// SPDX-License-Identifier: Apache-2.0
//! Owner uid of a flow, from the kernel: one `sock_diag` request for the exact 4-tuple of the socket the app opened.
//! The daemon uses it as the isolation key of a flow (one Tor circuit family per uid) and refuses a flow it cannot
//! attribute. `sys` holds the only `unsafe`; request building and reply parsing are safe Rust over bounds-checked slices.

use std::io;
use std::net::{IpAddr, SocketAddr};

#[allow(unsafe_code)]
mod sys;

const NLMSG_HEADER: usize = 16;
const NLMSG_ERROR: u16 = 2;
const SOCK_DIAG_BY_FAMILY: u16 = 20;
const NLM_F_REQUEST: u16 = 1;
const REQUEST_BODY: usize = 56;
const REPLY_BODY: usize = 72;
const INET_DIAG_NOCOOKIE: u32 = 0xffff_ffff;
const TCP_TIME_WAIT: u8 = 6;
const ENOENT: i32 = 2;
const IPPROTO_TCP: u8 = 6;
const IPPROTO_UDP: u8 = 17;
const AF_INET: u8 = 2;
const AF_INET6: u8 = 10;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Proto {
    Tcp,
    Udp,
}

/// What one reply says.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Reply {
    Uid(u32),
    /// The kernel has no such socket (`ENOENT`).
    NoSuchSocket,
    /// The socket exists but its uid is not an owner: a `TIME_WAIT` socket reports 0.
    NoOwner,
    /// The kernel refused the request with this positive errno.
    Errno(i32),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ParseError {
    /// The buffer ends before the message it announces.
    Truncated,
    /// The message announces a length that cannot hold its own header.
    BadLength,
    /// A message type or an acknowledgement that a lookup never answers with.
    Unexpected(u16),
}

fn put(v: &mut [u8], at: usize, bytes: &[u8]) {
    v[at..at + bytes.len()].copy_from_slice(bytes);
}

fn address(ip: IpAddr) -> [u8; 16] {
    let mut a = [0u8; 16];
    match ip {
        IpAddr::V4(v4) => a[..4].copy_from_slice(&v4.octets()),
        IpAddr::V6(v6) => a = v6.octets(),
    }
    a
}

/// The request for the socket whose local end is `local` and whose remote end is `remote`.
///
/// The kernel reads the two ends differently per protocol: for TCP `idiag_src` is the socket's own end, for UDP the
/// lookup is that of an incoming packet, so `idiag_src` is the peer (`net/ipv4/udp_diag.c`, `udp_dump_one`).
pub fn build_request(proto: Proto, local: SocketAddr, remote: SocketAddr) -> io::Result<Vec<u8>> {
    let family = match (local, remote) {
        (SocketAddr::V4(_), SocketAddr::V4(_)) => AF_INET,
        (SocketAddr::V6(_), SocketAddr::V6(_)) => AF_INET6,
        _ => return Err(io::Error::new(io::ErrorKind::InvalidInput, "mixed address families")),
    };
    let (src, dst, protocol) = match proto {
        Proto::Tcp => (local, remote, IPPROTO_TCP),
        Proto::Udp => (remote, local, IPPROTO_UDP),
    };
    let mut r = vec![0u8; NLMSG_HEADER + REQUEST_BODY];
    let len = r.len() as u32;
    put(&mut r, 0, &len.to_ne_bytes());
    put(&mut r, 4, &SOCK_DIAG_BY_FAMILY.to_ne_bytes());
    put(&mut r, 6, &NLM_F_REQUEST.to_ne_bytes());
    put(&mut r, 8, &1u32.to_ne_bytes()); // sequence
    r[16] = family;
    r[17] = protocol;
    // idiag_ext (18) stays 0; idiag_states: every state, so that a SYN_SENT socket is found too.
    put(&mut r, 20, &u32::MAX.to_ne_bytes());
    put(&mut r, 24, &src.port().to_be_bytes());
    put(&mut r, 26, &dst.port().to_be_bytes());
    put(&mut r, 28, &address(src.ip()));
    put(&mut r, 44, &address(dst.ip()));
    // idiag_if (60) stays 0; the cookie must be NOCOOKIE or the kernel compares it.
    put(&mut r, 64, &INET_DIAG_NOCOOKIE.to_ne_bytes());
    put(&mut r, 68, &INET_DIAG_NOCOOKIE.to_ne_bytes());
    Ok(r)
}

