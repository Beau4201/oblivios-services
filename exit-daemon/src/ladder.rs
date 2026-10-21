// SPDX-License-Identifier: Apache-2.0
//! The failure ladder (ADR-181 section 4.3, ADR-186): when connections through Tor fail or are slow, the exit app is told
//! once per app and company and window, and it asks the user what to do. The daemon keeps only counts, in memory, keyed by
//! uid and company id; a host name never enters this module. Safe Rust only, no I/O, no clock: every call is given the time.

use std::collections::HashMap;
use std::time::{Duration, Instant};

/// A key is reported at most once in this window, counted from its first event.
pub const WINDOW: Duration = Duration::from_secs(10 * 60);
/// A key is due after this many failures in the window, or after one slow connection.
pub const FAILURE_THRESHOLD: u32 = 3;
/// A connection that needs this long to be established through Tor is slow.
pub const SLOW: Duration = Duration::from_secs(5);
/// The most keys kept; a new key past this evicts the oldest.
pub const MAX_KEYS: usize = 4096;

/// What the exit app is told about one app and company. Company 0 means "not a listed company" (an app set to Tor).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Due {
    pub uid: u32,
    pub company: u16,
    pub count: u32,
}

#[derive(Debug, Clone, Copy)]
struct Entry {
    first: Instant,
    failures: u32,
    slow: bool,
    reported: bool,
}

#[derive(Debug, Default)]
pub struct Ladder {
    map: HashMap<(u32, u16), Entry>,
}

impl Ladder {
    /// The entry of a key for an event at `now`: a new one when there is none or the window of the old one is over.
    fn entry(&mut self, uid: u32, company: u16, now: Instant) -> &mut Entry {
        let key = (uid, company);
        let stale = self.map.get(&key).is_some_and(|e| now.saturating_duration_since(e.first) > WINDOW);
        if stale {
            self.map.remove(&key);
        }
        if !self.map.contains_key(&key) && self.map.len() >= MAX_KEYS {
            // Evict the oldest: a flood of keys cannot grow the table, and the key it pushes out is the least recent.
            if let Some(oldest) = self.map.iter().min_by_key(|(_, e)| e.first).map(|(k, _)| *k) {
                self.map.remove(&oldest);
            }
        }
        self.map.entry(key).or_insert(Entry { first: now, failures: 0, slow: false, reported: false })
    }

    /// One failed connection through Tor.
    pub fn record_failure(&mut self, uid: u32, company: u16, now: Instant) {
        let e = self.entry(uid, company, now);
        e.failures = e.failures.saturating_add(1);
    }

    /// One connection that was established but took [`SLOW`] or longer.
    pub fn record_slow(&mut self, uid: u32, company: u16, now: Instant) {
        self.entry(uid, company, now).slow = true;
    }

    /// The keys that have crossed a threshold and have not been reported in their window, at most `max` of them (the rest
    /// stay due for the next call). Each key returned is marked reported. Keys whose window is over are forgotten.
    pub fn due(&mut self, now: Instant, max: usize) -> Vec<Due> {
        self.map.retain(|_, e| now.saturating_duration_since(e.first) <= WINDOW);
        let mut keys: Vec<(u32, u16)> = self
            .map
            .iter()
            .filter(|(_, e)| !e.reported && (e.failures >= FAILURE_THRESHOLD || e.slow))
            .map(|(k, _)| *k)
            .collect();
        keys.sort_unstable();
        keys.truncate(max);
        keys.into_iter()
            .filter_map(|k| {
                let e = self.map.get_mut(&k)?;
                e.reported = true;
                Some(Due { uid: k.0, company: k.1, count: e.failures.max(1) })
            })
            .collect()
    }

    pub fn len(&self) -> usize {
        self.map.len()
    }

    pub fn is_empty(&self) -> bool {
        self.map.is_empty()
    }
}

