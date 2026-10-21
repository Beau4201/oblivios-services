// SPDX-License-Identifier: Apache-2.0
//! A limit on the DNS questions of one app (ADR-186). With masked destinations every app's DNS passes through the daemon, so
//! a flood from one app must slow that app only, never the others. The limit is per uid, in two windows (a second and a
//! minute), and the table of uids is bounded. Safe Rust only, no clock of its own.

use std::collections::HashMap;
use std::time::{Duration, Instant};

/// Questions one uid may ask per second and per minute.
pub const PER_SECOND: u32 = 100;
pub const PER_MINUTE: u32 = 2000;
/// Most uids kept; a new uid past this evicts the one that was seen longest ago.
pub const MAX_UIDS: usize = 4096;

#[derive(Debug, Clone, Copy)]
struct Bucket {
    second_start: Instant,
    in_second: u32,
    minute_start: Instant,
    in_minute: u32,
    last_seen: Instant,
}

#[derive(Debug, Default)]
pub struct RateLimiter {
    buckets: HashMap<u32, Bucket>,
}

impl RateLimiter {
    /// Counts one question of `uid` at `now` and says whether it is within both limits. A refused question still counts
    /// towards the minute, so an app that keeps asking at full speed stays refused.
    pub fn allow(&mut self, uid: u32, now: Instant) -> bool {
        if !self.buckets.contains_key(&uid) && self.buckets.len() >= MAX_UIDS {
            if let Some(oldest) = self.buckets.iter().min_by_key(|(_, b)| b.last_seen).map(|(k, _)| *k) {
                self.buckets.remove(&oldest);
            }
        }
        let b = self.buckets.entry(uid).or_insert(Bucket {
            second_start: now,
            in_second: 0,
            minute_start: now,
            in_minute: 0,
            last_seen: now,
        });
        if now.saturating_duration_since(b.second_start) >= Duration::from_secs(1) {
            b.second_start = now;
            b.in_second = 0;
        }
        if now.saturating_duration_since(b.minute_start) >= Duration::from_secs(60) {
            b.minute_start = now;
            b.in_minute = 0;
        }
        b.last_seen = now;
        b.in_second = b.in_second.saturating_add(1);
        b.in_minute = b.in_minute.saturating_add(1);
        b.in_second <= PER_SECOND && b.in_minute <= PER_MINUTE
    }

    pub fn len(&self) -> usize {
        self.buckets.len()
    }

    pub fn is_empty(&self) -> bool {
        self.buckets.is_empty()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ms(n: u64) -> Duration {
        Duration::from_millis(n)
    }

    #[test]
    fn the_limits_are_the_documented_values() {
        assert_eq!((PER_SECOND, PER_MINUTE, MAX_UIDS), (100, 2000, 4096));
    }

    #[test]
    fn a_hundred_questions_in_a_second_pass_and_the_next_does_not() {
        let mut r = RateLimiter::default();
        let t = Instant::now();
        for i in 0..PER_SECOND {
            assert!(r.allow(10100, t + ms(u64::from(i))), "question {i}");
        }
        assert!(!r.allow(10100, t + ms(500)), "the 101st within the second");
        assert!(!r.allow(10100, t + ms(999)));
    }

    #[test]
    fn a_new_second_starts_afresh() {
        let mut r = RateLimiter::default();
        let t = Instant::now();
        for _ in 0..PER_SECOND + 5 {
            r.allow(10100, t);
        }
        assert!(r.allow(10100, t + ms(1000)), "exactly one second later");
        for i in 1..PER_SECOND {
            assert!(r.allow(10100, t + ms(1000 + u64::from(i))));
        }
        assert!(!r.allow(10100, t + ms(1500)));
    }

    #[test]
    fn two_thousand_in_a_minute_pass_and_the_next_does_not() {
        let mut r = RateLimiter::default();
        let t = Instant::now();
        // 100 a second for 20 seconds is 2000, each second within its own limit.
        for s in 0..20u64 {
            for _ in 0..PER_SECOND {
                assert!(r.allow(10100, t + Duration::from_secs(s)));
            }
        }
        assert!(!r.allow(10100, t + Duration::from_secs(21)), "a fresh second but the 2001st of the minute");
        assert!(r.allow(10100, t + Duration::from_secs(60)), "a minute after the first question");
    }

    #[test]
    fn a_refused_question_still_counts_for_the_minute() {
        let mut r = RateLimiter::default();
        let t = Instant::now();
        // 150 questions a second: 100 pass the second's limit, but all 150 count towards the minute, so the minute's 2000 are
        // used up after about 13 seconds and the rest of the minute is refused.
        let mut allowed = 0;
        for s in 0..30u64 {
            for _ in 0..150 {
                if r.allow(10100, t + Duration::from_secs(s)) {
                    allowed += 1;
                }
            }
        }
        assert!((1300..=1400).contains(&allowed), "{allowed}");
        assert!(allowed < PER_MINUTE);
        assert!(!r.allow(10100, t + Duration::from_secs(30)), "still refused in the same minute");
        assert!(r.allow(10100, t + Duration::from_secs(60)), "a minute after the first question");
    }

    #[test]
    fn one_app_cannot_use_up_the_limit_of_another() {
        let mut r = RateLimiter::default();
        let t = Instant::now();
        for _ in 0..PER_SECOND * 10 {
            r.allow(10100, t);
        }
        assert!(!r.allow(10100, t));
        assert!(r.allow(10101, t), "another app is untouched");
        assert!(r.allow(0, t));
    }

    #[test]
    fn the_table_is_bounded_and_the_least_recent_uid_goes() {
        let mut r = RateLimiter::default();
        let t = Instant::now();
        // uid 0 is seen first, and uses up its second.
        for _ in 0..PER_SECOND + 10 {
            r.allow(0, t);
        }
        for uid in 1..MAX_UIDS as u32 {
            r.allow(uid, t + ms(1 + u64::from(uid) / 10));
        }
        assert_eq!(r.len(), MAX_UIDS);
        // One more uid pushes out the one seen longest ago, which is uid 0 ...
        r.allow(100_000, t + ms(600));
        assert_eq!(r.len(), MAX_UIDS);
        // ... so that it counts afresh within the same second; had it stayed it would still be refused.
        assert!(r.allow(0, t + ms(700)), "uid 0 was evicted and counts afresh");
    }

    #[test]
    fn counters_saturate_and_a_time_that_goes_backwards_does_not_panic() {
        let mut r = RateLimiter::default();
        let t = Instant::now();
        assert!(r.allow(1, t + Duration::from_secs(100)));
        assert!(r.allow(1, t), "earlier than the first question");
        let b = r.buckets.get_mut(&1).unwrap();
        b.in_second = u32::MAX;
        b.in_minute = u32::MAX;
        assert!(!r.allow(1, t));
    }
}
