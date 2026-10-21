// SPDX-License-Identifier: Apache-2.0
//! What the exit app told the daemon about the apps (ADR-186): the class of each app uid (Tor, masked, exempt) and the
//! companies the user chose to show the real address to. Safe Rust only, no I/O. Everything is bounded: the class table
//! by [`crate::control::MAX_CLASSES`], the override table by [`MAX_OVERRIDE_UIDS`] and [`crate::control::MAX_OVERRIDES`].

use crate::control::{ErrorCode, Kind, UidClass, FIRST_APP_UID, MAX_CLASSES, MAX_OVERRIDES};
use std::collections::HashMap;
use std::sync::{Arc, RwLock};

/// The most uids that may carry an override at one time.
pub const MAX_OVERRIDE_UIDS: usize = 1024;

/// Checks a class table the way the control server does before it stores it: at most [`MAX_CLASSES`] entries, every
/// range inside the app uids (`start >= FIRST_APP_UID`, `start <= end`), and the entries sorted and without overlap, so a
/// lookup is a binary search and no uid has two classes.
pub fn validate_classes(classes: &[UidClass]) -> Result<(), ErrorCode> {
    if classes.len() > MAX_CLASSES {
        return Err(ErrorCode::TooMany);
    }
    let mut next_free: u64 = u64::from(FIRST_APP_UID);
    for c in classes {
        // `next_free` starts at the first app uid, so a range below it fails the last test.
        if c.start > c.end || u64::from(c.start) < next_free {
            return Err(ErrorCode::BadValue);
        }
        next_free = u64::from(c.end) + 1;
    }
    Ok(())
}

/// Checks the company list of one override: at most [`MAX_OVERRIDES`] entries.
pub fn validate_overrides(companies: &[u16]) -> Result<(), ErrorCode> {
    if companies.len() > MAX_OVERRIDES {
        return Err(ErrorCode::TooMany);
    }
    Ok(())
}

#[derive(Debug, Default, Clone)]
pub struct Policy {
    classes: Vec<UidClass>,
    overrides: HashMap<u32, Vec<u16>>,
}

impl Policy {
    /// Replaces the class table. Refuses (and keeps the old table) when [`validate_classes`] does.
    pub fn set_classes(&mut self, classes: Vec<UidClass>) -> Result<(), ErrorCode> {
        validate_classes(&classes)?;
        self.classes = classes;
        Ok(())
    }

    /// Replaces the overrides of one uid; an empty list removes them. A uid below [`FIRST_APP_UID`] is refused, and so
    /// is a new uid once [`MAX_OVERRIDE_UIDS`] uids carry overrides.
    pub fn set_overrides(&mut self, uid: u32, companies: Vec<u16>) -> Result<(), ErrorCode> {
        validate_overrides(&companies)?;
        if uid < FIRST_APP_UID {
            return Err(ErrorCode::BadValue);
        }
        if companies.is_empty() {
            self.overrides.remove(&uid);
            return Ok(());
        }
        if !self.overrides.contains_key(&uid) && self.overrides.len() >= MAX_OVERRIDE_UIDS {
            return Err(ErrorCode::TooMany);
        }
        self.overrides.insert(uid, companies);
        Ok(())
    }

    /// The class of an app uid, or `None` when no range covers it (then the daemon treats the uid as exempt).
    pub fn kind_of(&self, uid: u32) -> Option<Kind> {
        // The table is sorted and disjoint: the candidate is the last range that starts at or before the uid.
        let after = self.classes.partition_point(|c| c.start <= uid);
        let c = self.classes.get(after.checked_sub(1)?)?;
        (uid <= c.end).then_some(c.kind)
    }

    /// True when the user chose to show the real address of `company` to the app `uid`.
    pub fn overridden(&self, uid: u32, company: u16) -> bool {
        self.overrides.get(&uid).is_some_and(|v| v.contains(&company))
    }

    pub fn class_count(&self) -> usize {
        self.classes.len()
    }

    pub fn override_uid_count(&self) -> usize {
        self.overrides.len()
    }
}

/// The policy as the control server writes it and the packet path reads it.
pub type SharedPolicy = Arc<RwLock<Policy>>;

#[cfg(test)]
mod tests {
    use super::*;

    fn c(start: u32, end: u32, kind: Kind) -> UidClass {
        UidClass { start, end, kind }
    }

    #[test]
    fn a_sorted_disjoint_table_inside_the_app_uids_is_valid() {
        assert_eq!(validate_classes(&[]), Ok(()));
        assert_eq!(validate_classes(&[c(10000, 10000, Kind::Tor)]), Ok(()));
        assert_eq!(
            validate_classes(&[c(10000, 10230, Kind::Masked), c(10231, 10231, Kind::Tor), c(10232, 19999, Kind::Masked)]),
            Ok(())
        );
        assert_eq!(validate_classes(&[c(10000, u32::MAX - 1, Kind::Masked)]), Ok(()));
    }

    #[test]
    fn a_range_below_the_first_app_uid_is_refused() {
        assert_eq!(validate_classes(&[c(9999, 10000, Kind::Tor)]), Err(ErrorCode::BadValue));
        assert_eq!(validate_classes(&[c(0, 0, Kind::Exempt)]), Err(ErrorCode::BadValue));
        assert_eq!(validate_classes(&[c(1000, 1000, Kind::Exempt)]), Err(ErrorCode::BadValue));
    }

