// SPDX-License-Identifier: Apache-2.0
//! The only `unsafe` of the route helper: one `NETLINK_ROUTE` socket, one request sent and its reply read, and the
//! interface name lookup. Nothing here interprets a byte; `netlink` builds the requests and parses the replies.
#![allow(unsafe_code)]

use super::netlink::{self, NLMSG_DONE, NLMSG_ERROR, NLM_F_DUMP};
use super::reconcile::Netlink;
use std::io;
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};

/// One datagram of a dump is a few KiB; a reply that needs more than this in all is not an answer to our request.
const DATAGRAM_MAX: usize = 32 * 1024;
const REPLY_MAX: usize = 1024 * 1024;
const TIMEOUT_SECS: libc::time_t = 2;

fn cvt(ret: libc::c_int) -> io::Result<libc::c_int> {
    if ret < 0 {
        Err(io::Error::last_os_error())
    } else {
        Ok(ret)
    }
}

pub struct KernelNetlink {
    fd: OwnedFd,
    /// The sequence number of the next interface lookup. The lookups are the only requests that this module numbers itself
    /// (the others come numbered from the reconciler), in a range of their own so that a late reply to a rule request is never
    /// taken for the answer of a lookup.
    lookup_seq: u32,
}

/// The first sequence number of a lookup: far above every number that the reconciler hands out (it starts at 0x1000, 0x2000
/// or 0x3000 and counts up by one per message, and a spec holds a few hundred messages at most).
const LOOKUP_SEQ_FIRST: u32 = 0x7000_0000;

impl KernelNetlink {
    pub fn open() -> io::Result<Self> {
        // SAFETY: socket(2) takes no pointers; a negative result is turned into an error before the fd is used.
        let raw = cvt(unsafe { libc::socket(libc::AF_NETLINK, libc::SOCK_RAW | libc::SOCK_CLOEXEC, libc::NETLINK_ROUTE) })?;
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
        Ok(Self { fd, lookup_seq: LOOKUP_SEQ_FIRST })
    }

    fn send(&self, msg: &[u8]) -> io::Result<()> {
        loop {
            // SAFETY: the pointer and length describe `msg`, which outlives the call.
            let n = unsafe { libc::send(self.fd.as_raw_fd(), msg.as_ptr().cast(), msg.len(), 0) };
            if n < 0 {
                let e = io::Error::last_os_error();
                if e.kind() == io::ErrorKind::Interrupted {
                    continue;
                }
                return Err(e);
            }
            if n as usize != msg.len() {
                return Err(io::Error::new(io::ErrorKind::WriteZero, "short netlink send"));
            }
            return Ok(());
        }
    }

    /// One datagram. MSG_TRUNC makes the return value the real length, so a longer datagram is seen and refused.
    fn recv(&self) -> io::Result<Vec<u8>> {
        let mut buf = vec![0u8; DATAGRAM_MAX];
        loop {
            // SAFETY: the pointer and length describe `buf`, which is writable for `buf.len()` bytes.
            let n = unsafe { libc::recv(self.fd.as_raw_fd(), buf.as_mut_ptr().cast(), buf.len(), libc::MSG_TRUNC) };
            if n < 0 {
                let e = io::Error::last_os_error();
                if e.kind() == io::ErrorKind::Interrupted {
                    continue;
                }
                return Err(e);
            }
            let n = n as usize;
            if n > buf.len() {
                return Err(io::Error::new(io::ErrorKind::InvalidData, "netlink datagram too large"));
            }
            buf.truncate(n);
            return Ok(buf);
        }
    }
}

