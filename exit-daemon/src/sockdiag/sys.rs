// SPDX-License-Identifier: Apache-2.0
//! The only `unsafe` of the crate: one netlink `sock_diag` socket, one request sent, one reply received. Nothing here
//! interprets a byte of either; `super` builds the request and parses the reply with bounds-checked slices.
#![allow(unsafe_code)]

use std::io;
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};

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
