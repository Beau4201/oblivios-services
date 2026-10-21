// SPDX-License-Identifier: Apache-2.0
//! The closed rule language of the route helper (ADR-186). The exit app sends a [`RouteSpec`]; [`plan`] expands it into the
//! operations the helper may perform, and nothing else can be asked of it: the priorities, the table, the fake address
//! ranges and the tun addresses are constants of this module, a uid range must lie in the app uids, and every rule that has
//! no uid range is a harmless anchor. Safe Rust only, no I/O.
//!
//! What the rules do, for the uids of a steer range (masked destinations): the DNS queries of the app (UDP and TCP port 53)
//! and every packet to the fake address ranges go to table [`TABLE`], whose default route is the tun, so the daemon sees
//! them. For the uids of a guard range (apps set to Tor): a socket whose mark carries no network id (0) or the id of the Tor
//! network passes, every other network id is prohibited (finding F01); this also closes a Tor app before the Tor network
//! exists, because its sockets then carry the id of the Wi-Fi network. Measured in `MSK-008` and `NET-008`.

use std::net::{Ipv4Addr, Ipv6Addr};

/// The priorities this helper may use, inclusive. Behind the secure-VPN rule (13000) and in front of netd's
/// explicit-network rules (15000, 16000) `[verified-in-source: system/netd/server/RouteController.h:34-82]`.
pub const BAND_FIRST: u32 = 13100;
pub const BAND_LAST: u32 = 13999;
/// The one routing table the rules look up.
pub const TABLE: u32 = 4747;

pub const PRIO_DNS_UDP: u32 = 13500;
pub const PRIO_DNS_TCP: u32 = 13501;
pub const PRIO_FAKE: u32 = 13510;
pub const PRIO_GUARD_ZERO: u32 = 13520;
pub const PRIO_GUARD_OEM: u32 = 13521;
pub const PRIO_GUARD_DENY: u32 = 13522;
/// The `nop` rule that the guard's `goto` jumps to.
pub const PRIO_GUARD_ANCHOR: u32 = 13523;

pub const IPPROTO_TCP: u8 = 6;
pub const IPPROTO_UDP: u8 = 17;
pub const DNS_PORT: u16 = 53;

/// The address of the tun interface: the daemon's DNS listener sits at `169.254.42.53` in the same /24.
pub const TUN_V4: (Ipv4Addr, u8) = (Ipv4Addr::new(169, 254, 42, 127), 24);
pub const TUN_V6: (Ipv6Addr, u8) = (Ipv6Addr::new(0xfd66, 0x7265, 0x6568, 1, 0, 0, 0, 2), 64);
/// The fake addresses the daemon hands out for listed names (`198.18.0.0/15` is the benchmark range of RFC 2544, never
/// routed on the internet; the IPv6 range is the upper half of the daemon's unique-local /48).
pub const FAKE_V4: (Ipv4Addr, u8) = (Ipv4Addr::new(198, 18, 0, 0), 15);
pub const FAKE_V6: (Ipv6Addr, u8) = (Ipv6Addr::new(0xfd66, 0x7265, 0x6568, 0x8000, 0, 0, 0, 0), 49);

