// SPDX-License-Identifier: Apache-2.0
//! The speed guard (ADR-188). The masked tier sends the names of listed advertising and analytics companies through Tor,
//! which is 7 to 89 times slower for a large transfer. A listed host that serves content, a video or a script bundle of
//! megabytes, is a regression the user sees, and no list finds them all. The guard counts what each masked company sends to
//! each app over Tor; when one company sends 4 MiB to one app within a minute, the daemon passes that company's names on to
//! that app for 30 minutes (the same effect as the user's own "show my address"), and the exit app is told so that it can
//! notify the user, who can end it with "Hide again".
//!
//! Counts and company ids only, in memory, bounded; a host name never enters this module. Safe Rust only, no I/O and no
//! clock: every call is given the time. The caller feeds the counters (the tunnel adds the bytes of each flow) and calls
//! [`Guard::tick`] every [`SAMPLE`].
//!
//! The threat that this accepts: a host can unmask itself by sending 4 MiB to the app. The effect is bounded (one company, one
//! app, 30 minutes, memory only) and visible, and it is what the user may choose anyway. It is a usability protection, not
//! an anonymity claim.

use onion_tunnel::accounting::BandwidthCounter;
use std::collections::{HashMap, HashSet, VecDeque};
use std::sync::Arc;
use std::time::{Duration, Instant};

/// A company is heavy for an app when it sends at least this many bytes within [`WINDOW`].
pub const HEAVY_BYTES: u64 = 4 * 1024 * 1024;
/// The span in which [`HEAVY_BYTES`] must arrive.
pub const WINDOW: Duration = Duration::from_secs(60);
/// How long a heavy company is passed on after it was found heavy.
pub const HOLD: Duration = Duration::from_secs(30 * 60);
/// The counters are read this often.
pub const SAMPLE: Duration = Duration::from_secs(5);
/// The most (app, company) pairs kept, and the most pairs that are pinned hidden.
pub const MAX_PAIRS: usize = 4096;
/// The most promotions told to the exit app in one reply.
pub const MAX_REPORT: usize = 16;

/// An active promotion: the company's names are passed on to the app. `fresh` is true until it has been reported once.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Promotion {
    pub uid: u32,
    pub company: u16,
    pub fresh: bool,
}

struct Pair {
    counter: Arc<BandwidthCounter>,
    /// Cumulative received bytes at the last samples, oldest first, none older than [`WINDOW`] plus one sample.
    history: VecDeque<(Instant, u64)>,
    created: Instant,
    until: Option<Instant>,
    fresh: bool,
}

#[derive(Default)]
pub struct Guard {
    pairs: HashMap<(u32, u16), Pair>,
    /// Pairs the user set back to hidden: they are never promoted again until the daemon restarts.
    pinned: HashSet<(u32, u16)>,
}

impl Guard {
    /// The counter that the tunnel adds the bytes of a flow of `uid` to `company` to. One per pair.
    pub fn counter(&mut self, uid: u32, company: u16, now: Instant) -> Arc<BandwidthCounter> {
        let key = (uid, company);
        if let Some(p) = self.pairs.get(&key) {
            return p.counter.clone();
        }
        if self.pairs.len() >= MAX_PAIRS {
            // Evict the oldest pair that is not promoted (and the oldest of all when every pair is): a flood of pairs cannot
            // grow the table.
            let victim = self
                .pairs
                .iter()
                .filter(|(_, p)| p.until.is_none() && Arc::strong_count(&p.counter) == 1)
                .min_by_key(|(_, p)| p.created)
                .or_else(|| self.pairs.iter().filter(|(_, p)| p.until.is_none()).min_by_key(|(_, p)| p.created))
                .or_else(|| self.pairs.iter().min_by_key(|(_, p)| p.created))
                .map(|(k, _)| *k);
            if let Some(v) = victim {
                self.pairs.remove(&v);
            }
        }
        let counter = Arc::new(BandwidthCounter::new());
        let mut history = VecDeque::new();
        history.push_back((now, 0));
        self.pairs.insert(key, Pair { counter: counter.clone(), history, created: now, until: None, fresh: false });
        counter
    }

    /// True while the names of `company` are passed on to `uid`.
    pub fn heavy(&self, uid: u32, company: u16, now: Instant) -> bool {
        self.pairs.get(&(uid, company)).and_then(|p| p.until).is_some_and(|until| now < until)
    }

