// SPDX-License-Identifier: Apache-2.0
//! Brings the kernel's rules to a plan and back to nothing (ADR-186). The helper owns the priority band
//! `BAND_FIRST..=BAND_LAST` and the table [`TABLE`]; it reads what is in the band, adds what the plan lacks and only then
//! removes what the plan does not hold, so that a changed plan never leaves a moment without steering, and a plan that
//! is already in place sends no rule message at all. A rule outside the band is never touched.

use super::netlink::{self as nl, Verb};
use super::spec::{Family, Rule, RuleOp, BAND_FIRST, BAND_LAST, TABLE, TUN_V4, TUN_V6};
use std::io;

/// The kernel side of the helper: one request in, the kernel's reply messages out.
pub trait Netlink {
    /// Sends one request and returns the reply: for an ordinary request the `NLMSG_ERROR` message that carries the
    /// result, for a dump every entry up to and including `NLMSG_DONE`.
    fn request(&mut self, msg: &[u8]) -> io::Result<Vec<u8>>;
    /// The index of the interface with this name.
    fn ifindex(&mut self, name: &str) -> io::Result<u32>;
}

#[derive(Debug, Default, Clone, Copy, PartialEq, Eq)]
pub struct Applied {
    pub added: usize,
    pub removed: usize,
    /// Rules in the band after the call.
    pub rules: usize,
}

/// What the band holds: the rules the helper can describe, and the priorities of those it cannot.
#[derive(Debug, Default, PartialEq, Eq)]
pub struct Band {
    pub known: Vec<Rule>,
    pub foreign: Vec<(Family, u32)>,
}

fn in_band(priority: u32) -> bool {
    (BAND_FIRST..=BAND_LAST).contains(&priority)
}

fn bad_reply(why: &'static str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, why)
}

struct Seq(u32);
impl Seq {
    fn next(&mut self) -> u32 {
        self.0 = self.0.wrapping_add(1);
        self.0
    }
}

/// Sends a request that is answered with an acknowledgement. An errno in `tolerated` counts as success.
fn ack(k: &mut dyn Netlink, msg: &[u8], tolerated: &[i32]) -> io::Result<()> {
    let reply = k.request(msg)?;
    let msgs = nl::messages(&reply).map_err(|_| bad_reply("malformed netlink reply"))?;
    let code = msgs
        .iter()
        .find(|m| m.kind == nl::NLMSG_ERROR)
        .map(nl::error_code)
        .ok_or_else(|| bad_reply("no acknowledgement in the netlink reply"))?
        .map_err(|_| bad_reply("malformed netlink acknowledgement"))?;
    if code == 0 || tolerated.contains(&code) {
        Ok(())
    } else {
        Err(io::Error::from_raw_os_error(code))
    }
}

const EEXIST: i32 = 17;
const ENOENT: i32 = 2;
const ESRCH: i32 = 3;

/// Reads the rules of both families that sit in the band.
pub fn read_band(k: &mut dyn Netlink) -> io::Result<Band> {
    let mut seq = Seq(0x1000);
    let mut band = Band::default();
    for family in [Family::V4, Family::V6] {
        let reply = k.request(&nl::dump_rules_msg(family, seq.next()))?;
        let msgs = nl::messages(&reply).map_err(|_| bad_reply("malformed netlink dump"))?;
        let mut done = false;
        for m in msgs {
            match m.kind {
                nl::NLMSG_DONE => done = true,
                nl::NLMSG_ERROR => {
                    let code = nl::error_code(&m).map_err(|_| bad_reply("malformed netlink error"))?;
                    if code != 0 {
                        return Err(io::Error::from_raw_os_error(code));
                    }
                }
                nl::RTM_NEWRULE => {
                    let Some((fam, prio)) = nl::rule_priority(m.payload).map_err(|_| bad_reply("malformed rule"))? else {
                        continue;
                    };
                    if !in_band(prio) {
                        continue;
                    }
                    match nl::parse_rule(m.payload).map_err(|_| bad_reply("malformed rule"))? {
                        Some(rule) => band.known.push(rule),
                        None => band.foreign.push((fam, prio)),
                    }
                }
                _ => {}
            }
        }
        if !done {
            return Err(bad_reply("the rule dump did not end"));
        }
    }
    Ok(band)
}

