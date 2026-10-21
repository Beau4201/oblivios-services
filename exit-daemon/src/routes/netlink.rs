// SPDX-License-Identifier: Apache-2.0
//! The `NETLINK_ROUTE` messages of the route helper: the encoders for the few messages it may send and the bounds-checked
//! decoders for what the kernel answers. Safe Rust only; `sys` owns the socket. Every constant is the kernel's
//! (`linux/rtnetlink.h`, `linux/fib_rules.h`, `linux/if_addr.h`, `linux/if_link.h`).

use super::spec::{Action, Dst, Family, Rule, UidRange};
use std::net::{Ipv4Addr, Ipv6Addr};

pub const NLMSG_HDR: usize = 16;
pub const NLM_F_REQUEST: u16 = 0x1;
pub const NLM_F_ACK: u16 = 0x4;
pub const NLM_F_REPLACE: u16 = 0x100;
pub const NLM_F_EXCL: u16 = 0x200;
pub const NLM_F_CREATE: u16 = 0x400;
/// `NLM_F_ROOT | NLM_F_MATCH`: a dump of everything that matches the header.
pub const NLM_F_DUMP: u16 = 0x300;

pub const NLMSG_ERROR: u16 = 2;
pub const NLMSG_DONE: u16 = 3;
pub const RTM_NEWLINK: u16 = 16;
pub const RTM_SETLINK: u16 = 19;
pub const RTM_NEWADDR: u16 = 20;
pub const RTM_NEWROUTE: u16 = 24;
pub const RTM_DELROUTE: u16 = 25;
pub const RTM_NEWRULE: u16 = 32;
pub const RTM_DELRULE: u16 = 33;
pub const RTM_GETRULE: u16 = 34;

const AF_INET: u8 = 2;
const AF_INET6: u8 = 10;

// fib_rules.h
const FRA_DST: u16 = 1;
const FRA_SUPPRESS_IFGROUP: u16 = 13;
const FRA_SUPPRESS_PREFIXLEN: u16 = 14;
const FRA_PAD: u16 = 18;
const FRA_PROTOCOL: u16 = 21;
const FRA_GOTO: u16 = 4;
const FRA_PRIORITY: u16 = 6;
const FRA_FWMARK: u16 = 10;
const FRA_TABLE: u16 = 15;
const FRA_FWMASK: u16 = 16;
const FRA_UID_RANGE: u16 = 20;
const FRA_IP_PROTO: u16 = 22;
const FRA_DPORT_RANGE: u16 = 24;
const FR_ACT_TO_TBL: u8 = 1;
const FR_ACT_GOTO: u8 = 2;
const FR_ACT_NOP: u8 = 3;
const FR_ACT_PROHIBIT: u8 = 8;
const FIB_RULE_HDR: usize = 12;

// rtnetlink.h
const RTA_OIF: u16 = 4;
const RTA_TABLE: u16 = 15;
const RTN_UNICAST: u8 = 1;
const RTPROT_STATIC: u8 = 4;
const RT_SCOPE_UNIVERSE: u8 = 0;
const RT_SCOPE_LINK: u8 = 253;
const RTMSG_HDR: usize = 12;

// if_addr.h, if_link.h
const IFA_ADDRESS: u16 = 1;
const IFA_LOCAL: u16 = 2;
const IFLA_IFNAME: u16 = 3;
const IFF_UP: u32 = 1;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum NlError {
    /// The buffer ends inside a header or an attribute.
    Truncated,
    /// A length field is smaller than its own header or runs past the buffer.
    BadLength,
    /// A fixed-size attribute has another size.
    BadAttribute,
}

fn align4(n: usize) -> usize {
    (n + 3) & !3
}

/// Builds one message: header, a fixed part, then attributes. The length field is filled in by [`Builder::finish`].
struct Builder {
    buf: Vec<u8>,
}

impl Builder {
    fn new(kind: u16, flags: u16, seq: u32) -> Self {
        let mut buf = Vec::with_capacity(128);
        buf.extend_from_slice(&0u32.to_ne_bytes());
        buf.extend_from_slice(&kind.to_ne_bytes());
        buf.extend_from_slice(&flags.to_ne_bytes());
        buf.extend_from_slice(&seq.to_ne_bytes());
        buf.extend_from_slice(&0u32.to_ne_bytes()); // port id: the kernel fills in who sent it
        Self { buf }
    }

    fn raw(&mut self, bytes: &[u8]) {
        self.buf.extend_from_slice(bytes);
    }

    fn attr(&mut self, kind: u16, payload: &[u8]) {
        let len = (4 + payload.len()) as u16;
        self.buf.extend_from_slice(&len.to_ne_bytes());
        self.buf.extend_from_slice(&kind.to_ne_bytes());
        self.buf.extend_from_slice(payload);
        self.buf.resize(align4(self.buf.len()), 0);
    }

    fn attr_u32(&mut self, kind: u16, v: u32) {
        self.attr(kind, &v.to_ne_bytes());
    }

    fn finish(mut self) -> Vec<u8> {
        let len = self.buf.len() as u32;
        self.buf[..4].copy_from_slice(&len.to_ne_bytes());
        self.buf
    }
}

