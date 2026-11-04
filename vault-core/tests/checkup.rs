// SPDX-License-Identifier: Apache-2.0
use rand::rngs::StdRng;
use rand::{Error, RngCore, SeedableRng};
use vault_core::checkup::{checkup, strength, Strength, WeakReason};
use vault_core::error::VaultError;
use vault_core::generator::{generate, uniform_below, GenOpts};
use vault_core::secret::SecretString;
use vault_core::vault::{Entry, Vault};

fn entry(n: u8, password: &str) -> Entry {
    Entry {
        id: [n; 16],
        title: format!("entry {n}"),
        domains: vec![],
        packages: vec![],
        username: "u".into(),
        password: SecretString::new(password),
        notes: String::new(),
        created_ms: 0,
        edited_ms: 0,
        last_filled_ms: None,
        history: vec![],
    }
}

fn vault_of(passwords: &[&str]) -> Vault {
    let mut v = Vault::new();
    for (i, p) in passwords.iter().enumerate() {
        v.add(entry(i as u8 + 1, p)).unwrap();
    }
    v
}

#[test]
fn reused_groups_entries_with_the_same_password() {
    let v = vault_of(&["q9Lz-tundra-wicker-48", "other-Strong-pass-77!", "q9Lz-tundra-wicker-48", "q9Lz-tundra-wicker-48", ""]);
    let c = checkup(&v);
    assert_eq!(c.reused, vec![vec![[1; 16], [3; 16], [4; 16]]]);
}

#[test]
fn empty_passwords_are_not_a_reuse_group() {
    let c = checkup(&vault_of(&["", ""]));
    assert!(c.reused.is_empty());
}

#[test]
fn weak_flags_short_common_and_patterned_passwords() {
    let v = vault_of(&[
        "abc12",             // 1 too short
        "Password",          // 2 common (case-insensitive), 8 chars
        "password123",       // 3 common after the digits are stripped
        "aaaaaaaaaa",        // 4 pattern: one character
        "abcdefghij",        // 5 pattern: a run
        "9876543210",        // 6 pattern: a descending run
        "xyzxyzxyzxyz",      // 7 pattern: a repeated block
        "q9Lz-tundra-wicker-48", // 8 fine
        "",                  // 9 empty: too short
    ]);
    let weak = checkup(&v).weak;
    let reason = |n: u8| weak.iter().find(|(id, _)| *id == [n; 16]).map(|(_, r)| *r);
    assert_eq!(reason(1), Some(WeakReason::TooShort));
    assert_eq!(reason(2), Some(WeakReason::Common));
    assert_eq!(reason(3), Some(WeakReason::Common));
    assert_eq!(reason(4), Some(WeakReason::Pattern));
    assert_eq!(reason(5), Some(WeakReason::Pattern));
    assert_eq!(reason(6), Some(WeakReason::Pattern));
    assert_eq!(reason(7), Some(WeakReason::Pattern));
    assert_eq!(reason(8), None);
    assert_eq!(reason(9), Some(WeakReason::TooShort));
}

#[test]
fn strength_of_the_reference_password_is_strong() {
    assert_eq!(strength("q9Lz-tundra-wicker-48"), Strength::Strong);
}

#[test]
fn strength_orders_the_obvious_cases() {
    assert_eq!(strength(""), Strength::Weak);
    assert_eq!(strength("password"), Strength::Weak);
    assert_eq!(strength("Password123!"), Strength::Weak, "a common word with decoration is still weak");
    assert_eq!(strength("aaaaaaaaaaaaaaaaaaaa"), Strength::Weak);
    assert_eq!(strength("abcdefghijklmnop"), Strength::Weak);
    assert!(strength("Xk3mQ9") < strength("Xk3mQ9vT2wLp7Zr"));
    assert!(strength("tr0ub4dX!") <= Strength::Good);
}

// --- generator ---

struct Scripted(Vec<u32>);
impl RngCore for Scripted {
    fn next_u32(&mut self) -> u32 {
        self.0.remove(0)
    }
    fn next_u64(&mut self) -> u64 {
        u64::from(self.next_u32())
    }
    fn fill_bytes(&mut self, dest: &mut [u8]) {
        dest.iter_mut().for_each(|b| *b = self.next_u32() as u8);
    }
    fn try_fill_bytes(&mut self, dest: &mut [u8]) -> Result<(), Error> {
        self.fill_bytes(dest);
        Ok(())
    }
}

