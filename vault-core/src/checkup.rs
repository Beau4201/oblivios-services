// SPDX-License-Identifier: Apache-2.0
//! Checkup: reused and weak passwords, and a strength estimate for the editor.
//!
//! The estimate is a heuristic, not zxcvbn: it counts the character classes used, discounts repeated characters, and
//! calls a password weak outright when it is on the common list (also with digits and symbols appended), a single
//! repeated character, a run, a keyboard row or a repeated block. It never leaves the device and sees no site data.

use std::collections::{HashMap, HashSet};
use std::sync::OnceLock;

use crate::vault::Vault;

const COMMON: &str = include_str!("../data/common-passwords.txt");
const KEYBOARD_ROWS: [&str; 4] = ["qwertyuiop", "asdfghjkl", "zxcvbnm", "1234567890"];

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum WeakReason {
    TooShort,
    Common,
    Pattern,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum Strength {
    Weak,
    Fair,
    Good,
    Strong,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Checkup {
    /// Groups of two or more entries that share a password; ids sorted, groups sorted by their first id.
    pub reused: Vec<Vec<[u8; 16]>>,
    /// Entries with a weak password, in vault order, with the first reason that applies.
    pub weak: Vec<([u8; 16], WeakReason)>,
}

fn common() -> &'static HashSet<&'static str> {
    static SET: OnceLock<HashSet<&'static str>> = OnceLock::new();
    SET.get_or_init(|| COMMON.lines().map(str::trim).filter(|l| !l.is_empty()).collect())
}

/// On the list as typed, or once trailing digits and symbols are taken off (`Password123!`).
fn is_common(password: &str) -> bool {
    let lower = password.to_lowercase();
    let base = lower.trim_end_matches(|c: char| !c.is_alphabetic());
    common().contains(lower.as_str()) || (base.chars().count() >= 4 && common().contains(base))
}

fn is_patterned(password: &str) -> bool {
    let chars: Vec<char> = password.to_lowercase().chars().collect();
    if chars.len() < 2 {
        return false;
    }
    if chars.iter().all(|&c| c == chars[0]) {
        return true;
    }
    // One run, up or down: abcdef, 98765.
    let diffs: Vec<i64> = chars.windows(2).map(|w| i64::from(w[1] as u32) - i64::from(w[0] as u32)).collect();
    if diffs.iter().all(|&d| d == 1) || diffs.iter().all(|&d| d == -1) {
        return true;
    }
    // A stretch of a keyboard row, either way round.
    let s: String = chars.iter().collect();
    let rev: String = chars.iter().rev().collect();
    if chars.len() >= 4 && KEYBOARD_ROWS.iter().any(|row| row.contains(s.as_str()) || row.contains(rev.as_str())) {
        return true;
    }
    // One block repeated: abab, xyzxyzxyz.
    (1..=chars.len() / 2).any(|unit| chars.len() % unit == 0 && chars.chunks(unit).all(|c| c == &chars[..unit]))
}

pub fn checkup(vault: &Vault) -> Checkup {
    let mut by_password: HashMap<&str, Vec<[u8; 16]>> = HashMap::new();
    let mut weak = Vec::new();
    for e in vault.entries() {
        let p = e.password.expose();
        if !p.is_empty() {
            by_password.entry(p).or_default().push(e.id);
        }
        let reason = if p.chars().count() < 8 {
            Some(WeakReason::TooShort)
        } else if is_common(p) {
            Some(WeakReason::Common)
        } else if is_patterned(p) {
            Some(WeakReason::Pattern)
        } else {
            None
        };
        if let Some(r) = reason {
            weak.push((e.id, r));
        }
    }
    let mut reused: Vec<Vec<[u8; 16]>> = by_password.into_values().filter(|g| g.len() > 1).collect();
    reused.iter_mut().for_each(|g| g.sort_unstable());
    reused.sort_unstable();
    Checkup { reused, weak }
}

pub fn strength(password: &str) -> Strength {
    let len = password.chars().count();
    if len == 0 || is_common(password) || is_patterned(password) {
        return Strength::Weak;
    }
    let mut pool = 0u32;
    if password.chars().any(|c| c.is_ascii_lowercase()) {
        pool += 26;
    }
    if password.chars().any(|c| c.is_ascii_uppercase()) {
        pool += 26;
    }
    if password.chars().any(|c| c.is_ascii_digit()) {
        pool += 10;
    }
    if password.chars().any(|c| c.is_ascii() && !c.is_ascii_alphanumeric()) {
        pool += 33;
    }
    if password.chars().any(|c| !c.is_ascii()) {
        pool += 100;
    }
    let unique = password.chars().collect::<HashSet<_>>().len();
    let effective = unique as f64 + 0.5 * (len - unique) as f64;
    let bits = effective * f64::from(pool.max(2)).log2();
    match bits {
        b if b < 40.0 => Strength::Weak,
        b if b < 60.0 => Strength::Fair,
        b if b < 80.0 => Strength::Good,
        _ => Strength::Strong,
    }
}
