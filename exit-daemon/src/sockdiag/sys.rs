// SPDX-License-Identifier: Apache-2.0
//! The only `unsafe` of the crate: one netlink `sock_diag` socket, one request sent, one reply received, and the
//! `SO_PEERCRED` read of the control socket (the standard library's `peer_cred` is not stable). Nothing here interprets a
//! byte of a request or a reply; `super` builds the request and parses the reply with bounds-checked slices.
#![allow(unsafe_code)]

use std::io;
use std::os::fd::{AsRawFd, BorrowedFd, FromRawFd, OwnedFd};

/// A reply to one exact-socket lookup is about 90 bytes; anything larger is not an answer to this request.
const REPLY_MAX: usize = 8192;
/// A kernel that does not answer must not hold a flow forever.
const TIMEOUT_SECS: libc::time_t = 2;

fn cvt(ret: libc::c_int) -> io::Result<libc::c_int> {
    if ret < 0 {
        Err(io::Error::last_os_error())
    } else {
        Ok(ret)
    }
}

/// Sends `request` on a fresh `NETLINK_SOCK_DIAG` socket and returns the first datagram that comes back.
pub fn query(request: &[u8]) -> io::Result<Vec<u8>> {
    // SAFETY: socket(2) takes no pointers; a negative result is turned into an error before the fd is used.
    let raw = cvt(unsafe { libc::socket(libc::AF_NETLINK, libc::SOCK_RAW | libc::SOCK_CLOEXEC, libc::NETLINK_SOCK_DIAG) })?;
    // SAFETY: `raw` is a valid descriptor that nothing else owns; the OwnedFd closes it on every path out.
    let fd = unsafe { OwnedFd::from_raw_fd(raw) };

    let timeout = libc::timeval { tv_sec: TIMEOUT_SECS, tv_usec: 0 };
    for opt in [libc::SO_RCVTIMEO, libc::SO_SNDTIMEO] {
        // SAFETY: the pointer and length describe `timeout`, which outlives the call.
        cvt(unsafe {
            libc::setsockopt(
                fd.as_raw_fd(),
                libc::SOL_SOCKET,
                opt,
                (&timeout as *const libc::timeval).cast(),
                std::mem::size_of::<libc::timeval>() as libc::socklen_t,
            )
        })?;
    }

    loop {
        // SAFETY: the pointer and length describe `request`, which outlives the call.
        let n = unsafe { libc::send(fd.as_raw_fd(), request.as_ptr().cast(), request.len(), 0) };
        if n < 0 {
            let e = io::Error::last_os_error();
            if e.kind() == io::ErrorKind::Interrupted {
                continue;
            }
            return Err(e);
        }
        if n as usize != request.len() {
            return Err(io::Error::new(io::ErrorKind::WriteZero, "short netlink send"));
        }
        break;
    }

    let mut buf = vec![0u8; REPLY_MAX];
    loop {
        // SAFETY: the pointer and length describe `buf`, which is writable for `buf.len()` bytes. MSG_TRUNC makes the
        // return value the real datagram length, so a longer reply is seen and refused instead of cut.
        let n = unsafe { libc::recv(fd.as_raw_fd(), buf.as_mut_ptr().cast(), buf.len(), libc::MSG_TRUNC) };
        if n < 0 {
            let e = io::Error::last_os_error();
            if e.kind() == io::ErrorKind::Interrupted {
                continue;
            }
            return Err(e);
        }
        let n = n as usize;
        if n > buf.len() {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "netlink reply too large"));
        }
        buf.truncate(n);
        return Ok(buf);
    }
}

/// The uid of the process on the other end of a connected Unix socket, as the kernel recorded it at `connect`.
pub fn peer_uid(fd: BorrowedFd<'_>) -> io::Result<u32> {
    let mut cred = libc::ucred { pid: 0, uid: 0, gid: 0 };
    let mut len = std::mem::size_of::<libc::ucred>() as libc::socklen_t;
    // SAFETY: the pointers describe `cred` and `len`, which outlive the call; `fd` is a valid descriptor.
    cvt(unsafe {
        libc::getsockopt(fd.as_raw_fd(), libc::SOL_SOCKET, libc::SO_PEERCRED, (&mut cred as *mut libc::ucred).cast(), &mut len)
    })?;
    if len as usize != std::mem::size_of::<libc::ucred>() {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "short SO_PEERCRED"));
    }
    Ok(cred.uid)
}