fn family_byte(f: Family) -> u8 {
    match f {
        Family::V4 => AF_INET,
        Family::V6 => AF_INET6,
    }
}

/// Adds or removes one rule. An addition fails with `EEXIST` when the identical rule is there; a removal with `ENOENT`
/// when it is not.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Verb {
    Add,
    Del,
}

pub fn rule_msg(verb: Verb, rule: &Rule, seq: u32) -> Vec<u8> {
    let (kind, flags) = match verb {
        Verb::Add => (RTM_NEWRULE, NLM_F_REQUEST | NLM_F_ACK | NLM_F_CREATE | NLM_F_EXCL),
        Verb::Del => (RTM_DELRULE, NLM_F_REQUEST | NLM_F_ACK),
    };
    let mut m = Builder::new(kind, flags, seq);
    let (action, table, goto) = match rule.action {
        Action::Lookup(t) => (FR_ACT_TO_TBL, Some(t), None),
        Action::Goto(p) => (FR_ACT_GOTO, None, Some(p)),
        Action::Prohibit => (FR_ACT_PROHIBIT, None, None),
        Action::Nop => (FR_ACT_NOP, None, None),
    };
    let dst_len = match rule.dst {
        Some(Dst::V4(_, p)) | Some(Dst::V6(_, p)) => p,
        None => 0,
    };
    // struct fib_rule_hdr: family, dst_len, src_len, tos, table, res1, res2, action, flags
    m.raw(&[family_byte(rule.family), dst_len, 0, 0, 0, 0, 0, action]);
    m.raw(&0u32.to_ne_bytes());
    match rule.dst {
        Some(Dst::V4(a, _)) => m.attr(FRA_DST, &a.octets()),
        Some(Dst::V6(a, _)) => m.attr(FRA_DST, &a.octets()),
        None => {}
    }
    m.attr_u32(FRA_PRIORITY, rule.priority);
    if let Some((value, mask)) = rule.fwmark {
        m.attr_u32(FRA_FWMARK, value);
        m.attr_u32(FRA_FWMASK, mask);
    }
    if let Some(p) = goto {
        m.attr_u32(FRA_GOTO, p);
    }
    if let Some(t) = table {
        m.attr_u32(FRA_TABLE, t);
    }
    if let Some(u) = rule.uids {
        let mut b = [0u8; 8];
        b[..4].copy_from_slice(&u.start.to_ne_bytes());
        b[4..].copy_from_slice(&u.end.to_ne_bytes());
        m.attr(FRA_UID_RANGE, &b);
    }
    if let Some(p) = rule.proto {
        m.attr(FRA_IP_PROTO, &[p]);
    }
    if let Some(port) = rule.dport {
        let mut b = [0u8; 4];
        b[..2].copy_from_slice(&port.to_ne_bytes());
        b[2..].copy_from_slice(&port.to_ne_bytes());
        m.attr(FRA_DPORT_RANGE, &b);
    }
    m.finish()
}

/// Removes the first rule of one family that has this priority, whatever else it selects: for a rule in the band that the
/// helper cannot describe (it was not written by this helper).
pub fn rule_del_priority_msg(family: Family, priority: u32, seq: u32) -> Vec<u8> {
    let mut m = Builder::new(RTM_DELRULE, NLM_F_REQUEST | NLM_F_ACK, seq);
    m.raw(&[family_byte(family), 0, 0, 0, 0, 0, 0, 0]);
    m.raw(&0u32.to_ne_bytes());
    m.attr_u32(FRA_PRIORITY, priority);
    m.finish()
}

/// A dump of all rules of one family.
pub fn dump_rules_msg(family: Family, seq: u32) -> Vec<u8> {
    let mut m = Builder::new(RTM_GETRULE, NLM_F_REQUEST | NLM_F_DUMP, seq);
    m.raw(&[family_byte(family), 0, 0, 0, 0, 0, 0, 0]);
    m.raw(&0u32.to_ne_bytes());
    m.finish()
}

/// `default dev <ifindex> table <table>`: replaces an identical route.
pub fn route_default_msg(ifindex: u32, family: Family, table: u32, seq: u32) -> Vec<u8> {
    let mut m = Builder::new(RTM_NEWROUTE, NLM_F_REQUEST | NLM_F_ACK | NLM_F_CREATE | NLM_F_REPLACE, seq);
    let scope = match family {
        Family::V4 => RT_SCOPE_LINK,
        Family::V6 => RT_SCOPE_UNIVERSE,
    };
    // struct rtmsg: family, dst_len, src_len, tos, table, protocol, scope, type, flags
    m.raw(&[family_byte(family), 0, 0, 0, 0, RTPROT_STATIC, scope, RTN_UNICAST]);
    m.raw(&0u32.to_ne_bytes());
    m.attr_u32(RTA_TABLE, table);
    m.attr_u32(RTA_OIF, ifindex);
    m.finish()
}