/// The host part of a Tor address as Arti prints it: `host:port`, `[v6]:port`, or just a host. Used to find the company of
/// a failed connection; the host itself is never stored.
pub fn host_of(addr: &str) -> &str {
    if let Some(rest) = addr.strip_prefix('[') {
        return rest.split(']').next().unwrap_or("");
    }
    match addr.rsplit_once(':') {
        // A bare IPv6 address has more than one colon and no port to strip.
        Some((host, port)) if !host.contains(':') && port.bytes().all(|b| b.is_ascii_digit()) => host,
        _ => addr,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn t0() -> Instant {
        Instant::now()
    }

    fn secs(n: u64) -> Duration {
        Duration::from_secs(n)
    }

    #[test]
    fn three_failures_in_the_window_make_a_key_due_once() {
        let mut l = Ladder::default();
        let t = t0();
        l.record_failure(10231, 7, t);
        l.record_failure(10231, 7, t + secs(1));
        assert!(l.due(t + secs(2), 16).is_empty(), "two failures are not enough");
        l.record_failure(10231, 7, t + secs(3));
        assert_eq!(l.due(t + secs(4), 16), vec![Due { uid: 10231, company: 7, count: 3 }]);
        assert!(l.due(t + secs(5), 16).is_empty(), "reported once per window");
        l.record_failure(10231, 7, t + secs(6));
        assert!(l.due(t + secs(7), 16).is_empty(), "more failures in the same window are not reported again");
    }

    #[test]
    fn one_slow_connection_makes_a_key_due() {
        let mut l = Ladder::default();
        let t = t0();
        l.record_slow(10231, 7, t);
        assert_eq!(l.due(t, 16), vec![Due { uid: 10231, company: 7, count: 1 }], "a slow connection is a count of 1");
        let mut l = Ladder::default();
        l.record_failure(10231, 7, t);
        l.record_slow(10231, 7, t + secs(1));
        assert_eq!(l.due(t + secs(2), 16), vec![Due { uid: 10231, company: 7, count: 1 }]);
    }

    #[test]
    fn a_failure_after_the_window_starts_a_new_window() {
        let mut l = Ladder::default();
        let t = t0();
        for i in 0..3 {
            l.record_failure(1, 1, t + secs(i));
        }
        assert_eq!(l.due(t + secs(3), 16).len(), 1);
        // 10 minutes after the first event the window is over: the same key counts afresh and is reported again.
        let later = t + WINDOW + secs(1);
        l.record_failure(1, 1, later);
        l.record_failure(1, 1, later + secs(1));
        assert!(l.due(later + secs(2), 16).is_empty());
        l.record_failure(1, 1, later + secs(3));
        assert_eq!(l.due(later + secs(4), 16), vec![Due { uid: 1, company: 1, count: 3 }]);
    }

    #[test]
    fn the_window_is_closed_at_ten_minutes_exactly() {
        let mut l = Ladder::default();
        let t = t0();
        l.record_failure(1, 1, t);
        l.record_failure(1, 1, t + WINDOW);
        l.record_failure(1, 1, t + WINDOW);
        assert_eq!(l.due(t + WINDOW, 16).len(), 1, "an event exactly at the end still counts in the window");
        let mut l = Ladder::default();
        l.record_failure(1, 1, t);
        l.record_failure(1, 1, t + WINDOW + Duration::from_millis(1));
        l.record_failure(1, 1, t + WINDOW + Duration::from_millis(1));
        assert!(l.due(t + WINDOW + secs(1), 16).is_empty(), "one past the end starts a new window of one event");
    }

    #[test]
    fn keys_are_per_uid_and_per_company() {
        let mut l = Ladder::default();
        let t = t0();
        l.record_failure(1, 1, t);
        l.record_failure(1, 2, t);
        l.record_failure(2, 1, t);
        assert!(l.due(t, 16).is_empty(), "three failures spread over three keys are not three for any key");
        l.record_failure(1, 1, t);
        l.record_failure(1, 1, t);
        assert_eq!(l.due(t, 16), vec![Due { uid: 1, company: 1, count: 3 }]);
    }

    #[test]
    fn at_most_max_keys_are_returned_and_the_rest_stay_due() {
        let mut l = Ladder::default();
        let t = t0();
        for uid in 10000..10010u32 {
            l.record_slow(uid, 0, t);
        }
        let first = l.due(t, 4);
        assert_eq!(first.iter().map(|d| d.uid).collect::<Vec<_>>(), vec![10000, 10001, 10002, 10003]);
        let second = l.due(t, 4);
        assert_eq!(second.iter().map(|d| d.uid).collect::<Vec<_>>(), vec![10004, 10005, 10006, 10007]);
        assert_eq!(l.due(t, 4).len(), 2);
        assert!(l.due(t, 4).is_empty());
        assert!(l.due(t, 0).is_empty());
    }

    #[test]
    fn expired_keys_are_forgotten_when_asked() {
        let mut l = Ladder::default();
        let t = t0();
        l.record_failure(1, 1, t);
        assert_eq!(l.len(), 1);
        let _ = l.due(t + WINDOW + secs(1), 16);
        assert!(l.is_empty());
    }

    #[test]
    fn the_table_is_bounded_and_the_oldest_key_goes_first() {
        let mut l = Ladder::default();
        let t = t0();
        for i in 0..MAX_KEYS as u32 {
            l.record_failure(10000 + i, 0, t + Duration::from_millis(u64::from(i)));
        }
        assert_eq!(l.len(), MAX_KEYS);
        l.record_failure(1, 1, t + secs(60));
        assert_eq!(l.len(), MAX_KEYS, "a new key pushes one out");
        // The oldest key (the first, uid 10000) was evicted. The second still has its failure: two more make three.
        l.record_failure(10001, 0, t + secs(61));
        l.record_failure(10001, 0, t + secs(62));
        assert_eq!(l.due(t + secs(63), usize::MAX), vec![Due { uid: 10001, company: 0, count: 3 }]);
        assert_eq!(l.len(), MAX_KEYS);
    }

    #[test]
    fn counts_saturate_and_a_time_that_goes_backwards_does_not_panic() {
        let mut l = Ladder::default();
        let t = t0();
        l.record_failure(1, 1, t + secs(100));
        l.record_failure(1, 1, t); // earlier than the first event
        l.record_failure(1, 1, t);
        assert_eq!(l.due(t, 16).len(), 1);
        let e = l.entry(2, 2, t);
        e.failures = u32::MAX;
        l.record_failure(2, 2, t);
        assert_eq!(l.due(t, 16)[0].count, u32::MAX);
    }

    #[test]
    fn the_thresholds_are_the_documented_values() {
        assert_eq!((WINDOW, FAILURE_THRESHOLD, SLOW, MAX_KEYS), (Duration::from_secs(600), 3, Duration::from_secs(5), 4096));
    }

    #[test]
    fn the_host_of_a_tor_address() {
        assert_eq!(host_of("example.com:443"), "example.com");
        assert_eq!(host_of("example.com"), "example.com");
        assert_eq!(host_of("1.2.3.4:80"), "1.2.3.4");
        assert_eq!(host_of("[2001:db8::1]:443"), "2001:db8::1");
        assert_eq!(host_of("2001:db8::1"), "2001:db8::1", "a bare v6 address has no port");
        assert_eq!(host_of("abc.onion:80"), "abc.onion");
        assert_eq!(host_of("example.com:http"), "example.com:http", "a port that is not a number is left alone");
        assert_eq!(host_of(""), "");
        assert_eq!(host_of("[::1"), "::1");
        assert_eq!(host_of(":443"), "");
    }
}