#[test]
fn rejection_discards_the_biased_tail() {
    // 2^32 mod 62 is 4: the four values 4294967292..=4294967295 would favour the first four symbols if they were used.
    let mut rng = Scripted(vec![u32::MAX, u32::MAX - 3, 5]);
    assert_eq!(uniform_below(&mut rng, 62), 5);
    let mut rng = Scripted(vec![4_294_967_291]);
    assert_eq!(uniform_below(&mut rng, 62), (4_294_967_291u64 % 62) as usize, "the last accepted value is used");
}

#[test]
fn generator_has_no_modulo_bias() {
    let opts = GenOpts { length: 62, lower: true, upper: true, digits: true, symbols: false };
    let mut rng = StdRng::seed_from_u64(0x00fe_edf0);
    let mut counts = std::collections::HashMap::new();
    for _ in 0..1_613 {
        for c in generate(&mut rng, &opts).unwrap().chars() {
            *counts.entry(c).or_insert(0u32) += 1;
        }
    }
    // 100 006 draws over 62 symbols. The 0.1 % critical value of chi-square with 61 degrees of freedom is 100.9.
    let total: u32 = counts.values().sum();
    assert_eq!(counts.len(), 62, "every symbol of the alphabet shows up");
    let expected = f64::from(total) / 62.0;
    let chi2: f64 = counts.values().map(|&c| (f64::from(c) - expected).powi(2) / expected).sum();
    assert!(chi2 < 100.9, "chi-square {chi2:.1} over {total} draws");
}

fn classes(s: &str) -> (bool, bool, bool, bool) {
    (
        s.chars().any(|c| c.is_ascii_lowercase()),
        s.chars().any(|c| c.is_ascii_uppercase()),
        s.chars().any(|c| c.is_ascii_digit()),
        s.chars().any(|c| c.is_ascii_punctuation()),
    )
}

#[test]
fn generator_honours_length_and_classes() {
    let mut rng = StdRng::seed_from_u64(7);
    for length in [4usize, 5, 12, 20, 64, 128] {
        for _ in 0..200 {
            let all = GenOpts { length, lower: true, upper: true, digits: true, symbols: true };
            let p = generate(&mut rng, &all).unwrap();
            assert_eq!(p.chars().count(), length);
            assert_eq!(classes(&p), (true, true, true, true), "{p}");
        }
    }
    let digits = GenOpts { length: 30, lower: false, upper: false, digits: true, symbols: false };
    assert!(generate(&mut rng, &digits).unwrap().chars().all(|c| c.is_ascii_digit()));
    let no_symbols = GenOpts { length: 40, lower: true, upper: true, digits: true, symbols: false };
    assert!(!classes(&generate(&mut rng, &no_symbols).unwrap()).3);
}

#[test]
fn the_generator_refuses_impossible_options() {
    let mut rng = StdRng::seed_from_u64(1);
    let none = GenOpts { length: 12, lower: false, upper: false, digits: false, symbols: false };
    assert_eq!(generate(&mut rng, &none).err(), Some(VaultError::Corrupt));
    let too_short = GenOpts { length: 3, lower: true, upper: true, digits: true, symbols: true };
    assert_eq!(generate(&mut rng, &too_short).err(), Some(VaultError::Corrupt));
    let too_long = GenOpts { length: 1_025, lower: true, upper: false, digits: false, symbols: false };
    assert_eq!(generate(&mut rng, &too_long).err(), Some(VaultError::TooLarge));
}

#[test]
fn two_generated_passwords_differ() {
    let opts = GenOpts { length: 20, lower: true, upper: true, digits: true, symbols: true };
    let mut rng = StdRng::seed_from_u64(99);
    assert_ne!(generate(&mut rng, &opts).unwrap(), generate(&mut rng, &opts).unwrap());
}

#[test]
fn seven_characters_are_too_short_and_eight_are_not() {
    let v = vault_of(&["Xk3mQ9v", "Xk3mQ9vT"]);
    let weak = checkup(&v).weak;
    assert_eq!(weak, vec![([1; 16], WeakReason::TooShort)]);
}