/// Removes the default route of a table; the table and the family identify it, the device does not have to exist.
pub fn route_default_del_msg(family: Family, table: u32, seq: u32) -> Vec<u8> {
    let mut m = Builder::new(RTM_DELROUTE, NLM_F_REQUEST | NLM_F_ACK, seq);
    let scope = match family {
        Family::V4 => RT_SCOPE_LINK,
        Family::V6 => RT_SCOPE_UNIVERSE,
    };
    m.raw(&[family_byte(family), 0, 0, 0, 0, RTPROT_STATIC, scope, RTN_UNICAST]);
    m.raw(&0u32.to_ne_bytes());
    m.attr_u32(RTA_TABLE, table);
    m.finish()
}

fn addr_msg(ifindex: u32, family: Family, prefix: u8, octets: &[u8], seq: u32) -> Vec<u8> {
    let mut m = Builder::new(RTM_NEWADDR, NLM_F_REQUEST | NLM_F_ACK | NLM_F_CREATE | NLM_F_EXCL, seq);
    // struct ifaddrmsg: family, prefixlen, flags, scope, index
    m.raw(&[family_byte(family), prefix, 0, RT_SCOPE_UNIVERSE]);
    m.raw(&ifindex.to_ne_bytes());
    m.attr(IFA_LOCAL, octets);
    m.attr(IFA_ADDRESS, octets);
    m.finish()
}

pub fn addr_v4_msg(ifindex: u32, addr: Ipv4Addr, prefix: u8, seq: u32) -> Vec<u8> {
    addr_msg(ifindex, Family::V4, prefix, &addr.octets(), seq)
}

pub fn addr_v6_msg(ifindex: u32, addr: Ipv6Addr, prefix: u8, seq: u32) -> Vec<u8> {
    addr_msg(ifindex, Family::V6, prefix, &addr.octets(), seq)
}

/// Brings the interface up. The kernel finds the interface by name when the index is 0.
pub fn link_up_msg(name: &str, seq: u32) -> Vec<u8> {
    let mut m = Builder::new(RTM_SETLINK, NLM_F_REQUEST | NLM_F_ACK, seq);
    // struct ifinfomsg: family, pad, type, index, flags, change
    m.raw(&[0, 0, 0, 0]);
    m.raw(&0i32.to_ne_bytes());
    m.raw(&IFF_UP.to_ne_bytes());
    m.raw(&IFF_UP.to_ne_bytes());
    let mut n = name.as_bytes().to_vec();
    n.push(0);
    m.attr(IFLA_IFNAME, &n);
    m.finish()
}

/// One message of a reply buffer.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Msg<'a> {
    pub kind: u16,
    pub seq: u32,
    pub payload: &'a [u8],
}

fn ne_u16(b: &[u8]) -> Result<u16, NlError> {
    <[u8; 2]>::try_from(b).map(u16::from_ne_bytes).map_err(|_| NlError::Truncated)
}

fn ne_u32(b: &[u8]) -> Result<u32, NlError> {
    <[u8; 4]>::try_from(b).map(u32::from_ne_bytes).map_err(|_| NlError::Truncated)
}

/// Splits a buffer of netlink messages. A length below the header size or past the end of the buffer is an error; the
/// padding after the last message may be missing.
pub fn messages(buf: &[u8]) -> Result<Vec<Msg<'_>>, NlError> {
    let mut out = Vec::new();
    let mut at = 0usize;
    while at < buf.len() {
        let head = buf.get(at..at + NLMSG_HDR).ok_or(NlError::Truncated)?;
        let len = ne_u32(&head[0..4])? as usize;
        let end = at.checked_add(len).ok_or(NlError::BadLength)?;
        // A length below the header's own size makes `at + NLMSG_HDR..end` an empty-or-reversed range, which `get` refuses.
        let payload = buf.get(at + NLMSG_HDR..end).ok_or(NlError::BadLength)?;
        out.push(Msg { kind: ne_u16(&head[4..6])?, seq: ne_u32(&head[8..12])?, payload });
        at = at.checked_add(align4(len)).ok_or(NlError::BadLength)?;
    }
    Ok(out)
}

/// The result of an `NLMSG_ERROR` message: 0 for an acknowledgement, otherwise the (positive) errno.
pub fn error_code(m: &Msg<'_>) -> Result<i32, NlError> {
    if m.kind != NLMSG_ERROR {
        return Err(NlError::BadAttribute);
    }
    let e = i32::from_ne_bytes(<[u8; 4]>::try_from(m.payload.get(..4).ok_or(NlError::Truncated)?).map_err(|_| NlError::Truncated)?);
    Ok(e.checked_neg().unwrap_or(i32::MAX))
}

/// Walks the attributes after a fixed part: `(type, payload)` pairs. A length below 4 or past the end is an error.
fn attrs(mut b: &[u8]) -> Result<Vec<(u16, &[u8])>, NlError> {
    let mut out = Vec::new();
    while !b.is_empty() {
        let head = b.get(..4).ok_or(NlError::Truncated)?;
        let len = ne_u16(&head[0..2])? as usize;
        // The nested and byte-order flags live in the top bits of the type; an attribute that has one is not one of ours.
        let kind = ne_u16(&head[2..4])?;
        // A length below 4 makes `4..len` a reversed range, which `get` refuses.
        let payload = b.get(4..len).ok_or(NlError::BadLength)?;
        out.push((kind, payload));
        b = b.get(align4(len)..).unwrap_or(&[]);
    }
    Ok(out)
}

