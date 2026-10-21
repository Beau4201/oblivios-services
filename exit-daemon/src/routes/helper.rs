// SPDX-License-Identifier: Apache-2.0
//! What the route helper does with each message of the exit app (ADR-186). It holds the last specification it applied and
//! nothing else; the rules live in the kernel and are re-read each time, so a restart of the helper loses nothing.

use super::proto::{decode_msg, encode_reply, RouteMsg, RouteReply, ROUTE_PROTOCOL_VERSION};
use super::reconcile::{self, Netlink};
use super::spec::{plan, RouteSpec, SpecError};
use crate::control::ErrorCode;
use crate::server::{check_peer, serve_framed, Auth};
use std::io;
use std::os::unix::net::UnixStream;

/// The frames to write for one message and whether the connection ends after them.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HelperReply {
    pub msgs: Vec<RouteReply>,
    pub close: bool,
}

impl HelperReply {
    fn one(m: RouteReply) -> Self {
        Self { msgs: vec![m], close: false }
    }
}

pub struct RouteHelper<N: Netlink> {
    nl: N,
    last: Option<RouteSpec>,
}

fn spec_error(e: SpecError) -> ErrorCode {
    match e {
        SpecError::TooManyRanges => ErrorCode::TooMany,
        SpecError::BadIface | SpecError::BadRange | SpecError::Overlap | SpecError::BadNetId => ErrorCode::BadValue,
    }
}

impl<N: Netlink> RouteHelper<N> {
    pub fn new(nl: N) -> Self {
        Self { nl, last: None }
    }

    /// The specification that was applied last and not cleared since.
    pub fn applied_spec(&self) -> Option<&RouteSpec> {
        self.last.as_ref()
    }

    pub fn handle(&mut self, m: RouteMsg) -> HelperReply {
        match m {
            RouteMsg::Hello { version } if version == ROUTE_PROTOCOL_VERSION => {
                HelperReply::one(RouteReply::Ready { version: ROUTE_PROTOCOL_VERSION })
            }
            RouteMsg::Hello { .. } => {
                HelperReply { msgs: vec![RouteReply::Error { seq: 0, code: ErrorCode::Unsupported }], close: true }
            }
            RouteMsg::Apply { seq, spec } => {
                let ops = match plan(&spec) {
                    Ok(ops) => ops,
                    Err(e) => return HelperReply::one(RouteReply::Error { seq, code: spec_error(e) }),
                };
                match reconcile::apply(&mut self.nl, &ops) {
                    Ok(_) => {
                        self.last = Some(spec);
                        HelperReply::one(RouteReply::Ack { seq })
                    }
                    Err(_) => HelperReply::one(RouteReply::Error { seq, code: ErrorCode::Failed }),
                }
            }
            RouteMsg::Clear { seq } => match reconcile::clear(&mut self.nl) {
                Ok(_) => {
                    self.last = None;
                    HelperReply::one(RouteReply::Ack { seq })
                }
                Err(_) => HelperReply::one(RouteReply::Error { seq, code: ErrorCode::Failed }),
            },
            RouteMsg::GetApplied { seq } => match reconcile::read_band(&mut self.nl) {
                Ok(band) => {
                    let n = band.known.len() + band.foreign.len();
                    HelperReply::one(RouteReply::Applied { seq, rules: u16::try_from(n).unwrap_or(u16::MAX) })
                }
                Err(_) => HelperReply::one(RouteReply::Error { seq, code: ErrorCode::Failed }),
            },
        }
    }
}