/// Adds one datagram to the reply being collected and says whether the reply is complete. A datagram without a message of
/// this request (a late reply to an earlier one that timed out) adds nothing; an error message ends the reply, and so does
/// the end of a dump; a reply past [`REPLY_MAX`] is refused.
fn take_part(all: &mut Vec<u8>, part: &[u8], seq: u32, dump: bool) -> io::Result<bool> {
    let msgs = netlink::messages(part).map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "malformed netlink reply"))?;
    let ours: Vec<_> = msgs.iter().filter(|m| m.seq == seq).collect();
    let finished = ours.iter().any(|m| m.kind == NLMSG_ERROR || (dump && m.kind == NLMSG_DONE));
    if !ours.is_empty() {
        all.extend_from_slice(part);
    }
    if all.len() > REPLY_MAX {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "netlink reply too large"));
    }
    Ok(finished)
}

impl Netlink for KernelNetlink {
    fn request(&mut self, msg: &[u8]) -> io::Result<Vec<u8>> {
        let head = netlink::messages(msg).map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "malformed request"))?;
        let flags = msg.get(6..8).map(|b| u16::from_ne_bytes([b[0], b[1]])).unwrap_or(0);
        let dump = flags & NLM_F_DUMP == NLM_F_DUMP;
        let seq = head.first().map(|m| m.seq).unwrap_or(0);
        self.send(msg)?;
        let mut all: Vec<u8> = Vec::new();
        loop {
            let part = self.recv()?;
            if take_part(&mut all, &part, seq, dump)? {
                return Ok(all);
            }
        }
    }

    /// By an `RTM_GETLINK` on the socket that is already open. `if_nametoindex` would open an `AF_INET` socket for the lookup,
    /// which the helper's SELinux domain must not have (`exitd_route.te`: no inet socket of any kind); the service then failed
    /// at its first apply, found by `strace` of the real binary on the device (NET-009, lynx image 2026102303).
    fn ifindex(&mut self, name: &str) -> io::Result<u32> {
        if name.as_bytes().contains(&0) {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "interface name has a NUL"));
        }
        let seq = self.lookup_seq;
        self.lookup_seq = self.lookup_seq.wrapping_add(1).max(LOOKUP_SEQ_FIRST);
        let reply = self.request(&netlink::link_get_msg(name, seq))?;
        match netlink::parse_link_index(&reply, seq) {
            Ok(Ok(index)) => Ok(index),
            Ok(Err(errno)) => Err(io::Error::from_raw_os_error(errno)),
            Err(_) => Err(io::Error::new(io::ErrorKind::InvalidData, "malformed netlink reply")),
        }
    }
}

/// Tests against the real kernel. They need `CAP_NET_ADMIN`, which a test run does not have, so each test starts itself
/// again under `unshare --user --map-root-user --net` (a new network namespace in which the test is root and which
/// disappears with it) and the parent reports the child's result. A test that cannot create the namespace fails: a
/// skipped test would prove nothing. Only uid 0 is mapped in that namespace, so the rules use the uid range 0-0; the
/// encoding is the same for any value.
#[cfg(test)]
mod tests {
    use super::*;
    use crate::routes::reconcile::{apply, clear, read_band, Applied};
    use crate::routes::spec::{plan, Action, Family, Rule, RouteSpec, RuleOp, UidRange, BAND_FIRST};
    use std::process::Command;

    const MARK: &str = "EXITD_TEST_IN_NETNS";

    /// True in the child, which must run the test body; in the parent it runs the child and checks it, and returns false.
    fn in_private_netns(name: &str) -> bool {
        if std::env::var_os(MARK).is_some() {
            return true;
        }
        let exe = std::env::current_exe().expect("the test binary has a path");
        let out = Command::new("unshare")
            .args(["--user", "--map-root-user", "--net"])
            .arg(exe)
            .args(["--exact", name, "--nocapture", "--test-threads=1"])
            .env(MARK, "1")
            .output()
            .expect("unshare is installed on the test host");
        assert!(
            out.status.success(),
            "the test failed inside its namespace:\n{}\n{}",
            String::from_utf8_lossy(&out.stdout),
            String::from_utf8_lossy(&out.stderr)
        );
        false
    }