fn fixed<const N: usize>(p: &[u8]) -> Result<[u8; N], NlError> {
    <[u8; N]>::try_from(p).map_err(|_| NlError::BadAttribute)
}

/// Decodes the payload of an `RTM_NEWRULE` message (a dump entry) into a rule. `Ok(None)` for a rule of a family or an
/// action this helper never writes: such a rule is not ours, and is left alone unless it sits in the band.
pub fn parse_rule(payload: &[u8]) -> Result<Option<Rule>, NlError> {
    let hdr = payload.get(..FIB_RULE_HDR).ok_or(NlError::Truncated)?;
    let family = match hdr[0] {
        AF_INET => Family::V4,
        AF_INET6 => Family::V6,
        _ => return Ok(None),
    };
    let (dst_len, table8, action8) = (hdr[1], hdr[4], hdr[7]);
    // A source prefix, a type of service or a flag (`not` is one) is a selector this helper never writes: a rule that has
    // one is not ours, whatever else it matches.
    let flags = ne_u32(&hdr[8..12])?;
    if hdr[2] != 0 || hdr[3] != 0 || flags != 0 {
        return Ok(None);
    }
    let mut priority = None;
    let (mut table, mut goto, mut fwmark, mut fwmask) = (u32::from(table8), None, None, None);
    let (mut uids, mut proto, mut dport, mut dst_bytes) = (None, None, None, None);
    for (kind, p) in attrs(payload.get(FIB_RULE_HDR..).ok_or(NlError::Truncated)?)? {
        match kind {
            FRA_PRIORITY => priority = Some(u32::from_ne_bytes(fixed::<4>(p)?)),
            FRA_TABLE => table = u32::from_ne_bytes(fixed::<4>(p)?),
            FRA_GOTO => goto = Some(u32::from_ne_bytes(fixed::<4>(p)?)),
            FRA_FWMARK => fwmark = Some(u32::from_ne_bytes(fixed::<4>(p)?)),
            FRA_FWMASK => fwmask = Some(u32::from_ne_bytes(fixed::<4>(p)?)),
            FRA_UID_RANGE => {
                let b = fixed::<8>(p)?;
                uids = Some(UidRange {
                    start: u32::from_ne_bytes([b[0], b[1], b[2], b[3]]),
                    end: u32::from_ne_bytes([b[4], b[5], b[6], b[7]]),
                });
            }
            FRA_IP_PROTO => proto = Some(fixed::<1>(p)?[0]),
            FRA_DPORT_RANGE => {
                let b = fixed::<4>(p)?;
                let (lo, hi) = (u16::from_ne_bytes([b[0], b[1]]), u16::from_ne_bytes([b[2], b[3]]));
                // A single port is the only range this helper writes.
                if lo != hi {
                    return Ok(None);
                }
                dport = Some(lo);
            }
            FRA_DST => dst_bytes = Some(p),
            // The kernel adds these to every rule it dumps; with the value 0 they select nothing.
            FRA_PAD => {}
            FRA_PROTOCOL if p == [0] => {}
            // "No suppression" is -1; any other value hides routes from the lookup, which this helper never asks for.
            FRA_SUPPRESS_PREFIXLEN | FRA_SUPPRESS_IFGROUP if p == u32::MAX.to_ne_bytes() => {}
            // Any other attribute (a source, an interface, a flow, a port range of another kind) is a selector this
            // helper never writes.
            _ => return Ok(None),
        }
    }
    let Some(priority) = priority else { return Ok(None) };
    let action = match action8 {
        FR_ACT_TO_TBL => Action::Lookup(table),
        FR_ACT_GOTO => match goto {
            Some(p) => Action::Goto(p),
            None => return Ok(None),
        },
        FR_ACT_NOP => Action::Nop,
        FR_ACT_PROHIBIT => Action::Prohibit,
        _ => return Ok(None),
    };
    let dst = match (family, dst_bytes) {
        (_, None) => None,
        (Family::V4, Some(b)) => Some(Dst::V4(Ipv4Addr::from(fixed::<4>(b)?), dst_len)),
        (Family::V6, Some(b)) => Some(Dst::V6(Ipv6Addr::from(fixed::<16>(b)?), dst_len)),
    };
    let mark = match (fwmark, fwmask) {
        (None, None) => None,
        (v, m) => Some((v.unwrap_or(0), m.unwrap_or(0))),
    };
    Ok(Some(Rule { priority, family, uids, proto, dport, dst, fwmark: mark, action }))
}