fn ne_u32(b: &[u8], at: usize) -> Result<u32, ParseError> {
    let s = b.get(at..at + 4).ok_or(ParseError::Truncated)?;
    Ok(u32::from_ne_bytes([s[0], s[1], s[2], s[3]]))
}

/// Reads the first netlink message of `buf`.
pub fn parse_reply(buf: &[u8]) -> Result<Reply, ParseError> {
    let len = ne_u32(buf, 0)? as usize;
    let ty = {
        let t = buf.get(4..6).ok_or(ParseError::Truncated)?;
        u16::from_ne_bytes([t[0], t[1]])
    };
    if len < NLMSG_HEADER {
        return Err(ParseError::BadLength);
    }
    let body = buf.get(NLMSG_HEADER..len).ok_or(ParseError::Truncated)?;
    match ty {
        NLMSG_ERROR => {
            let e = ne_u32(body, 0)? as i32;
            if e >= 0 {
                // 0 is an acknowledgement, which a lookup never gets.
                return Err(ParseError::Unexpected(ty));
            }
            let errno = e.checked_neg().unwrap_or(i32::MAX);
            Ok(if errno == ENOENT { Reply::NoSuchSocket } else { Reply::Errno(errno) })
        }
        SOCK_DIAG_BY_FAMILY => {
            if body.len() < REPLY_BODY {
                return Err(ParseError::Truncated);
            }
            let state = *body.get(1).ok_or(ParseError::Truncated)?;
            if state == TCP_TIME_WAIT {
                return Ok(Reply::NoOwner);
            }
            Ok(Reply::Uid(ne_u32(body, 64)?))
        }
        other => Err(ParseError::Unexpected(other)),
    }
}

