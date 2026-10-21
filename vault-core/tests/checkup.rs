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