/// The priority of a rule dump entry, even when [`parse_rule`] cannot describe the rule.
pub fn rule_priority(payload: &[u8]) -> Result<Option<(Family, u32)>, NlError> {
    let hdr = payload.get(..FIB_RULE_HDR).ok_or(NlError::Truncated)?;
    let family = match hdr[0] {
        AF_INET => Family::V4,
        AF_INET6 => Family::V6,
        _ => return Ok(None),
    };
    for (kind, p) in attrs(payload.get(FIB_RULE_HDR..).ok_or(NlError::Truncated)?)? {
        if kind == FRA_PRIORITY {
            return Ok(Some((family, u32::from_ne_bytes(fixed::<4>(p)?))));
        }
    }
    Ok(None)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::routes::spec::{plan, RouteSpec, RuleOp, TABLE};

    fn r(start: u32, end: u32) -> UidRange {
        UidRange { start, end }
    }

    fn rules_of(spec: &RouteSpec) -> Vec<Rule> {
        plan(spec).unwrap().into_iter().filter_map(|o| if let RuleOp::RuleAdd(x) = o { Some(x) } else { None }).collect()
    }

    fn sample() -> Vec<Rule> {
        rules_of(&RouteSpec {
            iface: "exit0".into(),
            steer: vec![r(10000, 10230), r(10232, 19999)],
            tor_guard: vec![r(10231, 10231)],
            oem_netid: Some(101),
        })
    }

    /// The payload of a message built by this module, without the netlink header, as the kernel would dump it back.
    fn payload(msg: &[u8]) -> &[u8] {
        &msg[NLMSG_HDR..]
    }

    #[test]
    fn the_golden_bytes_of_a_dns_rule() {
        // ip -4 rule add priority 13500 uidrange 10000-10230 ipproto udp dport 53 lookup 4747   (little-endian host)
        let rule = Rule {
            priority: 13500,
            family: Family::V4,
            uids: Some(r(10000, 10230)),
            proto: Some(17),
            dport: Some(53),
            dst: None,
            fwmark: None,
            action: Action::Lookup(TABLE),
        };
        let m = rule_msg(Verb::Add, &rule, 7);
        let mut want: Vec<u8> = Vec::new();
        want.extend_from_slice(&72u32.to_ne_bytes()); // length: 16 + 12 + 8 + 8 + 12 + 8 + 8
        want.extend_from_slice(&32u16.to_ne_bytes()); // RTM_NEWRULE
        want.extend_from_slice(&0x0605u16.to_ne_bytes()); // REQUEST | ACK | EXCL | CREATE
        want.extend_from_slice(&7u32.to_ne_bytes());
        want.extend_from_slice(&0u32.to_ne_bytes());
        want.extend_from_slice(&[2, 0, 0, 0, 0, 0, 0, 1]); // AF_INET, ..., FR_ACT_TO_TBL
        want.extend_from_slice(&0u32.to_ne_bytes());
        want.extend_from_slice(&[8, 0, 6, 0]); // FRA_PRIORITY
        want.extend_from_slice(&13500u32.to_ne_bytes());
        want.extend_from_slice(&[8, 0, 15, 0]); // FRA_TABLE
        want.extend_from_slice(&4747u32.to_ne_bytes());
        want.extend_from_slice(&[12, 0, 20, 0]); // FRA_UID_RANGE
        want.extend_from_slice(&10000u32.to_ne_bytes());
        want.extend_from_slice(&10230u32.to_ne_bytes());
        want.extend_from_slice(&[5, 0, 22, 0, 17, 0, 0, 0]); // FRA_IP_PROTO, padded
        want.extend_from_slice(&[8, 0, 24, 0]); // FRA_DPORT_RANGE
        want.extend_from_slice(&53u16.to_ne_bytes());
        want.extend_from_slice(&53u16.to_ne_bytes());
        assert_eq!(m, want);
    }

    #[test]
    fn every_rule_of_a_specification_survives_encode_and_decode() {
        for rule in sample() {
            for verb in [Verb::Add, Verb::Del] {
                let m = rule_msg(verb, &rule, 1);
                let msgs = messages(&m).unwrap();
                assert_eq!(msgs.len(), 1);
                assert_eq!(parse_rule(msgs[0].payload).unwrap(), Some(rule), "{rule:?}");
                assert_eq!(parse_rule(payload(&m)).unwrap(), Some(rule));
                assert_eq!(msgs[0].kind, if verb == Verb::Add { RTM_NEWRULE } else { RTM_DELRULE });
            }
        }
    }

    #[test]
    fn the_length_field_is_the_real_length_and_a_message_is_four_byte_aligned() {
        let mut all = sample();
        all.push(Rule {
            priority: 13523,
            family: Family::V6,
            uids: None,
            proto: None,
            dport: None,
            dst: None,
            fwmark: None,
            action: Action::Nop,
        });
        for rule in all {
            let m = rule_msg(Verb::Add, &rule, 1);
            assert_eq!(u32::from_ne_bytes([m[0], m[1], m[2], m[3]]) as usize, m.len());
            assert_eq!(m.len() % 4, 0);
        }
    }

    #[test]
    fn the_flags_of_each_kind_of_message() {
        let rule = sample()[0];
        let flags = |m: &[u8]| u16::from_ne_bytes([m[6], m[7]]);
        assert_eq!(flags(&rule_msg(Verb::Add, &rule, 1)), NLM_F_REQUEST | NLM_F_ACK | NLM_F_CREATE | NLM_F_EXCL);
        assert_eq!(flags(&rule_msg(Verb::Del, &rule, 1)), NLM_F_REQUEST | NLM_F_ACK);
        assert_eq!(flags(&dump_rules_msg(Family::V4, 1)), NLM_F_REQUEST | NLM_F_DUMP);
        assert_eq!(flags(&route_default_msg(3, Family::V4, 4747, 1)), NLM_F_REQUEST | NLM_F_ACK | NLM_F_CREATE | NLM_F_REPLACE);
        assert_eq!(flags(&addr_v4_msg(3, Ipv4Addr::new(169, 254, 42, 127), 24, 1)), NLM_F_REQUEST | NLM_F_ACK | NLM_F_CREATE | NLM_F_EXCL);
        assert_eq!(flags(&link_up_msg("exit0", 1)), NLM_F_REQUEST | NLM_F_ACK);
    }

    #[test]
    fn the_message_kinds_are_the_kernels() {
        let kind = |m: &[u8]| u16::from_ne_bytes([m[4], m[5]]);
        assert_eq!(kind(&rule_msg(Verb::Add, &sample()[0], 1)), 32);
        assert_eq!(kind(&rule_msg(Verb::Del, &sample()[0], 1)), 33);
        assert_eq!(kind(&dump_rules_msg(Family::V6, 1)), 34);
        assert_eq!(kind(&route_default_msg(3, Family::V6, 4747, 1)), 24);
        assert_eq!(kind(&route_default_del_msg(Family::V6, 4747, 1)), 25);
        assert_eq!(kind(&addr_v6_msg(3, Ipv6Addr::LOCALHOST, 64, 1)), 20);
        assert_eq!(kind(&link_up_msg("exit0", 1)), 19);
        assert_eq!(kind(&rule_del_priority_msg(Family::V4, 13500, 1)), 33);
    }

    #[test]
    fn the_families_are_in_the_first_header_byte() {
        let fam = |m: &[u8]| m[NLMSG_HDR];
        assert_eq!(fam(&dump_rules_msg(Family::V4, 1)), 2);
        assert_eq!(fam(&dump_rules_msg(Family::V6, 1)), 10);
        assert_eq!(fam(&route_default_msg(3, Family::V4, 4747, 1)), 2);
        assert_eq!(fam(&route_default_msg(3, Family::V6, 4747, 1)), 10);
        assert_eq!(fam(&addr_v4_msg(3, Ipv4Addr::new(1, 2, 3, 4), 24, 1)), 2);
        assert_eq!(fam(&addr_v6_msg(3, Ipv6Addr::LOCALHOST, 64, 1)), 10);
    }

    #[test]
    fn the_route_message_carries_table_interface_and_scope() {
        let v4 = route_default_msg(9, Family::V4, 4747, 1);
        let p = payload(&v4);
        assert_eq!(&p[..8], &[2, 0, 0, 0, 0, 4, 253, 1], "family, no prefix, protocol static, scope link, unicast");
        let a = attrs(&p[RTMSG_HDR..]).unwrap();
        assert!(a.contains(&(RTA_TABLE, &4747u32.to_ne_bytes()[..])));
        assert!(a.contains(&(RTA_OIF, &9u32.to_ne_bytes()[..])));
        let v6 = route_default_msg(9, Family::V6, 4747, 1);
        assert_eq!(payload(&v6)[6], 0, "an IPv6 route to a device has universe scope");
    }

    #[test]
    fn the_address_message_carries_the_address_twice() {
        let m = addr_v4_msg(5, Ipv4Addr::new(169, 254, 42, 127), 24, 1);
        let p = payload(&m);
        assert_eq!(&p[..4], &[2, 24, 0, 0]);
        assert_eq!(u32::from_ne_bytes([p[4], p[5], p[6], p[7]]), 5);
        let a = attrs(&p[8..]).unwrap();
        assert_eq!(a, vec![(IFA_LOCAL, &[169u8, 254, 42, 127][..]), (IFA_ADDRESS, &[169u8, 254, 42, 127][..])]);
    }

    #[test]
    fn link_up_sets_the_flag_and_the_name() {
        let m = link_up_msg("exit0", 1);
        let p = payload(&m);
        assert_eq!(u32::from_ne_bytes([p[8], p[9], p[10], p[11]]), 1, "flags: IFF_UP");
        assert_eq!(u32::from_ne_bytes([p[12], p[13], p[14], p[15]]), 1, "change mask: IFF_UP");
        assert_eq!(attrs(&p[16..]).unwrap(), vec![(IFLA_IFNAME, &b"exit0\0"[..])]);
    }

    #[test]
    fn an_error_message_gives_the_errno_or_zero() {
        let mut ack = Builder::new(NLMSG_ERROR, 0, 5);
        ack.raw(&0i32.to_ne_bytes());
        let ack = ack.finish();
        let enoent = {
            let mut b = Builder::new(NLMSG_ERROR, 0, 5);
            b.raw(&(-2i32).to_ne_bytes());
            b.finish()
        };
        assert_eq!(error_code(&messages(&ack).unwrap()[0]), Ok(0));
        assert_eq!(error_code(&messages(&enoent).unwrap()[0]), Ok(2));
        let done = Builder::new(NLMSG_DONE, 0, 5).finish();
        assert_eq!(error_code(&messages(&done).unwrap()[0]), Err(NlError::BadAttribute));
        let short = Builder::new(NLMSG_ERROR, 0, 5).finish();
        assert_eq!(error_code(&messages(&short).unwrap()[0]), Err(NlError::Truncated));
    }

    #[test]
    fn two_messages_in_one_buffer_are_split_and_a_missing_last_padding_is_fine() {
        let mut buf = rule_msg(Verb::Add, &sample()[0], 1);
        buf.extend(rule_msg(Verb::Del, &sample()[1], 2));
        let msgs = messages(&buf).unwrap();
        assert_eq!((msgs.len(), msgs[0].seq, msgs[1].seq), (2, 1, 2));
        // A header-only message of length 17 has 3 bytes of padding that the sender may leave out.
        let mut odd = Builder::new(99, 0, 1);
        odd.raw(&[1]);
        let mut odd = odd.finish();
        assert_eq!(odd.len(), 17);
        assert_eq!(messages(&odd).unwrap().len(), 1);
        odd.extend_from_slice(&[0, 0, 0]);
        assert_eq!(messages(&odd).unwrap().len(), 1);
    }

    #[test]
    fn no_truncation_of_a_reply_makes_the_parsers_panic() {
        let mut dump = Vec::new();
        for rule in sample() {
            dump.extend(rule_msg(Verb::Add, &rule, 1));
        }
        for cut in 0..dump.len() {
            let part = &dump[..cut];
            if let Ok(msgs) = messages(part) {
                for m in msgs {
                    let _ = parse_rule(m.payload);
                    let _ = rule_priority(m.payload);
                    let _ = error_code(&m);
                }
            }
        }
        // And every single-byte change of one message.
        let one = rule_msg(Verb::Add, &sample()[0], 1);
        for i in 0..one.len() {
            for v in [0u8, 1, 0x7f, 0xff] {
                let mut x = one.clone();
                x[i] = v;
                if let Ok(msgs) = messages(&x) {
                    for m in msgs {
                        let _ = parse_rule(m.payload);
                        let _ = rule_priority(m.payload);
                    }
                }
            }
        }
    }

    #[test]
    fn bad_lengths_are_refused() {
        assert_eq!(messages(&[0; 8]), Err(NlError::Truncated));
        let mut m = rule_msg(Verb::Add, &sample()[0], 1);
        m[..4].copy_from_slice(&8u32.to_ne_bytes()); // shorter than the header
        assert_eq!(messages(&m), Err(NlError::BadLength));
        let mut m = rule_msg(Verb::Add, &sample()[0], 1);
        let too_long = (m.len() as u32) + 4;
        m[..4].copy_from_slice(&too_long.to_ne_bytes());
        assert_eq!(messages(&m), Err(NlError::BadLength));
        let mut m = rule_msg(Verb::Add, &sample()[0], 1);
        m[..4].copy_from_slice(&u32::MAX.to_ne_bytes());
        assert_eq!(messages(&m), Err(NlError::BadLength));
    }

    #[test]
    fn an_attribute_with_a_bad_size_or_length_is_refused() {
        let good = rule_msg(Verb::Add, &sample()[0], 1);
        let p = payload(&good).to_vec();
        // Shrink the priority attribute's size field below its header.
        let mut bad = p.clone();
        bad[FIB_RULE_HDR..FIB_RULE_HDR + 2].copy_from_slice(&3u16.to_ne_bytes());
        assert_eq!(parse_rule(&bad), Err(NlError::BadLength));
        // A priority attribute that carries 2 bytes instead of 4.
        let mut bad = p.clone();
        bad[FIB_RULE_HDR..FIB_RULE_HDR + 2].copy_from_slice(&6u16.to_ne_bytes());
        assert!(parse_rule(&bad).is_err());
        // An attribute that claims more than the buffer holds.
        let mut bad = p.clone();
        bad[FIB_RULE_HDR..FIB_RULE_HDR + 2].copy_from_slice(&200u16.to_ne_bytes());
        assert_eq!(parse_rule(&bad), Err(NlError::BadLength));
        assert_eq!(parse_rule(&[0; 5]), Err(NlError::Truncated));
    }

    #[test]
    fn a_rule_this_helper_would_never_write_is_not_described() {
        // A DNS rule: it has a port range, which is the last attribute of its message.
        let base = sample().into_iter().find(|x| x.dport.is_some()).unwrap();
        let mut m = rule_msg(Verb::Add, &base, 1);
        m[NLMSG_HDR + 7] = 7; // FR_ACT_UNREACHABLE
        assert_eq!(parse_rule(payload(&m)).unwrap(), None);
        let mut m = rule_msg(Verb::Add, &base, 1);
        m[NLMSG_HDR] = 1; // AF_UNIX
        assert_eq!(parse_rule(payload(&m)).unwrap(), None);
        // A port range that is not a single port.
        let mut m = rule_msg(Verb::Add, &base, 1);
        let at = m.len() - 2;
        m[at..].copy_from_slice(&60u16.to_ne_bytes());
        assert_eq!(parse_rule(payload(&m)).unwrap(), None);
        // The priority is still readable for deleting it.
        assert_eq!(rule_priority(payload(&m)).unwrap(), Some((Family::V4, base.priority)));
        // A goto action with no target attribute.
        let mut g = rule_msg(Verb::Add, &Rule { action: Action::Prohibit, ..base }, 1);
        g[NLMSG_HDR + 7] = 2; // FR_ACT_GOTO
        assert_eq!(parse_rule(payload(&g)).unwrap(), None);
        // The same message with its real action is described.
        let ok = rule_msg(Verb::Add, &Rule { action: Action::Prohibit, ..base }, 1);
        assert_eq!(parse_rule(payload(&ok)).unwrap().map(|x| x.action), Some(Action::Prohibit));
    }

    #[test]
    fn a_rule_with_a_selector_this_helper_never_writes_is_not_described() {
        let base = sample().into_iter().find(|x| x.dport.is_some()).unwrap();
        let good = rule_msg(Verb::Add, &base, 1);
        assert_eq!(parse_rule(payload(&good)).unwrap(), Some(base));
        // A source prefix: header length and the FRA_SRC attribute.
        let mut with_src = Builder::new(RTM_NEWRULE, 0, 1);
        with_src.raw(&[2, 0, 24, 0, 0, 0, 0, 1]);
        with_src.raw(&0u32.to_ne_bytes());
        with_src.attr_u32(FRA_PRIORITY, 13500);
        with_src.attr_u32(FRA_TABLE, 4747);
        with_src.attr(2, &[10, 0, 0, 0]);
        assert_eq!(parse_rule(payload(&with_src.finish())).unwrap(), None, "from 10.0.0.0/24");
        // The same without the attribute but with a source length, a tos or the invert flag.
        for (at, v) in [(2usize, 24u8), (3, 4)] {
            let mut m = good.clone();
            m[NLMSG_HDR + at] = v;
            assert_eq!(parse_rule(payload(&m)).unwrap(), None, "header byte {at}");
        }
        let mut not = good.clone();
        not[NLMSG_HDR + 8..NLMSG_HDR + 12].copy_from_slice(&2u32.to_ne_bytes()); // FIB_RULE_INVERT
        assert_eq!(parse_rule(payload(&not)).unwrap(), None, "a `not` rule");
        // An attribute of a kind this helper never writes (an input interface).
        let mut with_iif = Builder::new(RTM_NEWRULE, 0, 1);
        with_iif.raw(&[2, 0, 0, 0, 0, 0, 0, 1]);
        with_iif.raw(&0u32.to_ne_bytes());
        with_iif.attr_u32(FRA_PRIORITY, 13500);
        with_iif.attr(3, b"wlan0\0");
        assert_eq!(parse_rule(payload(&with_iif.finish())).unwrap(), None, "iif wlan0");
        // The padding and a zero protocol that the kernel adds are harmless.
        let mut padded = Builder::new(RTM_NEWRULE, 0, 1);
        padded.raw(&[2, 0, 0, 0, 0, 0, 0, 3]);
        padded.raw(&0u32.to_ne_bytes());
        padded.attr_u32(FRA_PRIORITY, 13523);
        padded.attr(FRA_PAD, &[0; 4]);
        padded.attr(FRA_PROTOCOL, &[0]);
        padded.attr_u32(FRA_SUPPRESS_PREFIXLEN, u32::MAX);
        padded.attr_u32(FRA_SUPPRESS_IFGROUP, u32::MAX);
        assert_eq!(parse_rule(payload(&padded.finish())).unwrap().map(|x| x.action), Some(Action::Nop));
        // A real suppression is somebody else's rule.
        for kind in [FRA_SUPPRESS_PREFIXLEN, FRA_SUPPRESS_IFGROUP] {
            let mut sup = Builder::new(RTM_NEWRULE, 0, 1);
            sup.raw(&[2, 0, 0, 0, 0, 0, 0, 3]);
            sup.raw(&0u32.to_ne_bytes());
            sup.attr_u32(FRA_PRIORITY, 13523);
            sup.attr_u32(kind, 0);
            assert_eq!(parse_rule(payload(&sup.finish())).unwrap(), None, "attribute {kind}");
        }
        // A protocol other than zero is somebody else's rule.
        let mut proto = Builder::new(RTM_NEWRULE, 0, 1);
        proto.raw(&[2, 0, 0, 0, 0, 0, 0, 3]);
        proto.raw(&0u32.to_ne_bytes());
        proto.attr_u32(FRA_PRIORITY, 13523);
        proto.attr(FRA_PROTOCOL, &[3]);
        assert_eq!(parse_rule(payload(&proto.finish())).unwrap(), None);
    }

    #[test]
    fn an_attribute_with_a_flag_in_its_type_is_not_one_this_helper_writes() {
        let base = sample().into_iter().find(|x| x.dport.is_some()).unwrap();
        let mut m = rule_msg(Verb::Add, &base, 1);
        // The type of the first attribute (the priority) sits two bytes into it: set the nested flag.
        let at = NLMSG_HDR + FIB_RULE_HDR + 2;
        let ty = u16::from_ne_bytes([m[at], m[at + 1]]) | 0x8000;
        m[at..at + 2].copy_from_slice(&ty.to_ne_bytes());
        assert_eq!(parse_rule(payload(&m)).unwrap(), None);
        assert_eq!(rule_priority(payload(&m)).unwrap(), None, "and its priority is then not found either");
    }

    #[test]
    fn the_priority_of_a_deletion_by_priority_is_readable() {
        let m = rule_del_priority_msg(Family::V6, 13510, 3);
        assert_eq!(rule_priority(payload(&m)).unwrap(), Some((Family::V6, 13510)));
    }
}