    fn ip(args: &[&str]) -> String {
        let out = Command::new("ip").args(args).output().expect("ip is installed on the test host");
        assert!(out.status.success(), "ip {args:?}: {}", String::from_utf8_lossy(&out.stderr));
        String::from_utf8_lossy(&out.stdout).into_owned()
    }

    /// The same operations with the uid ranges replaced by 0-0, the one uid that exists in the namespace.
    fn as_uid_zero(ops: Vec<RuleOp>) -> Vec<RuleOp> {
        ops.into_iter()
            .map(|op| match op {
                RuleOp::RuleAdd(r) => RuleOp::RuleAdd(Rule { uids: r.uids.map(|_| UidRange { start: 0, end: 0 }), ..r }),
                other => other,
            })
            .collect()
    }

    fn spec(netid: Option<u32>) -> RouteSpec {
        RouteSpec {
            iface: "lo".into(),
            steer: vec![UidRange { start: 10000, end: 10230 }],
            tor_guard: vec![UidRange { start: 10231, end: 10231 }],
            oem_netid: netid,
        }
    }

    fn rule_count(ops: &[RuleOp]) -> usize {
        ops.iter().filter(|o| matches!(o, RuleOp::RuleAdd(_))).count()
    }

    fn lines_with(text: &str, prio: &str) -> Vec<String> {
        text.lines().filter(|l| l.starts_with(prio)).map(str::to_owned).collect()
    }

    #[test]
    fn the_kernel_takes_the_plan_shows_it_as_written_and_gives_it_up() {
        if !in_private_netns("routes::sys::tests::the_kernel_takes_the_plan_shows_it_as_written_and_gives_it_up") {
            return;
        }
        let mut k = KernelNetlink::open().unwrap();
        ip(&["rule", "add", "priority", "20000", "lookup", "main"]); // outside the band: must survive everything
        let ops = as_uid_zero(plan(&spec(Some(101))).unwrap());
        let n = rule_count(&ops);

        let first = apply(&mut k, &ops).unwrap();
        assert_eq!(first, Applied { added: n, removed: 0, rules: n });

        // Read back by an independent tool (iproute2), not by this module.
        let v4 = ip(&["-4", "rule", "show"]);
        let v6 = ip(&["-6", "rule", "show"]);
        for (text, dst) in [(&v4, "198.18.0.0/15"), (&v6, "fd66:7265:6568:8000::/49")] {
            assert!(text.contains("13500:\tfrom all uidrange 0-0 ipproto udp dport 53 lookup 4747"), "{text}");
            assert!(text.contains("13501:\tfrom all uidrange 0-0 ipproto tcp dport 53 lookup 4747"), "{text}");
            assert!(text.contains(&format!("13510:\tfrom all to {dst} uidrange 0-0 lookup 4747")), "{text}");
            assert!(text.contains("13520:\tfrom all fwmark 0/0xffff uidrange 0-0 goto 13523"), "{text}");
            assert!(text.contains("13521:\tfrom all fwmark 0x65/0xffff uidrange 0-0 goto 13523"), "{text}");
            assert!(text.contains("13522:\tfrom all uidrange 0-0 prohibit"), "{text}");
            assert!(text.contains("13523:\tfrom all nop"), "{text}");
        }
        assert!(v4.contains("20000:\tfrom all lookup main"));
        assert!(ip(&["-4", "route", "show", "table", "4747"]).contains("default dev lo"));
        assert!(ip(&["-6", "route", "show", "table", "4747"]).contains("default dev lo"));
        let addrs = ip(&["addr", "show", "dev", "lo"]);
        assert!(addrs.contains("169.254.42.127/24"), "{addrs}");
        assert!(addrs.contains("fd66:7265:6568:1::2/64"), "{addrs}");
        assert!(ip(&["link", "show", "lo"]).contains("LOOPBACK,UP"));

        // What this module reads back from the kernel is what it wrote.
        let band = read_band(&mut k).unwrap();
        assert_eq!(band.known.len(), n);
        assert!(band.foreign.is_empty());
        let mut want: Vec<Rule> = ops.iter().filter_map(|o| if let RuleOp::RuleAdd(r) = o { Some(*r) } else { None }).collect();
        let mut have = band.known.clone();
        let key = |r: &Rule| format!("{r:?}");
        want.sort_by_key(key);
        have.sort_by_key(key);
        assert_eq!(have, want);

        // The same plan again changes nothing.
        assert_eq!(apply(&mut k, &ops).unwrap(), Applied { added: 0, removed: 0, rules: n });

        // A new network id changes the two rules that carry it (one per family) and nothing else.
        let ops2 = as_uid_zero(plan(&spec(Some(102))).unwrap());
        assert_eq!(apply(&mut k, &ops2).unwrap(), Applied { added: 2, removed: 2, rules: n });
        let v4 = ip(&["-4", "rule", "show"]);
        assert!(v4.contains("13521:\tfrom all fwmark 0x66/0xffff uidrange 0-0 goto 13523"), "{v4}");
        assert!(!v4.contains("0x65/0xffff"), "{v4}");

        // A rule that somebody else put in the band is removed; the one outside it is not.
        ip(&["rule", "add", "priority", "13555", "lookup", "main"]);
        assert_eq!(apply(&mut k, &ops2).unwrap(), Applied { added: 0, removed: 1, rules: n });
        assert!(lines_with(&ip(&["-4", "rule", "show"]), "13555").is_empty());

        // An interface that does not exist is an error and writes no rule.
        let mut bad = spec(None);
        bad.iface = "nosuchif0".into();
        let e = apply(&mut k, &as_uid_zero(plan(&bad).unwrap())).unwrap_err();
        assert_eq!(e.raw_os_error(), Some(libc::ENODEV));

        // Clear removes the band and the routes, keeps the address, and can be repeated.
        assert_eq!(clear(&mut k).unwrap(), Applied { added: 0, removed: n, rules: 0 });
        let v4 = ip(&["-4", "rule", "show"]);
        assert!(lines_with(&v4, "135").is_empty() && v4.contains("20000:\tfrom all lookup main"), "{v4}");
        assert!(ip(&["-4", "route", "show", "table", "4747"]).trim().is_empty());
        assert!(ip(&["-6", "route", "show", "table", "4747"]).trim().is_empty());
        assert!(ip(&["addr", "show", "dev", "lo"]).contains("169.254.42.127/24"));
        assert_eq!(clear(&mut k).unwrap(), Applied::default());
    }