    /// Reads every counter: a pair that received [`HEAVY_BYTES`] within [`WINDOW`] is promoted for [`HOLD`], a promotion that
    /// has run out ends, and a pair that has been silent for a whole window is forgotten.
    pub fn tick(&mut self, now: Instant) {
        let pinned = &self.pinned;
        self.pairs.retain(|key, p| {
            let rx = p.counter.bytes_rx();
            if p.until.is_some_and(|u| now >= u) {
                p.until = None;
                p.fresh = false;
                p.history.clear();
            }
            p.history.push_back((now, rx));
            while p.history.len() > 1 && p.history.get(1).is_some_and(|s| now.saturating_duration_since(s.0) >= WINDOW) {
                p.history.pop_front();
            }
            let base = p.history.front().map_or(rx, |s| s.1);
            let delta = rx.saturating_sub(base);
            if p.until.is_none() && !pinned.contains(key) && delta >= HEAVY_BYTES {
                p.until = Some(now + HOLD);
                p.fresh = true;
            }
            let full = p.history.front().is_some_and(|s| now.saturating_duration_since(s.0) >= WINDOW);
            // A pair is kept while a flow still holds its counter: forgetting it would leave that flow counting on a counter
            // that nothing reads, and a later burst on an idle connection would go unseen.
            p.until.is_some() || delta > 0 || !full || Arc::strong_count(&p.counter) > 1
        });
    }

    /// The active promotions, at most `max`, each reported as `fresh` once.
    pub fn report(&mut self, now: Instant, max: usize) -> Vec<Promotion> {
        let mut keys: Vec<(u32, u16)> =
            self.pairs.iter().filter(|(_, p)| p.until.is_some_and(|u| now < u)).map(|(k, _)| *k).collect();
        keys.sort_unstable();
        keys.truncate(max);
        let mut out = Vec::with_capacity(keys.len());
        for (uid, company) in keys {
            if let Some(p) = self.pairs.get_mut(&(uid, company)) {
                out.push(Promotion { uid, company, fresh: p.fresh });
                p.fresh = false;
            }
        }
        out
    }

    /// "Hide again": ends the promotion of the pair and keeps the company hidden for that app until the daemon restarts.
    /// A pair that is not promoted is pinned as well, so the user's choice also holds against a later promotion.
    pub fn release(&mut self, uid: u32, company: u16) {
        if let Some(p) = self.pairs.get_mut(&(uid, company)) {
            p.until = None;
            p.fresh = false;
            p.history.clear();
        }
        if self.pinned.len() < MAX_PAIRS {
            self.pinned.insert((uid, company));
        }
    }

    pub fn pair_count(&self) -> usize {
        self.pairs.len()
    }

