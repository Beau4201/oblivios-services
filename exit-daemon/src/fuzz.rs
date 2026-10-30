// SPDX-License-Identifier: Apache-2.0
//! No input makes a decoder of the daemon panic (ADR-186, design section 7). A panic ends the process (`panic = "abort"`), so
//! one malformed message from an app-controlled path would be an outage of every exit app. Each decoder gets random bytes,
//! valid messages with a few bytes changed or cut, and messages with their length and count fields set to extreme values.
//! The generator is a small xorshift whose seed is part of every failure message, and the tests run under the unwinding test
//! profile, where a panic is a failed test. Tests only: this module is compiled for `cfg(test)`.
#![cfg(test)]

use crate::control::{
    decode_from_daemon, decode_to_daemon, encode_from_daemon, encode_to_daemon, DaemonState, ErrorCode, FromDaemon, Kind,
    ToDaemon, UidClass,
};
use crate::masklist::MaskList;
use crate::routes::netlink;
use crate::routes::proto::{decode_msg, decode_reply, encode_msg, encode_reply, RouteMsg, RouteReply};
use crate::routes::spec::{plan, Action, Dst, Family, RouteSpec, Rule, UidRange};

struct Rng(u64);

impl Rng {
    fn next(&mut self) -> u64 {
        self.0 ^= self.0 << 13;
        self.0 ^= self.0 >> 7;
        self.0 ^= self.0 << 17;
        self.0
    }
    fn below(&mut self, n: u64) -> u64 {
        self.next() % n.max(1)
    }
    fn byte(&mut self) -> u8 {
        self.below(256) as u8
    }
}

const SEEDS: [u64; 4] = [1, 7, 0x9e37_79b9_7f4a_7c15, 0xdead_beef];
const ROUNDS: usize = 30_000;

/// Random bytes, or a valid message with a few bytes replaced, a cut, extra bytes, or an extreme length field.
fn mutate(g: &mut Rng, valid: &[Vec<u8>]) -> Vec<u8> {
    if g.below(4) == 0 || valid.is_empty() {
        return (0..g.below(96)).map(|_| g.byte()).collect();
    }
    let mut v = valid[g.below(valid.len() as u64) as usize].clone();
    for _ in 0..g.below(4) {
        if !v.is_empty() {
            let at = g.below(v.len() as u64) as usize;
            v[at] = g.byte();
        }
    }
    match g.below(6) {
        0 => v.truncate(g.below(v.len() as u64 + 1) as usize),
        1 => v.extend((0..g.below(16)).map(|_| g.byte())),
        2 if v.len() >= 4 => v[..4].copy_from_slice(&[0xff, g.byte(), g.byte(), g.byte()]),
        3 if v.len() >= 6 => v[5] = 0xff,
        _ => {}
    }
    v
}

fn to_daemon_samples() -> Vec<Vec<u8>> {
    let classes = vec![
        UidClass { start: 10000, end: 10230, kind: Kind::Masked },
        UidClass { start: 10231, end: 10231, kind: Kind::Tor },
        UidClass { start: 10232, end: 19999, kind: Kind::Exempt },
    ];
    [
        ToDaemon::Hello { version: 2 },
        ToDaemon::SetMemoryMax { seq: 1, bytes: 64 << 20 },
        ToDaemon::RefreshApp { seq: 2, uid: 10231 },
        ToDaemon::RefreshAll { seq: 3 },
        ToDaemon::Shutdown { seq: 4 },
        ToDaemon::GetState { seq: 5 },
        ToDaemon::SetPolicy { seq: 6, classes },
        ToDaemon::SetOverrides { seq: 7, uid: 10231, companies: vec![1, 2, 3, 65535] },
        ToDaemon::SetCountry { seq: 8, uid: 10231, country: Some(*b"ES") },
        ToDaemon::SetCountry { seq: 9, uid: 10231, country: None },
    ]
    .iter()
    .map(encode_to_daemon)
    .collect()
}

fn from_daemon_samples() -> Vec<Vec<u8>> {
    [
        FromDaemon::Ready { version: 2, iface: "exit0".into() },
        FromDaemon::State { state: DaemonState::Up },
        FromDaemon::Failures { uid: 10231, company: 7, count: 3 },
        FromDaemon::Ack { seq: 9 },
        FromDaemon::Error { seq: 9, code: ErrorCode::TooMany },
    ]
    .iter()
    .map(encode_from_daemon)
    .collect()
}