    #[test]
    fn the_index_of_an_interface_is_read_by_netlink_and_a_missing_one_is_enodev() {
        if !in_private_netns("routes::sys::tests::the_index_of_an_interface_is_read_by_netlink_and_a_missing_one_is_enodev") {
            return;
        }
        let mut k = KernelNetlink::open().unwrap();
        // The loopback is the one interface of a new namespace; iproute2 names its index too.
        assert_eq!(k.ifindex("lo").unwrap(), 1);
        assert!(ip(&["-o", "link", "show", "lo"]).starts_with("1: lo"));
        // Again: the second lookup has its own sequence number and finds the same index.
        assert_eq!(k.ifindex("lo").unwrap(), 1);
        assert_eq!(k.ifindex("nosuchif0").unwrap_err().raw_os_error(), Some(libc::ENODEV));
        // The lookups that failed leave the socket usable.
        assert_eq!(k.ifindex("lo").unwrap(), 1);
    }

    #[test]
    fn a_band_too_big_for_one_datagram_is_read_and_removed_completely() {
        if !in_private_netns("routes::sys::tests::a_band_too_big_for_one_datagram_is_read_and_removed_completely") {
            return;
        }
        let mut k = KernelNetlink::open().unwrap();
        // 1500 rules written by somebody else in the band: more than one 32 KiB datagram of dump.
        let batch: String = (0..1500u32)
            .map(|i| format!("rule add priority {} from 10.{}.{}.0/24 lookup main\n", BAND_FIRST + (i % 800), (i / 250) + 1, i % 250))
            .collect();
        let path = std::env::temp_dir().join(format!("exit-daemon-batch-{}", std::process::id()));
        std::fs::write(&path, batch).unwrap();
        ip(&["-batch", path.to_str().unwrap()]);
        std::fs::remove_file(&path).unwrap();

        let band = read_band(&mut k).unwrap();
        assert_eq!(band.known.len() + band.foreign.len(), 1500);
        let empty = plan(&RouteSpec { iface: "lo".into(), steer: vec![], tor_guard: vec![], oem_netid: None }).unwrap();
        let out = apply(&mut k, &empty).unwrap();
        assert_eq!(out.removed, 1500);
        assert!(read_band(&mut k).unwrap().known.is_empty());
        assert!(lines_with(&ip(&["-4", "rule", "show"]), "13").is_empty());
        let _ = (Family::V4, Action::Nop);
    }