/// The uid that owns the socket with this local and remote end, `Ok(None)` when the kernel knows no such socket.
pub fn owner_uid(proto: Proto, local: SocketAddr, remote: SocketAddr) -> io::Result<Option<u32>> {
    let request = build_request(proto, local, remote)?;
    let reply = sys::query(&request)?;
    match parse_reply(&reply) {
        Ok(Reply::Uid(uid)) => Ok(Some(uid)),
        Ok(Reply::NoSuchSocket | Reply::NoOwner) => Ok(None),
        Ok(Reply::Errno(e)) => Err(io::Error::from_raw_os_error(e)),
        Err(e) => Err(io::Error::new(io::ErrorKind::InvalidData, format!("sock_diag reply: {e:?}"))),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::{SocketAddr, TcpListener, TcpStream, UdpSocket};
    use std::os::unix::fs::MetadataExt;

    fn my_uid() -> u32 {
        std::fs::metadata("/proc/self").unwrap().uid()
    }

    /// A reply as the kernel sends it: netlink header, `inet_diag_msg` (72 bytes), `uid` at byte 64 of the body.
    fn reply(state: u8, uid: u32) -> Vec<u8> {
        let mut v = vec![0u8; 16 + 72];
        let len = v.len() as u32;
        v[..4].copy_from_slice(&len.to_ne_bytes());
        v[4..6].copy_from_slice(&20u16.to_ne_bytes()); // SOCK_DIAG_BY_FAMILY
        v[16] = 2; // AF_INET
        v[17] = state;
        v[16 + 64..16 + 68].copy_from_slice(&uid.to_ne_bytes());
        v
    }

    fn error_reply(errno: i32) -> Vec<u8> {
        let mut v = vec![0u8; 16 + 4 + 16];
        let len = v.len() as u32;
        v[..4].copy_from_slice(&len.to_ne_bytes());
        v[4..6].copy_from_slice(&2u16.to_ne_bytes()); // NLMSG_ERROR
        v[16..20].copy_from_slice(&errno.to_ne_bytes());
        v
    }

    #[test]
    fn tcp_client_and_accepted_socket_have_the_uid_of_the_process() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let client = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
        let (accepted, _) = listener.accept().unwrap();
        let uid = my_uid();
        assert_eq!(owner_uid(Proto::Tcp, client.local_addr().unwrap(), client.peer_addr().unwrap()).unwrap(), Some(uid));
        assert_eq!(owner_uid(Proto::Tcp, accepted.local_addr().unwrap(), accepted.peer_addr().unwrap()).unwrap(), Some(uid));
    }

    #[test]
    fn tcp_over_ipv6_loopback_has_the_uid_of_the_process() {
        let listener = TcpListener::bind("[::1]:0").unwrap();
        let client = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
        let _accepted = listener.accept().unwrap();
        assert_eq!(
            owner_uid(Proto::Tcp, client.local_addr().unwrap(), client.peer_addr().unwrap()).unwrap(),
            Some(my_uid())
        );
    }

    #[test]
    fn a_connected_udp_socket_has_the_uid_of_the_process() {
        let a = UdpSocket::bind("127.0.0.1:0").unwrap();
        let b = UdpSocket::bind("127.0.0.1:0").unwrap();
        a.connect(b.local_addr().unwrap()).unwrap();
        assert_eq!(
            owner_uid(Proto::Udp, a.local_addr().unwrap(), a.peer_addr().unwrap()).unwrap(),
            Some(my_uid())
        );
    }

    #[test]
    fn a_connected_udp_socket_over_ipv6_has_the_uid_of_the_process() {
        let a = UdpSocket::bind("[::1]:0").unwrap();
        let b = UdpSocket::bind("[::1]:0").unwrap();
        a.connect(b.local_addr().unwrap()).unwrap();
        assert_eq!(
            owner_uid(Proto::Udp, a.local_addr().unwrap(), a.peer_addr().unwrap()).unwrap(),
            Some(my_uid())
        );
    }

    #[test]
    fn the_tuple_of_a_closed_udp_socket_has_no_owner() {
        let b = UdpSocket::bind("127.0.0.1:0").unwrap();
        let (local, remote) = {
            let a = UdpSocket::bind("127.0.0.1:0").unwrap();
            a.connect(b.local_addr().unwrap()).unwrap();
            (a.local_addr().unwrap(), a.peer_addr().unwrap())
        };
        assert_eq!(owner_uid(Proto::Udp, local, remote).unwrap(), None);
    }

    #[test]
    fn a_wrong_remote_port_has_no_owner() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let client = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
        let _accepted = listener.accept().unwrap();
        let mut remote = client.peer_addr().unwrap();
        remote.set_port(remote.port().wrapping_add(1));
        assert_eq!(owner_uid(Proto::Tcp, client.local_addr().unwrap(), remote).unwrap(), None);
    }

    #[test]
    fn a_tcp_tuple_is_not_found_as_udp() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let client = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
        let _accepted = listener.accept().unwrap();
        assert_eq!(owner_uid(Proto::Udp, client.local_addr().unwrap(), client.peer_addr().unwrap()).unwrap(), None);
    }

    #[test]
    fn mixed_address_families_are_refused() {
        let v4: SocketAddr = "127.0.0.1:1".parse().unwrap();
        let v6: SocketAddr = "[::1]:1".parse().unwrap();
        assert_eq!(owner_uid(Proto::Tcp, v4, v6).unwrap_err().kind(), std::io::ErrorKind::InvalidInput);
        assert_eq!(owner_uid(Proto::Udp, v6, v4).unwrap_err().kind(), std::io::ErrorKind::InvalidInput);
    }

    #[test]
    fn the_request_names_family_protocol_states_and_the_tuple() {
        let local: SocketAddr = "10.1.2.3:4000".parse().unwrap();
        let remote: SocketAddr = "93.184.216.34:443".parse().unwrap();
        let r = build_request(Proto::Tcp, local, remote).unwrap();
        assert_eq!(r.len(), 16 + 56);
        assert_eq!(u32::from_ne_bytes(r[..4].try_into().unwrap()) as usize, r.len());
        assert_eq!(u16::from_ne_bytes(r[4..6].try_into().unwrap()), 20); // SOCK_DIAG_BY_FAMILY
        assert_eq!(u16::from_ne_bytes(r[6..8].try_into().unwrap()), 1); // NLM_F_REQUEST only
        assert_eq!(r[16], 2); // AF_INET
        assert_eq!(r[17], 6); // IPPROTO_TCP
        assert_eq!(r[18], 0); // no extensions
        assert_eq!(u32::from_ne_bytes(r[20..24].try_into().unwrap()), 0xffff_ffff); // every state
        assert_eq!(&r[24..26], &4000u16.to_be_bytes()); // sport, network order
        assert_eq!(&r[26..28], &443u16.to_be_bytes()); // dport
        assert_eq!(&r[28..32], &[10, 1, 2, 3]); // src
        assert_eq!(&r[44..48], &[93, 184, 216, 34]); // dst
        assert_eq!(&r[64..72], &[0xff; 8]); // INET_DIAG_NOCOOKIE
        let u = build_request(Proto::Udp, local, remote).unwrap();
        assert_eq!(u[17], 17); // IPPROTO_UDP
    }

    #[test]
    fn a_reply_with_a_uid_is_read() {
        assert_eq!(parse_reply(&reply(1, 10231)), Ok(Reply::Uid(10231)));
        assert_eq!(parse_reply(&reply(1, 0)), Ok(Reply::Uid(0)));
        assert_eq!(parse_reply(&reply(1, u32::MAX)), Ok(Reply::Uid(u32::MAX)));
    }

    #[test]
    fn a_time_wait_socket_has_no_owner() {
        // The kernel reports uid 0 for a TIME_WAIT socket; that is not the uid of the app and must not be used.
        assert_eq!(parse_reply(&reply(6, 0)), Ok(Reply::NoOwner));
    }

    #[test]
    fn enoent_means_no_such_socket_and_any_other_error_is_kept() {
        assert_eq!(parse_reply(&error_reply(-2)), Ok(Reply::NoSuchSocket));
        assert_eq!(parse_reply(&error_reply(-1)), Ok(Reply::Errno(1)));
        assert_eq!(parse_reply(&error_reply(-13)), Ok(Reply::Errno(13)));
        // An error of 0 is an acknowledgement, which a lookup never gets: it is not an answer.
        assert_eq!(parse_reply(&error_reply(0)), Err(ParseError::Unexpected(2)));
    }

    #[test]
    fn a_reply_that_is_not_an_answer_is_refused() {
        let mut done = reply(1, 1);
        done[4..6].copy_from_slice(&3u16.to_ne_bytes()); // NLMSG_DONE
        assert_eq!(parse_reply(&done), Err(ParseError::Unexpected(3)));
        let mut length = reply(1, 1);
        length[..4].copy_from_slice(&8u32.to_ne_bytes()); // shorter than its own header
        assert_eq!(parse_reply(&length), Err(ParseError::BadLength));
        let mut longer = reply(1, 1);
        longer[..4].copy_from_slice(&1000u32.to_ne_bytes()); // longer than the buffer
        assert_eq!(parse_reply(&longer), Err(ParseError::Truncated));
    }

    #[test]
    fn a_truncated_reply_is_an_error_at_every_length() {
        let full = reply(1, 10231);
        for n in 0..full.len() {
            assert!(parse_reply(&full[..n]).is_err(), "{n} bytes parsed");
        }
        let full = error_reply(-2);
        for n in 0..full.len() {
            assert!(parse_reply(&full[..n]).is_err(), "{n} bytes parsed");
        }
    }

    #[test]
    fn a_real_reply_cut_at_every_length_is_an_error_and_never_a_panic() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let client = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
        let _accepted = listener.accept().unwrap();
        let request =
            build_request(Proto::Tcp, client.local_addr().unwrap(), client.peer_addr().unwrap()).unwrap();
        let full = sys::query(&request).unwrap();
        assert_eq!(parse_reply(&full), Ok(Reply::Uid(my_uid())));
        for n in 0..full.len() {
            assert!(parse_reply(&full[..n]).is_err(), "{n} of {} bytes parsed", full.len());
        }
    }
}