fn route_samples() -> Vec<Vec<u8>> {
    let spec = RouteSpec {
        iface: "exit0".into(),
        steer: vec![UidRange { start: 10000, end: 10230 }, UidRange { start: 10300, end: 10399 }],
        tor_guard: vec![UidRange { start: 10231, end: 10231 }],
        oem_netid: Some(101),
    };
    vec![
        encode_msg(&RouteMsg::Hello { version: 1 }),
        encode_msg(&RouteMsg::Apply { seq: 1, spec }),
        encode_msg(&RouteMsg::Clear { seq: 2 }),
        encode_msg(&RouteMsg::GetApplied { seq: 3 }),
        encode_reply(&RouteReply::Applied { seq: 4, rules: 99 }),
        encode_reply(&RouteReply::Error { seq: 4, code: crate::control::ErrorCode::Failed }),
    ]
}

#[test]
fn no_control_frame_makes_a_decoder_panic() {
    let (to, from) = (to_daemon_samples(), from_daemon_samples());
    for seed in SEEDS {
        let mut g = Rng(seed);
        for _ in 0..ROUNDS {
            let _ = decode_to_daemon(&mutate(&mut g, &to));
            let _ = decode_from_daemon(&mutate(&mut g, &from));
        }
    }
}

#[test]
fn no_route_frame_makes_a_decoder_panic_and_what_decodes_plans_without_panic() {
    let samples = route_samples();
    for seed in SEEDS {
        let mut g = Rng(seed);
        for _ in 0..ROUNDS {
            let bytes = mutate(&mut g, &samples);
            if let Ok((RouteMsg::Apply { spec, .. }, _)) = decode_msg(&bytes) {
                let _ = plan(&spec);
            }
            let _ = decode_reply(&bytes);
        }
    }
}

#[test]
fn no_netlink_message_makes_a_parser_panic() {
    let rules: Vec<Vec<u8>> = [
        Rule {
            priority: 13500,
            family: Family::V4,
            uids: Some(UidRange { start: 10000, end: 10230 }),
            proto: Some(17),
            dport: Some(53),
            dst: None,
            fwmark: None,
            action: Action::Lookup(4747),
        },
        Rule {
            priority: 13510,
            family: Family::V6,
            uids: Some(UidRange { start: 1, end: 2 }),
            proto: None,
            dport: None,
            dst: Some(Dst::V6(std::net::Ipv6Addr::LOCALHOST, 49)),
            fwmark: Some((0, 0xffff)),
            action: Action::Goto(13523),
        },
    ]
    .iter()
    .map(|r| netlink::rule_msg(netlink::Verb::Add, r, 1))
    .collect();
    for seed in SEEDS {
        let mut g = Rng(seed);
        for _ in 0..ROUNDS {
            let bytes = mutate(&mut g, &rules);
            if let Ok(msgs) = netlink::messages(&bytes) {
                for m in msgs {
                    let _ = netlink::parse_rule(m.payload);
                    let _ = netlink::rule_priority(m.payload);
                    let _ = netlink::error_code(&m);
                }
            }
        }
    }
}

#[test]
fn no_text_makes_the_mask_list_parser_or_matcher_panic() {
    let list = "exit-mask-list\t1\nads.example.com\t7\tExample Ads\nexample.net\t8\tExample Net\ncdn.example.net\t0\tprotected\n";
    let valid = vec![list.as_bytes().to_vec(), b"exit-mask-list\t1\n".to_vec()];
    let real = MaskList::parse(list).unwrap();
    for seed in SEEDS {
        let mut g = Rng(seed);
        for _ in 0..ROUNDS {
            let text = String::from_utf8_lossy(&mutate(&mut g, &valid)).into_owned();
            let _ = MaskList::parse(&text);
            // Names: random bytes as text, and random labels joined by dots.
            let name = String::from_utf8_lossy(&(0..g.below(40)).map(|_| g.byte()).collect::<Vec<u8>>()).into_owned();
            let _ = real.classify(&name);
            let dotted: String = (0..g.below(8)).map(|_| "ab-c".repeat(g.below(20) as usize)).collect::<Vec<_>>().join(".");
            let _ = real.classify(&dotted);
        }
    }
}