#[test]
fn the_strength_steps_sit_where_the_estimate_puts_them() {
    // All letters distinct and lower case: bits = n * log2(26) = n * 4.70.
    assert_eq!(strength("xkqzvjwf"), Strength::Weak); // 8 -> 37.6
    assert_eq!(strength("xkqzvjwfm"), Strength::Fair); // 9 -> 42.3
    assert_eq!(strength("xkqzvjwfmhtp"), Strength::Fair); // 12 -> 56.4
    assert_eq!(strength("xkqzvjwfmhtpl"), Strength::Good); // 13 -> 61.1
    assert_eq!(strength("xkqzvjwfmhtplcdbg"), Strength::Good); // 17 -> 79.9
    assert_eq!(strength("xkqzvjwfmhtplcdbgn"), Strength::Strong); // 18 -> 84.6
    // Repeated characters count for half: 9 distinct + 9 repeats = 13.5 characters.
    assert_eq!(strength("xkqzvjwfmxkqzvjwfk"), Strength::Good);
}

#[test]
fn every_character_class_widens_the_pool() {
    // Nine distinct characters: 26 -> 42.3 (Fair), 26+26+10+33 = 95 -> 59.1 (Fair), 4 classes of 9 > 40 in all cases;
    // eight distinct characters: lower only 37.6 (Weak) but all four classes 52.6 (Fair).
    assert_eq!(strength("xkqzvjwf"), Strength::Weak);
    assert_eq!(strength("xKq3vj!f"), Strength::Fair);
    // Non-ASCII adds its own pool of 100: eight distinct characters with one of them is 8 * log2(126) = 55.8.
    assert_eq!(strength("xkqzvj\u{e9}f"), Strength::Fair);
}

#[test]
fn a_password_of_exactly_1024_characters_can_be_generated() {
    let mut rng = StdRng::seed_from_u64(5);
    let opts = GenOpts { length: 1_024, lower: true, upper: true, digits: true, symbols: true };
    assert_eq!(generate(&mut rng, &opts).unwrap().len(), 1_024);
}

#[test]
fn keyboard_rows_are_patterns_either_way_round() {
    let v = vault_of(&["wertyuiop", "sdfghjkl", "poiuytrewq", "zxcvb7nm.q"]);
    let weak = checkup(&v).weak;
    assert_eq!(weak[..3], [([1; 16], WeakReason::Pattern), ([2; 16], WeakReason::Pattern), ([3; 16], WeakReason::Pattern)]);
    assert!(!weak.iter().any(|(id, _)| *id == [4; 16]), "a row with other characters mixed in is not a stretch of a row");
}

#[test]
fn groups_and_ids_come_back_sorted() {
    // The groups leave a HashMap in a random order: with two groups an unsorted result matches half the runs, with
    // eight one run in 8!. The ids go in ascending order within a group and the groups in ascending order of their first id.
    let mut v = Vault::new();
    for k in (0..8u8).rev() {
        let password = format!("shared-pass-{k}-Xq!7");
        v.add(entry(2 * k + 5, &password)).unwrap();
        v.add(entry(2 * k + 2, &password)).unwrap();
    }
    let want: Vec<Vec<[u8; 16]>> = (0..8u8).map(|k| vec![[2 * k + 2; 16], [2 * k + 5; 16]]).collect();
    assert_eq!(checkup(&v).reused, want);
}

#[test]
fn every_character_class_is_counted() {
    // Twelve distinct characters each: 12 * log2(pool) must land on the same side of the steps as the pool says.
    assert_eq!(strength("xkqzvjwfmhtp"), Strength::Fair); // lower only: 26 -> 56.4
    assert_eq!(strength("xKqzVjwFmhTp"), Strength::Good); // + upper: 52 -> 68.4
    assert_eq!(strength("xkqzvjwfm3h7"), Strength::Good); // + digits: 36 -> 62.0
    assert_eq!(strength("xkqzvjwfm!h#"), Strength::Good); // + symbols: 59 -> 70.6
    assert_eq!(strength("xkqzvjwfmht\u{e9}"), Strength::Strong); // + non-ASCII: 126 -> 83.7
}