/// Serves one connection of the exit app. The peer is checked before the first byte is read.
pub fn serve_route<N: Netlink>(stream: UnixStream, auth: &Auth, helper: &mut RouteHelper<N>) -> io::Result<()> {
    serve_framed(stream, |s| check_peer(s, auth), decode_msg, encode_reply, |m| {
        let r = helper.handle(m);
        (r.msgs, r.close)
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::routes::netlink as nl;
    use crate::routes::proto::{decode_reply, encode_msg};
    use crate::routes::reconcile::tests::FakeKernel;
    use crate::routes::spec::UidRange;
    use std::io::{Read, Write};

    fn r(start: u32, end: u32) -> UidRange {
        UidRange { start, end }
    }

    fn spec(steer: Vec<UidRange>) -> RouteSpec {
        RouteSpec { iface: "exit0".into(), steer, tor_guard: vec![], oem_netid: None }
    }

    fn helper() -> RouteHelper<FakeKernel> {
        RouteHelper::new(FakeKernel::default())
    }

    #[test]
    fn hello_of_the_right_version_gets_ready_and_another_version_ends_the_connection() {
        let mut h = helper();
        assert_eq!(h.handle(RouteMsg::Hello { version: 1 }), HelperReply::one(RouteReply::Ready { version: 1 }));
        for v in [0u8, 2, 255] {
            assert_eq!(
                h.handle(RouteMsg::Hello { version: v }),
                HelperReply { msgs: vec![RouteReply::Error { seq: 0, code: ErrorCode::Unsupported }], close: true }
            );
        }
    }

    #[test]
    fn apply_is_planned_reconciled_remembered_and_acknowledged() {
        let mut h = helper();
        let reply = h.handle(RouteMsg::Apply { seq: 4, spec: spec(vec![r(10000, 10230)]) });
        assert_eq!(reply, HelperReply::one(RouteReply::Ack { seq: 4 }));
        assert_eq!(h.nl.rules.len(), 6);
        assert_eq!(h.applied_spec(), Some(&spec(vec![r(10000, 10230)])));
    }

    #[test]
    fn a_specification_the_planner_refuses_changes_nothing_and_names_the_reason() {
        let mut h = helper();
        let cases = [
            (RouteSpec { iface: "Bad Name".into(), ..spec(vec![]) }, ErrorCode::BadValue),
            (spec(vec![r(1000, 2000)]), ErrorCode::BadValue),
            (spec(vec![r(10000, 10010), r(10005, 10020)]), ErrorCode::BadValue),
            (RouteSpec { oem_netid: Some(0), ..spec(vec![]) }, ErrorCode::BadValue),
            (spec((0..129u32).map(|i| r(10000 + 2 * i, 10000 + 2 * i)).collect()), ErrorCode::TooMany),
        ];
        for (s, code) in cases {
            assert_eq!(h.handle(RouteMsg::Apply { seq: 9, spec: s }), HelperReply::one(RouteReply::Error { seq: 9, code }));
        }
        assert!(h.nl.sent.is_empty(), "nothing reached the kernel");
        assert_eq!(h.applied_spec(), None);
    }

    #[test]
    fn a_kernel_refusal_is_failed_and_the_old_specification_stays_the_applied_one() {
        let mut h = helper();
        h.handle(RouteMsg::Apply { seq: 1, spec: spec(vec![r(10000, 10230)]) });
        h.nl.fail_with = Some((nl::RTM_NEWRULE, 1));
        let reply = h.handle(RouteMsg::Apply { seq: 2, spec: spec(vec![r(10000, 10099)]) });
        assert_eq!(reply, HelperReply::one(RouteReply::Error { seq: 2, code: ErrorCode::Failed }));
        assert_eq!(h.applied_spec(), Some(&spec(vec![r(10000, 10230)])));
    }

    #[test]
    fn clear_empties_the_band_forgets_the_specification_and_get_applied_counts_rules() {
        let mut h = helper();
        h.handle(RouteMsg::Apply { seq: 1, spec: spec(vec![r(10000, 10230)]) });
        assert_eq!(h.handle(RouteMsg::GetApplied { seq: 2 }), HelperReply::one(RouteReply::Applied { seq: 2, rules: 6 }));
        assert_eq!(h.handle(RouteMsg::Clear { seq: 3 }), HelperReply::one(RouteReply::Ack { seq: 3 }));
        assert_eq!(h.applied_spec(), None);
        assert_eq!(h.handle(RouteMsg::GetApplied { seq: 4 }), HelperReply::one(RouteReply::Applied { seq: 4, rules: 0 }));
    }

    #[test]
    fn a_kernel_that_does_not_answer_sensibly_is_failed_not_a_panic() {
        let mut h = helper();
        h.nl.garbage = true;
        for m in [
            RouteMsg::Apply { seq: 1, spec: spec(vec![r(10000, 10230)]) },
            RouteMsg::Clear { seq: 2 },
            RouteMsg::GetApplied { seq: 3 },
        ] {
            let seq = match &m {
                RouteMsg::Apply { seq, .. } | RouteMsg::Clear { seq } | RouteMsg::GetApplied { seq } => *seq,
                RouteMsg::Hello { .. } => unreachable!(),
            };
            assert_eq!(h.handle(m), HelperReply::one(RouteReply::Error { seq, code: ErrorCode::Failed }));
        }
    }

    #[test]
    fn over_a_socket_a_peer_with_the_wrong_uid_gets_nothing_and_the_right_one_is_served() {
        use std::os::unix::fs::MetadataExt;
        let me = std::fs::metadata("/proc/self").unwrap().uid();
        let read_reply = |c: &mut UnixStream| {
            let mut buf = Vec::new();
            let mut b = [0u8; 1];
            loop {
                match decode_reply(&buf) {
                    Ok((m, _)) => return m,
                    Err(crate::control::DecodeError::Incomplete) => {
                        c.read_exact(&mut b).unwrap();
                        buf.push(b[0]);
                    }
                    Err(e) => panic!("{e:?}"),
                }
            }
        };
        // The wrong uid.
        let (server, mut client) = UnixStream::pair().unwrap();
        let t = std::thread::spawn(move || serve_route(server, &Auth::Uid(me.wrapping_add(1)), &mut helper()));
        assert_eq!(t.join().unwrap().unwrap_err().kind(), io::ErrorKind::PermissionDenied);
        let mut b = [0u8; 4];
        assert!(matches!(client.read(&mut b), Ok(0) | Err(_)), "no reply of any kind");
        // The right uid.
        let (server, mut client) = UnixStream::pair().unwrap();
        client.set_read_timeout(Some(std::time::Duration::from_secs(5))).unwrap();
        let t = std::thread::spawn(move || serve_route(server, &Auth::Uid(me), &mut helper()));
        client.write_all(&encode_msg(&RouteMsg::Hello { version: 1 })).unwrap();
        assert_eq!(read_reply(&mut client), RouteReply::Ready { version: 1 });
        client.write_all(&encode_msg(&RouteMsg::Apply { seq: 7, spec: spec(vec![r(10000, 10230)]) })).unwrap();
        assert_eq!(read_reply(&mut client), RouteReply::Ack { seq: 7 });
        client.write_all(&encode_msg(&RouteMsg::GetApplied { seq: 8 })).unwrap();
        assert_eq!(read_reply(&mut client), RouteReply::Applied { seq: 8, rules: 6 });
        drop(client);
        assert!(t.join().unwrap().is_ok());
    }
}