fn interface_ops(k: &mut dyn Netlink, seq: &mut Seq, ops: &[RuleOp]) -> io::Result<()> {
    for op in ops {
        match op {
            RuleOp::AddrAdd { iface, family } => {
                let idx = k.ifindex(iface)?;
                let msg = match family {
                    Family::V4 => nl::addr_v4_msg(idx, TUN_V4.0, TUN_V4.1, seq.next()),
                    Family::V6 => nl::addr_v6_msg(idx, TUN_V6.0, TUN_V6.1, seq.next()),
                };
                ack(k, &msg, &[EEXIST])?;
            }
            RuleOp::LinkUp { iface } => ack(k, &nl::link_up_msg(iface, seq.next()), &[])?,
            RuleOp::RouteDefault { iface, family } => {
                let idx = k.ifindex(iface)?;
                ack(k, &nl::route_default_msg(idx, *family, TABLE, seq.next()), &[])?;
            }
            RuleOp::RuleAdd(_) => {}
        }
    }
    Ok(())
}

/// Makes the band equal to the rules of `plan` and the interface ready. Stops at the first error and leaves what it
/// already did in place; calling it again with the same plan finishes the job.
pub fn apply(k: &mut dyn Netlink, plan: &[RuleOp]) -> io::Result<Applied> {
    let mut seq = Seq(0x2000);
    interface_ops(k, &mut seq, plan)?;
    let desired: Vec<Rule> = plan.iter().filter_map(|o| if let RuleOp::RuleAdd(r) = o { Some(*r) } else { None }).collect();
    if desired.iter().any(|r| !in_band(r.priority)) {
        // plan() never gives such a rule; refusing here keeps the band guarantee independent of the planner.
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "rule outside the band"));
    }
    let band = read_band(k)?;

    // Match the plan against the band as multisets: what the plan has and the band lacks is to be added (in the plan's
    // order), what the band has and the plan lacks is stale.
    let mut unmatched = band.known.clone();
    let mut missing: Vec<Rule> = Vec::new();
    for d in &desired {
        match unmatched.iter().position(|h| h == d) {
            Some(i) => {
                unmatched.swap_remove(i);
            }
            None => missing.push(*d),
        }
    }
    let stale = unmatched;

    let mut out = Applied { rules: desired.len(), ..Applied::default() };
    // Add first, remove second: a changed plan overlaps for a moment instead of leaving a gap.
    for r in &missing {
        ack(k, &nl::rule_msg(Verb::Add, r, seq.next()), &[EEXIST])?;
        out.added += 1;
    }
    for r in &stale {
        ack(k, &nl::rule_msg(Verb::Del, r, seq.next()), &[ENOENT])?;
        out.removed += 1;
    }
    for (family, prio) in &band.foreign {
        ack(k, &nl::rule_del_priority_msg(*family, *prio, seq.next()), &[ENOENT])?;
        out.removed += 1;
    }
    Ok(out)
}