/// The longest SELinux context this module reads. A real one is about 40 bytes; a longer answer is refused.
const CONTEXT_MAX: usize = 256;

/// The SELinux context of the process on the other end of a connected Unix socket (`SO_PEERSEC`), without the NUL that
/// the kernel appends. A kernel without SELinux answers with an error, which the caller must treat as a refusal.
pub fn peer_context(fd: BorrowedFd<'_>) -> io::Result<String> {
    let mut buf = [0u8; CONTEXT_MAX];
    let mut len = buf.len() as libc::socklen_t;
    // SAFETY: the pointers describe `buf` and `len`, which outlive the call; `len` tells the kernel the room it has.
    cvt(unsafe { libc::getsockopt(fd.as_raw_fd(), libc::SOL_SOCKET, libc::SO_PEERSEC, buf.as_mut_ptr().cast(), &mut len) })?;
    context_from(&buf, len as usize)
}

/// The context text in the first `len` bytes of `buf`: the kernel counts the trailing NUL in `len`, the text does not.
fn context_from(buf: &[u8], len: usize) -> io::Result<String> {
    let got = buf
        .get(..len)
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "SO_PEERSEC length out of range"))?;
    let got = got.strip_suffix(&[0]).unwrap_or(got);
    String::from_utf8(got.to_vec()).map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "SO_PEERSEC is not UTF-8"))
}

#[cfg(test)]
mod tests {
    use super::context_from;

    #[test]
    fn the_context_loses_the_nul_the_kernel_counts() {
        assert_eq!(context_from(b"u:r:exit_app:s0\0\0\0", 16).unwrap(), "u:r:exit_app:s0");
        assert_eq!(context_from(b"u:r:exit_app:s0", 15).unwrap(), "u:r:exit_app:s0", "no NUL to strip");
        assert_eq!(context_from(b"abc\0", 4).unwrap(), "abc");
        assert_eq!(context_from(b"", 0).unwrap(), "");
        assert_eq!(context_from(b"\0", 1).unwrap(), "");
    }

    #[test]
    fn a_length_beyond_the_buffer_or_bytes_that_are_not_text_are_refused() {
        assert!(context_from(b"abc", 4).is_err());
        assert!(context_from(&[], 1).is_err());
        assert!(context_from(&[0xff, 0xfe, 0], 3).is_err());
    }

    #[test]
    fn only_the_first_len_bytes_count() {
        assert_eq!(context_from(b"abcdef", 3).unwrap(), "abc");
    }
}

/// Takes over a listening Unix socket that `init` created for this service (`socket <name> stream ...` in the rc file)
/// and handed down as descriptor `raw`. The descriptor is touched only after it is shown to be a listening Unix socket
/// (a datagram socket never listens); a number below 3 (standard streams) is refused outright.
pub fn listener_from_fd(raw: i32) -> io::Result<std::os::unix::net::UnixListener> {
    use std::os::unix::net::UnixListener;
    if raw < 3 {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "descriptor below 3"));
    }
    let get = |opt: libc::c_int| -> io::Result<libc::c_int> {
        let mut v: libc::c_int = 0;
        let mut len = std::mem::size_of::<libc::c_int>() as libc::socklen_t;
        // SAFETY: the pointers describe `v` and `len`, which outlive the call; an unopened `raw` is an EBADF error.
        cvt(unsafe { libc::getsockopt(raw, libc::SOL_SOCKET, opt, (&mut v as *mut libc::c_int).cast(), &mut len) })?;
        Ok(v)
    };
    let not_ours = |what: &'static str| io::Error::new(io::ErrorKind::InvalidInput, what);
    if get(libc::SO_DOMAIN)? != libc::AF_UNIX {
        return Err(not_ours("descriptor is not a Unix socket"));
    }
    if get(libc::SO_ACCEPTCONN)? != 1 {
        return Err(not_ours("descriptor is not listening"));
    }
    // SAFETY: `raw` is an open descriptor that `init` passed to this process for this purpose and that nothing else in
    // the process owns; the checks above show that it is a listening Unix stream socket.
    Ok(unsafe { UnixListener::from_raw_fd(raw) })
}