    #[test]
    fn a_range_whose_start_is_after_its_end_is_refused() {
        assert_eq!(validate_classes(&[c(10010, 10009, Kind::Tor)]), Err(ErrorCode::BadValue));
    }

    #[test]
    fn overlapping_or_unsorted_ranges_are_refused() {
        // Overlap by one uid, of different kinds and of the same kind.
        assert_eq!(validate_classes(&[c(10000, 10010, Kind::Masked), c(10010, 10020, Kind::Tor)]), Err(ErrorCode::BadValue));
        assert_eq!(validate_classes(&[c(10000, 10010, Kind::Tor), c(10010, 10020, Kind::Tor)]), Err(ErrorCode::BadValue));
        // Unsorted.
        assert_eq!(validate_classes(&[c(10100, 10110, Kind::Tor), c(10000, 10010, Kind::Tor)]), Err(ErrorCode::BadValue));
        // Adjacent is fine.
        assert_eq!(validate_classes(&[c(10000, 10009, Kind::Masked), c(10010, 10020, Kind::Tor)]), Ok(()));
    }

    #[test]
    fn the_number_of_classes_is_bounded() {
        let ok: Vec<UidClass> = (0..MAX_CLASSES as u32).map(|i| c(10000 + 2 * i, 10000 + 2 * i, Kind::Tor)).collect();
        assert_eq!(validate_classes(&ok), Ok(()));
        let too_many: Vec<UidClass> = (0..MAX_CLASSES as u32 + 1).map(|i| c(10000 + 2 * i, 10000 + 2 * i, Kind::Tor)).collect();
        assert_eq!(validate_classes(&too_many), Err(ErrorCode::TooMany));
    }

    #[test]
    fn a_refused_table_leaves_the_old_one_in_place() {
        let mut p = Policy::default();
        p.set_classes(vec![c(10000, 10010, Kind::Tor)]).unwrap();
        assert_eq!(p.set_classes(vec![c(5, 6, Kind::Tor)]), Err(ErrorCode::BadValue));
        assert_eq!(p.kind_of(10005), Some(Kind::Tor));
        assert_eq!(p.class_count(), 1);
    }

    #[test]
    fn kind_of_finds_the_range_and_nothing_outside_it() {
        let mut p = Policy::default();
        p.set_classes(vec![c(10000, 10230, Kind::Masked), c(10231, 10231, Kind::Tor), c(10300, 10399, Kind::Exempt)]).unwrap();
        assert_eq!(p.kind_of(9999), None);
        assert_eq!(p.kind_of(10000), Some(Kind::Masked));
        assert_eq!(p.kind_of(10230), Some(Kind::Masked));
        assert_eq!(p.kind_of(10231), Some(Kind::Tor));
        assert_eq!(p.kind_of(10232), None, "a gap between two ranges has no class");
        assert_eq!(p.kind_of(10299), None);
        assert_eq!(p.kind_of(10300), Some(Kind::Exempt));
        assert_eq!(p.kind_of(10399), Some(Kind::Exempt));
        assert_eq!(p.kind_of(10400), None);
        assert_eq!(p.kind_of(u32::MAX), None);
        assert_eq!(Policy::default().kind_of(10000), None, "an empty table covers nothing");
    }

    #[test]
    fn a_range_that_ends_at_the_top_is_found_without_overflow() {
        let mut p = Policy::default();
        p.set_classes(vec![c(10000, u32::MAX - 1, Kind::Masked)]).unwrap();
        assert_eq!(p.kind_of(u32::MAX - 1), Some(Kind::Masked));
        assert_eq!(p.kind_of(u32::MAX), None);
    }

    #[test]
    fn overrides_are_per_uid_and_company() {
        let mut p = Policy::default();
        p.set_overrides(10231, vec![3, 9]).unwrap();
        assert!(p.overridden(10231, 3));
        assert!(p.overridden(10231, 9));
        assert!(!p.overridden(10231, 4));
        assert!(!p.overridden(10232, 3));
        p.set_overrides(10231, vec![4]).unwrap();
        assert!(!p.overridden(10231, 3), "a new list replaces the old one");
        assert!(p.overridden(10231, 4));
        p.set_overrides(10231, vec![]).unwrap();
        assert!(!p.overridden(10231, 4));
        assert_eq!(p.override_uid_count(), 0, "an empty list removes the uid");
    }

    #[test]
    fn overrides_are_bounded() {
        let mut p = Policy::default();
        let max: Vec<u16> = (0..MAX_OVERRIDES as u16).collect();
        assert_eq!(p.set_overrides(10000, max), Ok(()));
        let over: Vec<u16> = (0..MAX_OVERRIDES as u16 + 1).collect();
        assert_eq!(p.set_overrides(10000, over), Err(ErrorCode::TooMany));
        assert_eq!(p.set_overrides(9999, vec![1]), Err(ErrorCode::BadValue));
        let mut q = Policy::default();
        for uid in 0..MAX_OVERRIDE_UIDS as u32 {
            q.set_overrides(10000 + uid, vec![1]).unwrap();
        }
        assert_eq!(q.set_overrides(10000 + MAX_OVERRIDE_UIDS as u32, vec![1]), Err(ErrorCode::TooMany));
        // An existing uid can still be changed or removed when the table is full.
        assert_eq!(q.set_overrides(10000, vec![2]), Ok(()));
        assert_eq!(q.set_overrides(10001, vec![]), Ok(()));
        assert_eq!(q.set_overrides(10000 + MAX_OVERRIDE_UIDS as u32, vec![1]), Ok(()));
    }
}