/// Removes every rule in the band and the default routes of the table. The addresses and the interface are the
/// daemon's and stay.
pub fn clear(k: &mut dyn Netlink) -> io::Result<Applied> {
    let mut seq = Seq(0x3000);
    let band = read_band(k)?;
    let mut out = Applied::default();
    for r in &band.known {
        ack(k, &nl::rule_msg(Verb::Del, r, seq.next()), &[ENOENT])?;
        out.removed += 1;
    }
    for (family, prio) in &band.foreign {
        ack(k, &nl::rule_del_priority_msg(*family, *prio, seq.next()), &[ENOENT])?;
        out.removed += 1;
    }
    for family in [Family::V4, Family::V6] {
        ack(k, &nl::route_default_del_msg(family, TABLE, seq.next()), &[ESRCH, ENOENT])?;
    }
    Ok(out)
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use crate::routes::spec::{plan, RouteSpec, UidRange};

    /// A stand-in for the kernel that understands the messages of this module: it stores the rules, answers dumps with
    /// the stored rules (re-encoded the way the kernel dumps them), and records every request.
    #[derive(Default)]
    pub(crate) struct FakeKernel {
        pub rules: Vec<Rule>,
        pub foreign_unreadable: Vec<(Family, u32)>,
        pub routes: Vec<(Family, u32)>,
        pub sent: Vec<u16>,
        pub fail_with: Option<(u16, i32)>,
        pub garbage: bool,
        /// Answers every request with nothing at all: no acknowledgement and no error.
        pub silent: bool,
    }

    fn error_msg(seq: u32, errno: i32) -> Vec<u8> {
        let mut m = Vec::new();
        m.extend_from_slice(&((16 + 4) as u32).to_ne_bytes());
        m.extend_from_slice(&nl::NLMSG_ERROR.to_ne_bytes());
        m.extend_from_slice(&0u16.to_ne_bytes());
        m.extend_from_slice(&seq.to_ne_bytes());
        m.extend_from_slice(&0u32.to_ne_bytes());
        m.extend_from_slice(&(-errno).to_ne_bytes());
        m
    }

    fn done_msg(seq: u32) -> Vec<u8> {
        let mut m = Vec::new();
        m.extend_from_slice(&16u32.to_ne_bytes());
        m.extend_from_slice(&nl::NLMSG_DONE.to_ne_bytes());
        m.extend_from_slice(&2u16.to_ne_bytes());
        m.extend_from_slice(&seq.to_ne_bytes());
        m.extend_from_slice(&0u32.to_ne_bytes());
        m
    }

    impl Netlink for FakeKernel {
        fn request(&mut self, msg: &[u8]) -> io::Result<Vec<u8>> {
            let head = nl::messages(msg).unwrap().remove(0);
            self.sent.push(head.kind);
            if self.garbage {
                return Ok(vec![1, 2, 3]);
            }
            if self.silent {
                return Ok(vec![]);
            }
            if let Some((kind, errno)) = self.fail_with {
                if kind == head.kind {
                    return Ok(error_msg(head.seq, errno));
                }
            }
            match head.kind {
                nl::RTM_NEWRULE => {
                    let r = nl::parse_rule(head.payload).unwrap().expect("a rule we can read");
                    if self.rules.contains(&r) {
                        return Ok(error_msg(head.seq, EEXIST));
                    }
                    self.rules.push(r);
                    Ok(error_msg(head.seq, 0))
                }
                nl::RTM_DELRULE => {
                    let by_priority = nl::parse_rule(head.payload).unwrap().is_none();
                    if by_priority {
                        let (fam, prio) = nl::rule_priority(head.payload).unwrap().unwrap();
                        if let Some(i) = self.foreign_unreadable.iter().position(|x| *x == (fam, prio)) {
                            self.foreign_unreadable.remove(i);
                            return Ok(error_msg(head.seq, 0));
                        }
                        return Ok(error_msg(head.seq, ENOENT));
                    }
                    let r = nl::parse_rule(head.payload).unwrap().unwrap();
                    match self.rules.iter().position(|x| *x == r) {
                        Some(i) => {
                            self.rules.remove(i);
                            Ok(error_msg(head.seq, 0))
                        }
                        None => Ok(error_msg(head.seq, ENOENT)),
                    }
                }
                nl::RTM_GETRULE => {
                    let fam = if head.payload[0] == 2 { Family::V4 } else { Family::V6 };
                    let mut out = Vec::new();
                    for r in self.rules.iter().filter(|r| r.family == fam) {
                        out.extend(nl::rule_msg(Verb::Add, r, head.seq));
                    }
                    for (f, p) in self.foreign_unreadable.iter().filter(|(f, _)| *f == fam) {
                        // A rule with an action this helper never writes (unreachable), so parse_rule cannot describe it.
                        let mut m = nl::rule_msg(
                            Verb::Add,
                            &Rule {
                                priority: *p,
                                family: *f,
                                uids: None,
                                proto: None,
                                dport: None,
                                dst: None,
                                fwmark: None,
                                action: crate::routes::spec::Action::Nop,
                            },
                            head.seq,
                        );
                        m[nl::NLMSG_HDR + 7] = 7;
                        out.extend(m);
                    }
                    out.extend(done_msg(head.seq));
                    Ok(out)
                }
                nl::RTM_NEWROUTE => {
                    let fam = if head.payload[0] == 2 { Family::V4 } else { Family::V6 };
                    if !self.routes.contains(&(fam, TABLE)) {
                        self.routes.push((fam, TABLE));
                    }
                    Ok(error_msg(head.seq, 0))
                }
                nl::RTM_DELROUTE => {
                    let fam = if head.payload[0] == 2 { Family::V4 } else { Family::V6 };
                    match self.routes.iter().position(|x| *x == (fam, TABLE)) {
                        Some(i) => {
                            self.routes.remove(i);
                            Ok(error_msg(head.seq, 0))
                        }
                        None => Ok(error_msg(head.seq, ESRCH)),
                    }
                }
                nl::RTM_NEWADDR | nl::RTM_SETLINK => Ok(error_msg(head.seq, 0)),
                other => panic!("the helper sent a message kind it must not: {other}"),
            }
        }

        fn ifindex(&mut self, name: &str) -> io::Result<u32> {
            if name == "exit0" {
                Ok(7)
            } else {
                Err(io::Error::from_raw_os_error(19))
            }
        }
    }

    fn r(start: u32, end: u32) -> UidRange {
        UidRange { start, end }
    }

    fn plan_of(steer: Vec<UidRange>, guard: Vec<UidRange>, netid: Option<u32>) -> Vec<RuleOp> {
        plan(&RouteSpec { iface: "exit0".into(), steer, tor_guard: guard, oem_netid: netid }).unwrap()
    }

    fn rule_msgs(k: &FakeKernel) -> usize {
        k.sent.iter().filter(|x| **x == nl::RTM_NEWRULE || **x == nl::RTM_DELRULE).count()
    }

    fn outside(priority: u32) -> Rule {
        Rule {
            priority,
            family: Family::V4,
            uids: Some(UidRange { start: 10000, end: 10000 }),
            proto: None,
            dport: None,
            dst: None,
            fwmark: None,
            action: crate::routes::spec::Action::Lookup(1050),
        }
    }

    #[test]
    fn the_first_apply_adds_every_rule_and_a_second_one_sends_no_rule_message() {
        let mut k = FakeKernel::default();
        let p = plan_of(vec![r(10000, 10230)], vec![r(10231, 10231)], Some(101));
        let want_rules = p.iter().filter(|o| matches!(o, RuleOp::RuleAdd(_))).count();
        let first = apply(&mut k, &p).unwrap();
        assert_eq!(first, Applied { added: want_rules, removed: 0, rules: want_rules });
        assert_eq!(k.rules.len(), want_rules);
        assert_eq!(k.routes.len(), 2);
        k.sent.clear();
        let second = apply(&mut k, &p).unwrap();
        assert_eq!(second, Applied { added: 0, removed: 0, rules: want_rules });
        assert_eq!(rule_msgs(&k), 0, "an unchanged plan must not touch a rule, only read the dump");
        assert!(k.sent.contains(&nl::RTM_GETRULE));
    }

    #[test]
    fn a_changed_plan_sends_exactly_the_difference_and_adds_before_it_removes() {
        let mut k = FakeKernel::default();
        apply(&mut k, &plan_of(vec![r(10000, 10230)], vec![], None)).unwrap();
        k.sent.clear();
        let out = apply(&mut k, &plan_of(vec![r(10000, 10099)], vec![], None)).unwrap();
        assert_eq!(out.added, 6, "the six rules of the new range");
        assert_eq!(out.removed, 6, "the six rules of the old range");
        let first_del = k.sent.iter().position(|x| *x == nl::RTM_DELRULE).unwrap();
        let last_add = k.sent.iter().rposition(|x| *x == nl::RTM_NEWRULE).unwrap();
        assert!(last_add < first_del, "every addition comes before the first removal: {:?}", k.sent);
        assert_eq!(k.rules.len(), 6);
        assert!(k.rules.iter().all(|x| x.uids == Some(r(10000, 10099)) ));
    }

    #[test]
    fn a_rule_outside_the_band_is_never_touched() {
        let mut k = FakeKernel::default();
        for prio in [100, 13099, 14000, 16000, 29040] {
            k.rules.push(outside(prio));
        }
        apply(&mut k, &plan_of(vec![r(10000, 10230)], vec![], None)).unwrap();
        clear(&mut k).unwrap();
        for prio in [100, 13099, 14000, 16000, 29040] {
            assert!(k.rules.contains(&outside(prio)), "rule {prio} was touched");
        }
        assert_eq!(k.rules.len(), 5);
    }

    #[test]
    fn the_edges_of_the_band_are_inside_it() {
        let mut k = FakeKernel::default();
        k.rules.push(outside(BAND_FIRST));
        k.rules.push(outside(BAND_LAST));
        k.rules.push(outside(BAND_FIRST - 1));
        k.rules.push(outside(BAND_LAST + 1));
        apply(&mut k, &plan_of(vec![], vec![], None)).unwrap();
        assert_eq!(k.rules.len(), 2, "the two inside the band are removed, the two next to it stay");
        assert!(k.rules.contains(&outside(BAND_FIRST - 1)) && k.rules.contains(&outside(BAND_LAST + 1)));
    }

    #[test]
    fn a_stale_or_unreadable_rule_in_the_band_is_removed() {
        let mut k = FakeKernel::default();
        k.rules.push(outside(13555));
        k.foreign_unreadable.push((Family::V4, 13600));
        k.foreign_unreadable.push((Family::V6, 13601));
        let out = apply(&mut k, &plan_of(vec![], vec![], None)).unwrap();
        assert_eq!(out.removed, 3);
        assert!(k.rules.is_empty() && k.foreign_unreadable.is_empty());
    }

    #[test]
    fn a_duplicate_in_the_kernel_is_removed_down_to_the_plan() {
        let mut k = FakeKernel::default();
        let p = plan_of(vec![r(10000, 10230)], vec![], None);
        apply(&mut k, &p).unwrap();
        let extra = k.rules[0];
        // The fake refuses duplicates like the kernel does with EXCL, so fake an older duplicate directly.
        k.rules.push(extra);
        let out = apply(&mut k, &p).unwrap();
        assert_eq!((out.added, out.removed), (0, 1));
        assert_eq!(k.rules.len(), 6);
    }

    #[test]
    fn eexist_on_an_addition_and_enoent_on_a_removal_count_as_done() {
        let mut k = FakeKernel::default();
        k.fail_with = Some((nl::RTM_NEWRULE, EEXIST));
        let out = apply(&mut k, &plan_of(vec![r(10000, 10230)], vec![], None)).unwrap();
        assert_eq!(out.added, 6);
        let mut k = FakeKernel::default();
        k.rules.push(outside(13555));
        k.fail_with = Some((nl::RTM_DELRULE, ENOENT));
        assert!(apply(&mut k, &plan_of(vec![], vec![], None)).is_ok());
    }

    #[test]
    fn any_other_errno_stops_the_apply_with_that_error() {
        for (kind, errno) in [(nl::RTM_NEWRULE, 1), (nl::RTM_NEWRULE, 22), (nl::RTM_NEWROUTE, 1), (nl::RTM_SETLINK, 19), (nl::RTM_NEWADDR, 1)] {
            let mut k = FakeKernel::default();
            k.fail_with = Some((kind, errno));
            let e = apply(&mut k, &plan_of(vec![r(10000, 10230)], vec![], None)).unwrap_err();
            assert_eq!(e.raw_os_error(), Some(errno), "kind {kind}");
        }
        let mut k = FakeKernel::default();
        k.fail_with = Some((nl::RTM_DELRULE, 1));
        k.rules.push(outside(13555));
        assert_eq!(apply(&mut k, &plan_of(vec![], vec![], None)).unwrap_err().raw_os_error(), Some(1));
    }

    #[test]
    fn a_missing_interface_is_an_error_and_no_rule_is_written() {
        let mut k = FakeKernel::default();
        let mut spec = RouteSpec { iface: "other".into(), steer: vec![r(10000, 10230)], tor_guard: vec![], oem_netid: None };
        let p = plan(&spec).unwrap();
        assert!(apply(&mut k, &p).is_err());
        assert_eq!(rule_msgs(&k), 0);
        spec.iface = "exit0".into();
        assert!(apply(&mut k, &plan(&spec).unwrap()).is_ok());
    }

    #[test]
    fn a_reply_that_is_not_netlink_is_an_error_not_a_panic() {
        let mut k = FakeKernel::default();
        k.garbage = true;
        let e = apply(&mut k, &plan_of(vec![r(10000, 10230)], vec![], None)).unwrap_err();
        assert_eq!(e.kind(), io::ErrorKind::InvalidData);
        assert_eq!(clear(&mut k).unwrap_err().kind(), io::ErrorKind::InvalidData);
    }

    #[test]
    fn the_errno_values_are_those_of_the_kernel() {
        assert_eq!((EEXIST, ENOENT, ESRCH), (17, 2, 3));
    }

    #[test]
    fn a_write_that_is_not_acknowledged_is_an_error_and_never_a_success() {
        let rule = Rule {
            priority: 13_500,
            family: Family::V4,
            uids: Some(UidRange { start: 10_000, end: 10_000 }),
            proto: None,
            dport: None,
            dst: None,
            fwmark: None,
            action: crate::routes::spec::Action::Lookup(4747),
        };
        let msg = nl::rule_msg(Verb::Add, &rule, 1);
        let mut silent = FakeKernel { silent: true, ..FakeKernel::default() };
        let err = ack(&mut silent, &msg, &[]).unwrap_err();
        assert_eq!(err.kind(), io::ErrorKind::InvalidData, "a reply without an acknowledgement");
        // The same message, acknowledged, is a success; a tolerated errno is one too, any other is that errno.
        let mut kernel = FakeKernel::default();
        assert!(ack(&mut kernel, &msg, &[]).is_ok());
        assert_eq!(ack(&mut kernel, &msg, &[]).unwrap_err().raw_os_error(), Some(EEXIST));
        assert!(ack(&mut kernel, &msg, &[EEXIST]).is_ok());
    }

    #[test]
    fn a_dump_that_does_not_end_is_an_error() {
        struct Endless;
        impl Netlink for Endless {
            fn request(&mut self, _msg: &[u8]) -> io::Result<Vec<u8>> {
                Ok(Vec::new())
            }
            fn ifindex(&mut self, _: &str) -> io::Result<u32> {
                Ok(1)
            }
        }
        assert_eq!(read_band(&mut Endless).unwrap_err().kind(), io::ErrorKind::InvalidData);
    }

    #[test]
    fn a_rule_outside_the_band_in_a_plan_is_refused_before_anything_is_written() {
        let mut k = FakeKernel::default();
        let bad = vec![RuleOp::RuleAdd(outside(100))];
        assert_eq!(apply(&mut k, &bad).unwrap_err().kind(), io::ErrorKind::InvalidInput);
        assert_eq!(rule_msgs(&k), 0);
    }

    #[test]
    fn clear_removes_the_band_and_the_routes_and_is_repeatable() {
        let mut k = FakeKernel::default();
        apply(&mut k, &plan_of(vec![r(10000, 10230)], vec![r(10231, 10231)], Some(9))).unwrap();
        k.rules.push(outside(16000));
        let out = clear(&mut k).unwrap();
        assert_eq!(out.removed, 6 + 8);
        assert_eq!(k.rules, vec![outside(16000)]);
        assert!(k.routes.is_empty());
        let again = clear(&mut k).unwrap();
        assert_eq!(again, Applied::default());
    }

    #[test]
    fn clear_tolerates_a_rule_that_is_already_gone() {
        let mut k = FakeKernel::default();
        apply(&mut k, &plan_of(vec![r(10000, 10230)], vec![], None)).unwrap();
        // Somebody removed each rule between the read and the delete: ENOENT for every deletion.
        k.fail_with = Some((nl::RTM_DELRULE, ENOENT));
        let out = clear(&mut k).unwrap();
        assert_eq!(out.removed, 6);
    }

    #[test]
    fn the_band_is_read_for_both_families() {
        let mut k = FakeKernel::default();
        apply(&mut k, &plan_of(vec![r(10000, 10230)], vec![], None)).unwrap();
        let band = read_band(&mut k).unwrap();
        assert_eq!(band.known.len(), 6);
        assert_eq!(band.known.iter().filter(|x| x.family == Family::V4).count(), 3);
        assert_eq!(band.known.iter().filter(|x| x.family == Family::V6).count(), 3);
        assert!(band.foreign.is_empty());
    }
}