    #[test]
    fn a_malformed_request_is_refused_before_it_is_sent() {
        let mut k = KernelNetlink::open().unwrap();
        assert_eq!(k.request(&[1, 2, 3]).unwrap_err().kind(), io::ErrorKind::InvalidInput);
        assert_eq!(k.ifindex("a\0b").unwrap_err().kind(), io::ErrorKind::InvalidInput);
    }
}

#[cfg(test)]
mod collect_tests {
    use super::*;

    fn msg(kind: u16, seq: u32, payload_len: usize) -> Vec<u8> {
        let mut m = Vec::new();
        m.extend_from_slice(&((16 + payload_len) as u32).to_ne_bytes());
        m.extend_from_slice(&kind.to_ne_bytes());
        m.extend_from_slice(&0u16.to_ne_bytes());
        m.extend_from_slice(&seq.to_ne_bytes());
        m.extend_from_slice(&0u32.to_ne_bytes());
        m.resize(16 + payload_len, 0);
        m
    }

    #[test]
    fn a_datagram_of_another_request_adds_nothing_and_ends_nothing() {
        let mut all = Vec::new();
        // A late acknowledgement of request 6 while request 7 is waiting.
        assert_eq!(take_part(&mut all, &msg(NLMSG_ERROR, 6, 4), 7, false).unwrap(), false);
        assert!(all.is_empty(), "not ours, not kept");
        assert_eq!(take_part(&mut all, &msg(NLMSG_DONE, 6, 4), 7, true).unwrap(), false);
        assert!(all.is_empty());
        // Ours is kept and, as an error message, ends the reply.
        assert_eq!(take_part(&mut all, &msg(NLMSG_ERROR, 7, 4), 7, false).unwrap(), true);
        assert_eq!(all.len(), 20);
    }

    #[test]
    fn the_end_of_a_dump_ends_a_dump_and_only_a_dump() {
        let mut all = Vec::new();
        assert_eq!(take_part(&mut all, &msg(0x1c, 7, 8), 7, true).unwrap(), false, "a dump entry");
        assert_eq!(take_part(&mut all, &msg(NLMSG_DONE, 7, 4), 7, false).unwrap(), false, "DONE ends nothing but a dump");
        assert_eq!(take_part(&mut all, &msg(NLMSG_DONE, 7, 4), 7, true).unwrap(), true);
    }

    #[test]
    fn a_reply_past_the_limit_is_refused() {
        let mut all = vec![0u8; REPLY_MAX - 20];
        assert!(take_part(&mut all, &msg(0x1c, 7, 4), 7, true).is_ok(), "exactly at the limit: {}", all.len());
        assert_eq!(all.len(), REPLY_MAX);
        let mut all = vec![0u8; REPLY_MAX - 20];
        assert_eq!(take_part(&mut all, &msg(0x1c, 7, 8), 7, true).unwrap_err().kind(), io::ErrorKind::InvalidData);
        assert_eq!(take_part(&mut Vec::new(), &[1, 2, 3], 7, true).unwrap_err().kind(), io::ErrorKind::InvalidData, "not netlink");
    }
}