/// The first uid of an ordinary app, and the last valid uid (`u32::MAX` means "no uid").
pub const FIRST_APP_UID: u32 = 10000;
pub const LAST_UID: u32 = u32::MAX - 1;
/// At most this many ranges in each list of a specification.
pub const MAX_RANGES: usize = 128;
/// The mask of the network id in a socket mark (the low 16 bits).
pub const NETID_MASK: u32 = 0xffff;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct UidRange {
    pub start: u32,
    pub end: u32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RouteSpec {
    /// The tun interface the daemon created: 1 to 15 bytes of `[a-z0-9_]`.
    pub iface: String,
    /// Uids whose DNS and fake-range packets go into the tun (masked destinations).
    pub steer: Vec<UidRange>,
    /// Uids that may use only the Tor network (apps set to Tor).
    pub tor_guard: Vec<UidRange>,
    /// The network id of the Tor network, once the exit app has registered it; `None` before.
    pub oem_netid: Option<u32>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SpecError {
    BadIface,
    /// A range below the first app uid, with `start > end`, or with an end above the last valid uid.
    BadRange,
    TooManyRanges,
    /// Ranges of one list that are not sorted and disjoint, or a uid in both lists.
    Overlap,
    /// A network id that is 0 or does not fit 16 bits.
    BadNetId,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Family {
    V4,
    V6,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Dst {
    V4(Ipv4Addr, u8),
    V6(Ipv6Addr, u8),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Action {
    Lookup(u32),
    Goto(u32),
    Prohibit,
    Nop,
}

/// One policy rule. Fields that are `None` do not select.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Rule {
    pub priority: u32,
    pub family: Family,
    pub uids: Option<UidRange>,
    pub proto: Option<u8>,
    pub dport: Option<u16>,
    pub dst: Option<Dst>,
    /// Value and mask of the socket mark.
    pub fwmark: Option<(u32, u32)>,
    pub action: Action,
}

/// What the helper may do. Nothing outside this list is ever sent to the kernel.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RuleOp {
    AddrAdd { iface: String, family: Family },
    LinkUp { iface: String },
    RouteDefault { iface: String, family: Family },
    RuleAdd(Rule),
}

fn valid_iface(name: &str) -> bool {
    !name.is_empty() && name.len() <= 15 && name.bytes().all(|b| matches!(b, b'a'..=b'z' | b'0'..=b'9' | b'_'))
}

/// Sorted, disjoint, inside the app uids.
fn check_ranges(list: &[UidRange]) -> Result<(), SpecError> {
    if list.len() > MAX_RANGES {
        return Err(SpecError::TooManyRanges);
    }
    let mut next_free = u64::from(FIRST_APP_UID);
    for r in list {
        if r.start < FIRST_APP_UID || r.start > r.end || r.end > LAST_UID {
            return Err(SpecError::BadRange);
        }
        if u64::from(r.start) < next_free {
            return Err(SpecError::Overlap);
        }
        next_free = u64::from(r.end) + 1;
    }
    Ok(())
}

fn disjoint(a: &[UidRange], b: &[UidRange]) -> bool {
    a.iter().all(|x| b.iter().all(|y| x.end < y.start || y.end < x.start))
}

fn rule(priority: u32, family: Family) -> Rule {
    Rule { priority, family, uids: None, proto: None, dport: None, dst: None, fwmark: None, action: Action::Nop }
}

/// Expands a specification into the closed list of operations, in the order they are applied: the interface first, then
/// the anchors, then the rules by priority, within a priority by uid range, IPv4 before IPv6. The same specification
/// always gives the same list.
pub fn plan(spec: &RouteSpec) -> Result<Vec<RuleOp>, SpecError> {
    if !valid_iface(&spec.iface) {
        return Err(SpecError::BadIface);
    }
    check_ranges(&spec.steer)?;
    check_ranges(&spec.tor_guard)?;
    if !disjoint(&spec.steer, &spec.tor_guard) {
        return Err(SpecError::Overlap);
    }
    if let Some(id) = spec.oem_netid {
        if id == 0 || id > NETID_MASK {
            return Err(SpecError::BadNetId);
        }
    }

    let iface = &spec.iface;
    let mut ops = vec![
        RuleOp::AddrAdd { iface: iface.clone(), family: Family::V4 },
        RuleOp::AddrAdd { iface: iface.clone(), family: Family::V6 },
        RuleOp::LinkUp { iface: iface.clone() },
        RuleOp::RouteDefault { iface: iface.clone(), family: Family::V4 },
        RuleOp::RouteDefault { iface: iface.clone(), family: Family::V6 },
    ];
    let families = [Family::V4, Family::V6];
    let fake = |f: Family| match f {
        Family::V4 => Dst::V4(FAKE_V4.0, FAKE_V4.1),
        Family::V6 => Dst::V6(FAKE_V6.0, FAKE_V6.1),
    };

    // The anchor of the guard's `goto`, only when some uid is guarded.
    if !spec.tor_guard.is_empty() {
        for f in families {
            ops.push(RuleOp::RuleAdd(Rule { action: Action::Nop, ..rule(PRIO_GUARD_ANCHOR, f) }));
        }
    }

    let mut rules: Vec<Rule> = Vec::new();
    for r in &spec.steer {
        for f in families {
            for (prio, proto) in [(PRIO_DNS_UDP, IPPROTO_UDP), (PRIO_DNS_TCP, IPPROTO_TCP)] {
                rules.push(Rule {
                    uids: Some(*r),
                    proto: Some(proto),
                    dport: Some(DNS_PORT),
                    action: Action::Lookup(TABLE),
                    ..rule(prio, f)
                });
            }
            rules.push(Rule { uids: Some(*r), dst: Some(fake(f)), action: Action::Lookup(TABLE), ..rule(PRIO_FAKE, f) });
        }
    }
    for r in &spec.tor_guard {
        for f in families {
            rules.push(Rule {
                uids: Some(*r),
                fwmark: Some((0, NETID_MASK)),
                action: Action::Goto(PRIO_GUARD_ANCHOR),
                ..rule(PRIO_GUARD_ZERO, f)
            });
            if let Some(id) = spec.oem_netid {
                rules.push(Rule {
                    uids: Some(*r),
                    fwmark: Some((id, NETID_MASK)),
                    action: Action::Goto(PRIO_GUARD_ANCHOR),
                    ..rule(PRIO_GUARD_OEM, f)
                });
            }
            rules.push(Rule { uids: Some(*r), action: Action::Prohibit, ..rule(PRIO_GUARD_DENY, f) });
        }
    }
    // Stable order: priority, then uid range, then family (V4 first).
    rules.sort_by_key(|r| (r.priority, r.uids.map(|u| u.start), r.family == Family::V6));
    ops.extend(rules.into_iter().map(RuleOp::RuleAdd));
    Ok(ops)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn r(start: u32, end: u32) -> UidRange {
        UidRange { start, end }
    }

    fn spec(steer: Vec<UidRange>, guard: Vec<UidRange>, netid: Option<u32>) -> RouteSpec {
        RouteSpec { iface: "exit0".into(), steer, tor_guard: guard, oem_netid: netid }
    }

    fn rules(ops: &[RuleOp]) -> Vec<Rule> {
        ops.iter().filter_map(|o| if let RuleOp::RuleAdd(r) = o { Some(*r) } else { None }).collect()
    }

    #[test]
    fn the_constants_are_the_documented_values() {
        // The exit app and the daemon share these; a changed value is a protocol change.
        assert_eq!((BAND_FIRST, BAND_LAST, TABLE), (13100, 13999, 4747));
        assert_eq!((PRIO_DNS_UDP, PRIO_DNS_TCP, PRIO_FAKE), (13500, 13501, 13510));
        assert_eq!((PRIO_GUARD_ZERO, PRIO_GUARD_OEM, PRIO_GUARD_DENY, PRIO_GUARD_ANCHOR), (13520, 13521, 13522, 13523));
        assert_eq!(TUN_V4, (Ipv4Addr::new(169, 254, 42, 127), 24));
        assert_eq!(FAKE_V4, (Ipv4Addr::new(198, 18, 0, 0), 15));
        assert_eq!(FAKE_V6.1, 49);
        assert_eq!((IPPROTO_TCP, IPPROTO_UDP, DNS_PORT), (6, 17, 53));
        assert_eq!((FIRST_APP_UID, LAST_UID, MAX_RANGES, NETID_MASK), (10000, 4294967294, 128, 0xffff));
    }

    #[test]
    fn the_fake_ranges_of_the_rules_are_the_ranges_the_dns_manager_hands_out() {
        use onion_tunnel::{is_fake_address, FAKE_V4_PREFIX, FAKE_V6_PREFIX};
        use smoltcp::wire::{IpAddress, Ipv4Address, Ipv6Address};
        // The rule covers 198.18.0.0/15: the prefix and the length agree with the manager.
        assert_eq!(FAKE_V4.0.octets()[..2], FAKE_V4_PREFIX);
        assert_eq!(FAKE_V4.1, 15);
        assert_eq!(FAKE_V6.0.octets()[..6], FAKE_V6_PREFIX);
        assert_eq!(FAKE_V6.1, 49);
        // First and last address of each range are fake, the neighbours are not.
        let v4 = |a: [u8; 4]| is_fake_address(&IpAddress::Ipv4(Ipv4Address::from(a)));
        assert!(v4([198, 18, 0, 0]) && v4([198, 19, 255, 255]) && !v4([198, 17, 255, 255]) && !v4([198, 20, 0, 0]));
        let v6 = |x: u128| is_fake_address(&IpAddress::Ipv6(Ipv6Address::from(x)));
        let base = u128::from(FAKE_V6.0);
        let size = 1u128 << (128 - u32::from(FAKE_V6.1));
        assert!(v6(base) && v6(base + size - 1) && !v6(base - 1) && !v6(base + size));
        // The tun's own addresses are not in the fake ranges, or a connection to the tun would be taken for one to a name.
        assert!(!v4(TUN_V4.0.octets()));
        assert!(!v6(u128::from(TUN_V6.0)));
    }

    #[test]
    fn a_steer_range_gives_the_dns_and_fake_rules_for_both_families_and_nothing_else() {
        let ops = plan(&spec(vec![r(10000, 10230)], vec![], None)).unwrap();
        assert_eq!(
            &ops[..5],
            &[
                RuleOp::AddrAdd { iface: "exit0".into(), family: Family::V4 },
                RuleOp::AddrAdd { iface: "exit0".into(), family: Family::V6 },
                RuleOp::LinkUp { iface: "exit0".into() },
                RuleOp::RouteDefault { iface: "exit0".into(), family: Family::V4 },
                RuleOp::RouteDefault { iface: "exit0".into(), family: Family::V6 },
            ]
        );
        let got = rules(&ops);
        assert_eq!(got.len(), 6, "udp 53, tcp 53 and the fake range, for two families");
        let uid = Some(r(10000, 10230));
        let lookup = Action::Lookup(4747);
        let expect = [
            Rule { priority: 13500, family: Family::V4, uids: uid, proto: Some(17), dport: Some(53), dst: None, fwmark: None, action: lookup },
            Rule { priority: 13500, family: Family::V6, uids: uid, proto: Some(17), dport: Some(53), dst: None, fwmark: None, action: lookup },
            Rule { priority: 13501, family: Family::V4, uids: uid, proto: Some(6), dport: Some(53), dst: None, fwmark: None, action: lookup },
            Rule { priority: 13501, family: Family::V6, uids: uid, proto: Some(6), dport: Some(53), dst: None, fwmark: None, action: lookup },
            Rule {
                priority: 13510,
                family: Family::V4,
                uids: uid,
                proto: None,
                dport: None,
                dst: Some(Dst::V4(Ipv4Addr::new(198, 18, 0, 0), 15)),
                fwmark: None,
                action: lookup,
            },
            Rule {
                priority: 13510,
                family: Family::V6,
                uids: uid,
                proto: None,
                dport: None,
                dst: Some(Dst::V6(Ipv6Addr::new(0xfd66, 0x7265, 0x6568, 0x8000, 0, 0, 0, 0), 49)),
                fwmark: None,
                action: lookup,
            },
        ];
        assert_eq!(got, expect);
    }

    #[test]
    fn a_guard_range_gives_the_pass_and_deny_rules_and_the_anchor() {
        let ops = plan(&spec(vec![], vec![r(10231, 10231)], Some(101))).unwrap();
        let got = rules(&ops);
        let uid = Some(r(10231, 10231));
        let at = |prio: u32, fam: Family| got.iter().find(|x| x.priority == prio && x.family == fam).copied().unwrap();
        for f in [Family::V4, Family::V6] {
            let anchor = at(13523, f);
            assert_eq!((anchor.uids, anchor.action), (None, Action::Nop), "the anchor selects nothing");
            let zero = at(13520, f);
            assert_eq!((zero.uids, zero.fwmark, zero.action), (uid, Some((0, 0xffff)), Action::Goto(13523)));
            let oem = at(13521, f);
            assert_eq!((oem.uids, oem.fwmark, oem.action), (uid, Some((101, 0xffff)), Action::Goto(13523)));
            let deny = at(13522, f);
            assert_eq!((deny.uids, deny.fwmark, deny.action), (uid, None, Action::Prohibit));
        }
        assert_eq!(got.len(), 8, "four rules for each of two families");
    }

    #[test]
    fn before_the_tor_network_exists_only_the_unmarked_socket_passes_the_guard() {
        let got = rules(&plan(&spec(vec![], vec![r(10231, 10231)], None)).unwrap());
        assert!(got.iter().all(|x| x.priority != PRIO_GUARD_OEM), "no rule for a network that does not exist");
        assert_eq!(got.iter().filter(|x| x.priority == PRIO_GUARD_ZERO).count(), 2);
        assert_eq!(got.iter().filter(|x| x.priority == PRIO_GUARD_DENY).count(), 2);
    }

    #[test]
    fn no_guard_range_means_no_anchor() {
        let got = rules(&plan(&spec(vec![r(10000, 10000)], vec![], Some(5))).unwrap());
        assert!(got.iter().all(|x| x.priority != PRIO_GUARD_ANCHOR && x.priority != PRIO_GUARD_ZERO));
    }

    #[test]
    fn an_empty_specification_only_brings_the_interface_up() {
        let ops = plan(&spec(vec![], vec![], None)).unwrap();
        assert_eq!(ops.len(), 5);
        assert!(rules(&ops).is_empty());
    }

    #[test]
    fn the_order_is_by_priority_then_uid_then_family() {
        let ops = plan(&spec(vec![r(10300, 10399), r(10000, 10099)], vec![], None));
        // Unsorted ranges are an error, so build them sorted and check the order of the output.
        assert_eq!(ops, Err(SpecError::Overlap));
        let ops = plan(&spec(vec![r(10000, 10099), r(10300, 10399)], vec![r(10200, 10200)], Some(7))).unwrap();
        let all = rules(&ops);
        // The anchors come first, so that a `goto` never points at a rule that does not exist yet.
        let (anchors, rest) = all.split_at(2);
        assert!(anchors.iter().all(|x| x.priority == PRIO_GUARD_ANCHOR));
        assert!(rest.iter().all(|x| x.priority != PRIO_GUARD_ANCHOR));
        let key: Vec<(u32, Option<u32>, bool)> =
            rest.iter().map(|x| (x.priority, x.uids.map(|u| u.start), x.family == Family::V6)).collect();
        let mut sorted = key.clone();
        sorted.sort();
        assert_eq!(key, sorted);
        assert_eq!(plan(&spec(vec![r(10000, 10099)], vec![], None)), plan(&spec(vec![r(10000, 10099)], vec![], None)));
    }

    #[test]
    fn an_interface_name_is_bounded_and_plain() {
        for bad in ["", "Exit0", "exit-0", "exit.0", "exit 0", "exit/0", "exit0\0", "é", "sixteen_chars_xx"] {
            let mut s = spec(vec![], vec![], None);
            s.iface = bad.into();
            assert_eq!(plan(&s), Err(SpecError::BadIface), "{bad:?}");
        }
        let mut s = spec(vec![], vec![], None);
        s.iface = "a".repeat(15);
        assert!(plan(&s).is_ok());
        s.iface = "exit_0".into();
        assert!(plan(&s).is_ok());
    }

    #[test]
    fn a_range_must_lie_in_the_app_uids() {
        for (a, b) in [(9999, 10000), (0, 0), (1000, 1000), (10010, 10009), (10000, u32::MAX)] {
            for as_guard in [false, true] {
                let (steer, guard) = if as_guard { (vec![], vec![r(a, b)]) } else { (vec![r(a, b)], vec![]) };
                assert_eq!(plan(&spec(steer, guard, None)), Err(SpecError::BadRange), "{a}-{b}");
            }
        }
        assert!(plan(&spec(vec![r(10000, 10000)], vec![], None)).is_ok());
        assert!(plan(&spec(vec![r(10000, LAST_UID)], vec![], None)).is_ok());
        assert!(plan(&spec(vec![r(LAST_UID, LAST_UID)], vec![], None)).is_ok());
    }

    #[test]
    fn ranges_must_be_sorted_disjoint_and_not_shared_between_the_lists() {
        assert_eq!(plan(&spec(vec![r(10000, 10010), r(10010, 10020)], vec![], None)), Err(SpecError::Overlap));
        assert_eq!(plan(&spec(vec![r(10100, 10110), r(10000, 10010)], vec![], None)), Err(SpecError::Overlap));
        assert_eq!(plan(&spec(vec![], vec![r(10000, 10010), r(10005, 10006)], None)), Err(SpecError::Overlap));
        assert!(plan(&spec(vec![r(10000, 10009), r(10010, 10020)], vec![], None)).is_ok(), "adjacent is fine");
        // A uid in both lists.
        assert_eq!(plan(&spec(vec![r(10000, 10010)], vec![r(10010, 10010)], None)), Err(SpecError::Overlap));
        assert_eq!(plan(&spec(vec![r(10000, 10010)], vec![r(9990 + 10, 10000)], None)), Err(SpecError::Overlap));
        assert!(plan(&spec(vec![r(10000, 10010)], vec![r(10011, 10011)], None)).is_ok());
        assert!(plan(&spec(vec![r(10012, 10020)], vec![r(10011, 10011)], None)).is_ok());
    }

    #[test]
    fn the_number_of_ranges_is_bounded() {
        let make = |n: usize| (0..n as u32).map(|i| r(10000 + 2 * i, 10000 + 2 * i)).collect::<Vec<_>>();
        assert!(plan(&spec(make(MAX_RANGES), vec![], None)).is_ok());
        assert_eq!(plan(&spec(make(MAX_RANGES + 1), vec![], None)), Err(SpecError::TooManyRanges));
        assert!(plan(&spec(vec![], make(MAX_RANGES), None)).is_ok());
        assert_eq!(plan(&spec(vec![], make(MAX_RANGES + 1), None)), Err(SpecError::TooManyRanges));
    }

    #[test]
    fn the_network_id_must_fit_the_mark() {
        for bad in [0u32, 65536, u32::MAX] {
            assert_eq!(plan(&spec(vec![], vec![r(10000, 10000)], Some(bad))), Err(SpecError::BadNetId), "{bad}");
        }
        assert!(plan(&spec(vec![], vec![r(10000, 10000)], Some(1))).is_ok());
        assert!(plan(&spec(vec![], vec![r(10000, 10000)], Some(65535))).is_ok());
    }

    /// A small xorshift generator: the property test needs no dependency and prints its seed on failure.
    struct Rng(u64);
    impl Rng {
        fn next(&mut self) -> u64 {
            self.0 ^= self.0 << 13;
            self.0 ^= self.0 >> 7;
            self.0 ^= self.0 << 17;
            self.0
        }
        fn below(&mut self, n: u64) -> u64 {
            self.next() % n
        }
    }

    fn random_ranges(g: &mut Rng, wild: bool) -> Vec<UidRange> {
        let mut out = Vec::new();
        let mut at = if wild { g.below(20000) as u32 } else { 10000 };
        for _ in 0..g.below(6) {
            let start = at.saturating_add(g.below(50) as u32);
            let end = if wild { start.saturating_add(g.below(100) as u32).wrapping_sub(g.below(3) as u32) } else { start.saturating_add(g.below(100) as u32) };
            out.push(r(start, end));
            at = end.saturating_add(1);
        }
        out
    }

    /// The closed language: whatever the input, an accepted plan holds only operations inside the band, the one table, the
    /// declared interface and the declared uid ranges. This is what the helper can be asked to do, and nothing more.
    #[test]
    fn whatever_is_asked_an_accepted_plan_stays_inside_the_band_the_table_and_the_declared_uids() {
        for seed in [1u64, 7, 0x9e37_79b9_7f4a_7c15, 0xdead_beef] {
            let mut g = Rng(seed);
            let mut accepted = 0;
            for _ in 0..20_000 {
                let iface = ["exit0", "tun1", "bad name", "", "x"][g.below(5) as usize];
                let (wild_a, wild_b) = (g.below(4) == 0, g.below(4) == 0);
                let steer = random_ranges(&mut g, wild_a);
                let tor_guard = random_ranges(&mut g, wild_b);
                let oem_netid = [None, Some(0), Some(1), Some(101), Some(65535), Some(65536)][g.below(6) as usize];
                let s = RouteSpec { iface: iface.into(), steer, tor_guard, oem_netid };
                let Ok(ops) = plan(&s) else { continue };
                accepted += 1;
                for op in &ops {
                    match op {
                        RuleOp::AddrAdd { iface, .. } | RuleOp::LinkUp { iface } | RuleOp::RouteDefault { iface, .. } => {
                            assert_eq!(iface, &s.iface, "seed {seed}")
                        }
                        RuleOp::RuleAdd(x) => {
                            assert!((BAND_FIRST..=BAND_LAST).contains(&x.priority), "seed {seed}: {x:?}");
                            match x.action {
                                Action::Lookup(t) => assert_eq!(t, TABLE, "seed {seed}: {x:?}"),
                                Action::Goto(t) => assert_eq!((t, x.priority), (PRIO_GUARD_ANCHOR, x.priority), "seed {seed}"),
                                Action::Prohibit | Action::Nop => {}
                            }
                            match x.uids {
                                Some(u) => {
                                    assert!(u.start >= FIRST_APP_UID && u.start <= u.end && u.end <= LAST_UID, "seed {seed}: {x:?}");
                                    let declared = s.steer.iter().chain(s.tor_guard.iter()).any(|d| *d == u);
                                    assert!(declared, "seed {seed}: a rule for a uid range nobody asked for: {x:?}");
                                }
                                None => assert_eq!(x.action, Action::Nop, "seed {seed}: a rule with no uid must be the anchor: {x:?}"),
                            }
                            if x.action == Action::Prohibit {
                                assert_eq!(x.priority, PRIO_GUARD_DENY, "seed {seed}");
                                assert!(x.uids.is_some());
                            }
                            if let Some((value, mask)) = x.fwmark {
                                assert_eq!(mask, NETID_MASK, "seed {seed}");
                                assert!(value <= NETID_MASK, "seed {seed}");
                            }
                            // A steered uid is never also guarded.
                            if let Some(u) = x.uids {
                                let in_steer = s.steer.contains(&u);
                                let in_guard = s.tor_guard.contains(&u);
                                assert!(in_steer != in_guard, "seed {seed}: {x:?}");
                            }
                        }
                    }
                }
            }
            assert!(accepted > 1000, "seed {seed}: only {accepted} of 20000 random specifications were accepted; the test is too weak");
        }
    }
}