    pub fn pinned_count(&self) -> usize {
        self.pinned.len()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const MIB: u64 = 1024 * 1024;

    fn t0() -> Instant {
        Instant::now()
    }

    fn at(base: Instant, secs: u64) -> Instant {
        base + Duration::from_secs(secs)
    }

    #[test]
    fn four_mebibytes_within_a_minute_promote_and_one_byte_less_does_not() {
        let base = t0();
        let mut g = Guard::default();
        let c = g.counter(10100, 7, base);
        c.on_rx(HEAVY_BYTES - 1);
        g.tick(at(base, 5));
        assert!(!g.heavy(10100, 7, at(base, 5)), "one byte under the threshold");
        c.on_rx(1);
        g.tick(at(base, 10));
        assert!(g.heavy(10100, 7, at(base, 10)), "the threshold itself");
    }

    #[test]
    fn the_same_bytes_spread_over_more_than_a_minute_do_not_promote() {
        let base = t0();
        let mut g = Guard::default();
        let c = g.counter(10100, 7, base);
        // 1 MiB every 30 s for twenty minutes: no window of a minute (the base sample is at most one sample older) holds 4 MiB.
        for step in 1..=240u64 {
            let now = at(base, 5 * step);
            if step % 6 == 0 {
                c.on_rx(MIB);
            }
            g.tick(now);
            assert!(!g.heavy(10100, 7, now), "no window holds 4 MiB, step {step}");
        }
    }

    #[test]
    fn the_same_rate_in_a_burst_does_promote() {
        let base = t0();
        let mut g = Guard::default();
        let c = g.counter(10100, 7, base);
        // The same 4 MiB in four seconds, after nineteen quiet minutes.
        for step in 1..=228u64 {
            g.tick(at(base, 5 * step));
        }
        c.on_rx(4 * MIB);
        g.tick(at(base, 5 * 229));
        assert!(g.heavy(10100, 7, at(base, 5 * 229)));
    }

    #[test]
    fn a_promotion_lasts_thirty_minutes_and_not_a_second_more() {
        let base = t0();
        let mut g = Guard::default();
        let c = g.counter(10100, 7, base);
        c.on_rx(HEAVY_BYTES);
        g.tick(at(base, 5));
        let found = at(base, 5);
        assert!(g.heavy(10100, 7, found + HOLD - Duration::from_secs(1)));
        assert!(!g.heavy(10100, 7, found + HOLD), "it ends at the hold");
        g.tick(found + HOLD);
        assert!(!g.heavy(10100, 7, found + HOLD + Duration::from_secs(5)), "and stays ended after a tick");
    }

    #[test]
    fn two_apps_and_two_companies_are_counted_apart() {
        let base = t0();
        let mut g = Guard::default();
        let a = g.counter(10100, 7, base);
        let _b = g.counter(10101, 7, base);
        let _c = g.counter(10100, 8, base);
        a.on_rx(HEAVY_BYTES);
        g.tick(at(base, 5));
        assert!(g.heavy(10100, 7, at(base, 5)));
        assert!(!g.heavy(10101, 7, at(base, 5)), "another app, same company");
        assert!(!g.heavy(10100, 8, at(base, 5)), "same app, another company");
        assert!(!g.heavy(10100, 9, at(base, 5)), "a pair never seen");
    }

    #[test]
    fn one_counter_per_pair() {
        let base = t0();
        let mut g = Guard::default();
        let a = g.counter(10100, 7, base);
        let b = g.counter(10100, 7, at(base, 1));
        assert!(Arc::ptr_eq(&a, &b));
        assert_eq!(g.pair_count(), 1);
    }

    #[test]
    fn hide_again_ends_the_promotion_and_the_pair_is_not_promoted_again() {
        let base = t0();
        let mut g = Guard::default();
        let c = g.counter(10100, 7, base);
        let other = g.counter(10101, 7, base);
        c.on_rx(HEAVY_BYTES);
        other.on_rx(HEAVY_BYTES);
        g.tick(at(base, 5));
        assert!(g.heavy(10100, 7, at(base, 5)) && g.heavy(10101, 7, at(base, 5)));
        g.release(10100, 7);
        assert!(!g.heavy(10100, 7, at(base, 6)), "released");
        assert!(g.heavy(10101, 7, at(base, 6)), "another app keeps its promotion");
        g.tick(at(base, 10)); // the release restarts the count: this sample is the new baseline
        c.on_rx(10 * HEAVY_BYTES);
        g.tick(at(base, 15));
        g.tick(at(base, 20));
        assert!(!g.heavy(10100, 7, at(base, 20)), "pinned: never promoted again");
        assert_eq!(g.pinned_count(), 1);
        // The same bytes on the other pair, which was not released, do promote again (the pin is the only difference).
        let again = g.counter(10101, 7, at(base, 20));
        g.tick(at(base, 25));
        again.on_rx(HEAVY_BYTES);
        g.tick(at(base, 30));
        assert!(g.heavy(10101, 7, at(base, 30)));
    }

    #[test]
    fn the_pin_table_is_bounded() {
        let base = t0();
        let mut g = Guard::default();
        for uid in 0..(MAX_PAIRS as u32 + 5) {
            g.release(10000 + uid, 7);
        }
        assert_eq!(g.pinned_count(), MAX_PAIRS, "the pin table stops at the limit");
        g.release(10000, 7); // a pair that is pinned already is not new
        assert_eq!(g.pinned_count(), MAX_PAIRS);
        let _ = base;
    }

    #[test]
    fn a_full_table_evicts_the_oldest_pair_and_keeps_the_newest() {
        let base = t0();
        let mut g = Guard::default();
        for i in 0..MAX_PAIRS as u32 {
            drop(g.counter(10000 + i, 1, at(base, u64::from(i))));
        }
        drop(g.counter(99_000, 1, at(base, 10_000)));
        assert_eq!(g.pair_count(), MAX_PAIRS);
        assert!(!g.pairs.contains_key(&(10000, 1)), "the oldest pair went");
        assert!(g.pairs.contains_key(&(10000 + MAX_PAIRS as u32 - 1, 1)), "the newest of the old ones stays");
        assert!(g.pairs.contains_key(&(99_000, 1)), "and the new pair is in");
    }

    #[test]
    fn bytes_older_than_a_minute_do_not_count() {
        let base = t0();
        let mut g = Guard::default();
        let c = g.counter(10100, 7, base);
        for s in 1..=2u64 {
            g.tick(at(base, 5 * s));
        }
        c.on_rx(2 * MIB); // arrives just after the sample at 10 s, so the sample at 15 s holds it
        for s in 3..=14u64 {
            g.tick(at(base, 5 * s));
        }
        c.on_rx(2 * MIB); // arrives just before the sample at 75 s
        g.tick(at(base, 75));
        assert!(!g.heavy(10100, 7, at(base, 75)), "the first 2 MiB are 60 s old and outside the window");
        // The same two bursts 55 s apart do promote.
        let mut h = Guard::default();
        let d = h.counter(10100, 7, base);
        h.tick(at(base, 5));
        d.on_rx(2 * MIB);
        for s in 2..=12u64 {
            h.tick(at(base, 5 * s));
        }
        d.on_rx(2 * MIB);
        h.tick(at(base, 65));
        assert!(h.heavy(10100, 7, at(base, 65)), "inside a minute: promoted");
    }

    #[test]
    fn a_pair_that_was_never_promoted_can_be_pinned_too() {
        let base = t0();
        let mut g = Guard::default();
        let c = g.counter(10100, 7, base);
        g.release(10100, 7);
        c.on_rx(HEAVY_BYTES);
        g.tick(at(base, 5));
        assert!(!g.heavy(10100, 7, at(base, 5)));
    }

    #[test]
    fn a_promotion_is_reported_as_fresh_once_and_listed_while_it_lasts() {
        let base = t0();
        let mut g = Guard::default();
        let c = g.counter(10100, 7, base);
        c.on_rx(HEAVY_BYTES);
        g.tick(at(base, 5));
        let first = g.report(at(base, 6), MAX_REPORT);
        assert_eq!(first, vec![Promotion { uid: 10100, company: 7, fresh: true }]);
        let second = g.report(at(base, 7), MAX_REPORT);
        assert_eq!(second, vec![Promotion { uid: 10100, company: 7, fresh: false }], "still listed, not fresh");
        assert!(g.report(at(base, 5) + HOLD, MAX_REPORT).is_empty(), "gone when it has run out");
    }

    #[test]
    fn a_report_holds_at_most_the_given_number() {
        let base = t0();
        let mut g = Guard::default();
        for uid in 10100..10120 {
            g.counter(uid, 7, base).on_rx(HEAVY_BYTES);
        }
        g.tick(at(base, 5));
        assert_eq!(g.report(at(base, 6), MAX_REPORT).len(), MAX_REPORT);
        assert_eq!(g.report(at(base, 6), 3).len(), 3);
    }

    #[test]
    fn the_table_is_bounded_and_keeps_a_promoted_pair() {
        let base = t0();
        let mut g = Guard::default();
        g.counter(10100, 7, base).on_rx(HEAVY_BYTES);
        g.tick(at(base, 5));
        for i in 0..(MAX_PAIRS as u32 + 10) {
            g.counter(20000 + i, 1, at(base, 6));
        }
        assert!(g.pair_count() <= MAX_PAIRS, "the table stays bounded: {}", g.pair_count());
        assert!(g.heavy(10100, 7, at(base, 6)), "the promoted pair is not the one evicted");
    }

    #[test]
    fn a_silent_pair_is_forgotten_after_a_full_window() {
        let base = t0();
        let mut g = Guard::default();
        g.counter(10100, 7, base).on_rx(MIB);
        for s in 1..=10u64 {
            g.tick(at(base, 5 * s));
        }
        assert_eq!(g.pair_count(), 1, "its data is still inside the window");
        for s in 11..=40u64 {
            g.tick(at(base, 5 * s));
        }
        assert_eq!(g.pair_count(), 0, "then a whole window of silence, and no flow holds the counter");
    }

    #[test]
    fn a_pair_whose_counter_an_open_flow_holds_is_not_forgotten() {
        let base = t0();
        let mut g = Guard::default();
        let flow = g.counter(10100, 7, base);
        for s in 1..=60u64 {
            g.tick(at(base, 5 * s));
        }
        assert_eq!(g.pair_count(), 1, "the idle flow still holds the counter");
        drop(flow);
        g.tick(at(base, 5 * 61));
        assert_eq!(g.pair_count(), 0, "forgotten once the flow is gone");
    }

    #[test]
    fn a_full_table_evicts_a_pair_without_a_flow_before_one_with_a_flow() {
        let base = t0();
        let mut g = Guard::default();
        let held = g.counter(1, 1, base);
        for i in 1..MAX_PAIRS as u32 {
            drop(g.counter(1 + i, 1, at(base, 1)));
        }
        assert_eq!(g.pair_count(), MAX_PAIRS);
        drop(g.counter(99_999, 1, at(base, 2)));
        assert_eq!(g.pair_count(), MAX_PAIRS);
        assert!(Arc::ptr_eq(&held, &g.counter(1, 1, at(base, 3))), "the oldest pair holds a live counter and stays");
    }

    #[test]
    fn the_constants_are_the_adr() {
        assert_eq!(HEAVY_BYTES, 4 * 1024 * 1024);
        assert_eq!(WINDOW, Duration::from_secs(60));
        assert_eq!(HOLD, Duration::from_secs(1800));
        assert_eq!(SAMPLE, Duration::from_secs(5));
        assert_eq!(MAX_PAIRS, 4096);
        assert_eq!(MAX_REPORT, 16);
    }
}
