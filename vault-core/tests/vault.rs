// SPDX-License-Identifier: Apache-2.0
use std::time::Instant;
use vault_core::envelope::{open, seal};
use vault_core::error::VaultError;
use vault_core::keys::VaultKey;
use vault_core::secret::SecretString;
use vault_core::vault::{Entry, Vault, MAX_FIELD, VAULT_AAD};

fn id(n: u32) -> [u8; 16] {
    let mut i = [0u8; 16];
    i[..4].copy_from_slice(&n.to_le_bytes());
    i
}

fn entry(n: u32, title: &str, username: &str, password: &str) -> Entry {
    Entry {
        id: id(n),
        title: title.to_string(),
        domains: vec![format!("site{n}.example")],
        packages: vec![],
        username: username.to_string(),
        password: SecretString::new(password),
        notes: String::new(),
        created_ms: 1_000,
        edited_ms: 1_000,
        last_filled_ms: None,
        history: vec![],
    }
}

fn plaintext_of(vault: &Vault, vk: &VaultKey) -> Vec<u8> {
    open(vk.as_bytes(), VAULT_AAD, &vault.to_sealed(vk)).unwrap()
}

#[test]
fn edit_pushes_old_password_into_history() {
    let mut v = Vault::new();
    v.add(entry(1, "Mail", "me", "one")).unwrap();
    let found = v
        .edit(&id(1), |e| {
            e.password = SecretString::new("two");
            e.edited_ms = 5_000;
        })
        .unwrap();
    assert!(found);
    let e = v.get(&id(1)).unwrap();
    assert_eq!(e.password.expose(), "two");
    assert_eq!(e.history.len(), 1);
    assert_eq!(e.history[0].0.expose(), "one");
    assert_eq!(e.history[0].1, 5_000);
}

#[test]
fn editing_something_else_leaves_history_alone() {
    let mut v = Vault::new();
    v.add(entry(1, "Mail", "me", "one")).unwrap();
    v.edit(&id(1), |e| e.title = "Mail 2".into()).unwrap();
    assert!(v.get(&id(1)).unwrap().history.is_empty());
}

#[test]
fn history_is_capped_at_ten_newest_first() {
    let mut v = Vault::new();
    v.add(entry(1, "Mail", "me", "p0")).unwrap();
    for n in 1..=12 {
        v.edit(&id(1), |e| {
            e.password = SecretString::new(format!("p{n}"));
            e.edited_ms = 2_000 + n;
        })
        .unwrap();
    }
    let e = v.get(&id(1)).unwrap();
    assert_eq!(e.history.len(), 10);
    assert_eq!(e.history[0].0.expose(), "p11");
    assert_eq!(e.history[9].0.expose(), "p2");
}

#[test]
fn editing_a_missing_entry_reports_false() {
    let mut v = Vault::new();
    assert_eq!(v.edit(&id(9), |_| {}), Ok(false));
}

#[test]
fn search_matches_notes_and_ignores_case() {
    let mut v = Vault::new();
    let mut e = entry(1, "Hetzner", "Beau", "pw-secret");
    e.notes = "Customer number KDNR-4711".into();
    v.add(e).unwrap();
    v.add(entry(2, "Bank", "someone", "other")).unwrap();
    assert_eq!(v.search("kdnr-4711").len(), 1);
    assert_eq!(v.search("HETZNER").len(), 1);
    assert_eq!(v.search("beau").len(), 1);
    assert_eq!(v.search("site2.EXAMPLE").len(), 1);
    assert!(v.search("pw-secret").is_empty(), "the password must never be searchable");
    assert_eq!(v.search("").len(), 2);
}

#[test]
fn delete_removes_and_reports() {
    let mut v = Vault::new();
    v.add(entry(1, "A", "a", "a")).unwrap();
    assert!(v.delete(&id(1)));
    assert!(!v.delete(&id(1)));
    assert_eq!(v.len(), 0);
}

#[test]
fn mark_filled_sets_the_time() {
    let mut v = Vault::new();
    v.add(entry(1, "A", "a", "a")).unwrap();
    v.mark_filled(&id(1), 77);
    assert_eq!(v.get(&id(1)).unwrap().last_filled_ms, Some(77));
}

#[test]
fn adding_an_existing_id_replaces_it() {
    let mut v = Vault::new();
    v.add(entry(1, "Old", "a", "a")).unwrap();
    v.add(entry(1, "New", "a", "a")).unwrap();
    assert_eq!(v.len(), 1);
    assert_eq!(v.get(&id(1)).unwrap().title, "New");
}

#[test]
fn entry_with_empty_username_emoji_and_rtl_title_roundtrips() {
    let vk = VaultKey::generate();
    let mut v = Vault::new();
    let mut e = entry(1, "\u{645}\u{631}\u{62d}\u{628}\u{627} \u{1f510} \u{202e}x", "", "p\u{e4}ss\u{1f511}");
    e.notes = "line one\nline two\u{0}with a NUL".into();
    e.last_filled_ms = Some(42);
    e.history = vec![(SecretString::new("older"), 900)];
    v.add(e.clone()).unwrap();
    let back = Vault::from_sealed(&v.to_sealed(&vk), &vk).unwrap();
    assert_eq!(back.len(), 1);
    assert_eq!(back.get(&id(1)).unwrap(), &e);
}

#[test]
fn a_vault_opens_only_with_its_own_key() {
    let (a, b) = (VaultKey::generate(), VaultKey::generate());
    let blob = Vault::new().to_sealed(&a);
    assert_eq!(Vault::from_sealed(&blob, &b).err(), Some(VaultError::BadCredentials));
}

#[test]
fn a_field_over_the_limit_is_refused_and_changes_nothing() {
    let mut v = Vault::new();
    let mut big = entry(1, "A", "a", "a");
    big.notes = "x".repeat(MAX_FIELD + 1);
    assert_eq!(v.add(big).err(), Some(VaultError::TooLarge));
    assert_eq!(v.len(), 0);

    v.add(entry(2, "B", "b", "b")).unwrap();
    let r = v.edit(&id(2), |e| e.notes = "x".repeat(MAX_FIELD + 1));
    assert_eq!(r, Err(VaultError::TooLarge));
    assert!(v.get(&id(2)).unwrap().notes.is_empty(), "a refused edit must leave the entry as it was");
    // A field of exactly the limit is fine and survives a roundtrip.
    v.edit(&id(2), |e| e.notes = "x".repeat(MAX_FIELD)).unwrap();
    let vk = VaultKey::generate();
    assert!(Vault::from_sealed(&v.to_sealed(&vk), &vk).is_ok());
}

#[test]
fn five_thousand_entries_open_and_search_within_one_second() {
    let vk = VaultKey::generate();
    let mut v = Vault::new();
    for n in 0..5_000 {
        let mut e = entry(n, &format!("Site number {n}"), &format!("user{n}@example.org"), &format!("pw-{n}-xxxxxxxxxxxx"));
        e.notes = "some notes about this account ".repeat(4);
        v.add(e).unwrap();
    }
    let blob = v.to_sealed(&vk);
    let start = Instant::now();
    let back = Vault::from_sealed(&blob, &vk).unwrap();
    let hits = back.search("number 4999").len();
    let took = start.elapsed();
    assert_eq!(back.len(), 5_000);
    assert_eq!(hits, 1);
    assert!(took.as_millis() < 1_000, "open + search took {took:?}");
    eprintln!("5000 entries: open + search {took:?} (debug build on the host)");
}

// --- hostile input: a vault file is untrusted until it has been parsed ---

fn reseal(vk: &VaultKey, plain: &[u8]) -> Vec<u8> {
    seal(vk.as_bytes(), VAULT_AAD, plain)
}

#[test]
fn claimed_four_billion_entries_is_refused_fast() {
    let vk = VaultKey::generate();
    let mut plain = vec![1u8]; // schema 1
    plain.extend_from_slice(&u32::MAX.to_le_bytes());
    let start = Instant::now();
    let r = Vault::from_sealed(&reseal(&vk, &plain), &vk);
    assert!(matches!(r, Err(VaultError::TooLarge) | Err(VaultError::Corrupt)));
    assert!(start.elapsed().as_millis() < 100);
}

#[test]
fn a_count_larger_than_the_remaining_bytes_is_corrupt() {
    let vk = VaultKey::generate();
    let mut plain = vec![1u8];
    plain.extend_from_slice(&50_000u32.to_le_bytes()); // under the entry limit, but no bytes follow
    assert_eq!(Vault::from_sealed(&reseal(&vk, &plain), &vk).err(), Some(VaultError::Corrupt));
}

#[test]
fn every_truncation_of_the_plaintext_is_refused_without_panic() {
    let vk = VaultKey::generate();
    let mut v = Vault::new();
    v.add(entry(1, "Mail", "me", "one")).unwrap();
    v.edit(&id(1), |e| e.password = SecretString::new("two")).unwrap();
    let plain = plaintext_of(&v, &vk);
    for len in 0..plain.len() {
        assert!(Vault::from_sealed(&reseal(&vk, &plain[..len]), &vk).is_err(), "cut to {len} bytes opened");
    }
}

#[test]
fn trailing_bytes_are_corrupt() {
    let vk = VaultKey::generate();
    let mut plain = plaintext_of(&Vault::new(), &vk);
    plain.push(0);
    assert_eq!(Vault::from_sealed(&reseal(&vk, &plain), &vk).err(), Some(VaultError::Corrupt));
}

#[test]
fn an_unknown_schema_is_unsupported() {
    let vk = VaultKey::generate();
    let mut plain = plaintext_of(&Vault::new(), &vk);
    plain[0] = 2;
    assert_eq!(Vault::from_sealed(&reseal(&vk, &plain), &vk).err(), Some(VaultError::UnsupportedVersion(2)));
}

#[test]
fn invalid_utf8_in_a_title_is_corrupt() {
    let vk = VaultKey::generate();
    let mut v = Vault::new();
    v.add(entry(1, "AAAA", "me", "one")).unwrap();
    let mut plain = plaintext_of(&v, &vk);
    let pos = plain.windows(4).position(|w| w == b"AAAA").unwrap();
    plain[pos] = 0xff;
    assert_eq!(Vault::from_sealed(&reseal(&vk, &plain), &vk).err(), Some(VaultError::Corrupt));
}

#[test]
fn a_field_length_beyond_the_limit_is_refused() {
    let vk = VaultKey::generate();
    let mut v = Vault::new();
    v.add(entry(1, "T", "me", "one")).unwrap();
    let mut plain = plaintext_of(&v, &vk);
    // schema 1 | count 4 | id 16 | title length 4 ...
    plain[1 + 4 + 16..1 + 4 + 16 + 4].copy_from_slice(&u32::MAX.to_le_bytes());
    assert!(matches!(
        Vault::from_sealed(&reseal(&vk, &plain), &vk).err(),
        Some(VaultError::TooLarge) | Some(VaultError::Corrupt)
    ));
}

// --- limits that the mutation run found untested ---

fn maximal(n: u32, notes_len: usize) -> Entry {
    let big = "x".repeat(MAX_FIELD);
    let mut e = entry(n, &big, &big, &big);
    e.notes = "x".repeat(notes_len);
    e.domains.clear();
    e
}

#[test]
fn a_vault_filled_to_the_exact_limit_still_opens_and_can_be_marked_filled() {
    let vk = VaultKey::generate();
    let mut v = Vault::new();
    let mut n = 0u32;
    while v.add(maximal(n, MAX_FIELD)).is_ok() {
        n += 1;
    }
    assert!(n > 200, "only {n} maximal entries fit");
    // Squeeze one more entry in, with notes as long as the room allows: the vault is then full to the byte.
    let mut probe = Vault::new();
    probe.add(maximal(0, 0)).unwrap();
    let room = vault_core::vault::MAX_PLAIN - v.plain_size();
    let fixed = probe.plain_size() - 5; // the entry without notes
    v.add(maximal(n, room - fixed)).unwrap();
    assert_eq!(v.plain_size(), vault_core::vault::MAX_PLAIN, "the vault must be full to the byte");
    assert!(v.add(maximal(n + 1, 1)).is_err(), "the vault is not full yet");
    // A fill adds eight bytes per entry: the file must still be readable after every one of them.
    for i in 0..=n {
        v.mark_filled(&id(i), 1);
    }
    let blob = v.to_sealed(&vk);
    let back = Vault::from_sealed(&blob, &vk).expect("a full vault must reopen");
    assert_eq!(back.len(), n as usize + 1);
    // Room comes back when an entry goes.
    assert!(v.delete(&id(0)));
    v.add(maximal(1_000_000, 100)).unwrap();
}

#[test]
fn lists_are_limited_to_256() {
    let mut v = Vault::new();
    let mut e = entry(1, "A", "a", "a");
    e.domains = (0..256).map(|i| format!("d{i}.example")).collect();
    e.packages = (0..256).map(|i| format!("p.{i}")).collect();
    v.add(e.clone()).unwrap();
    let vk = VaultKey::generate();
    assert_eq!(Vault::from_sealed(&v.to_sealed(&vk), &vk).unwrap().get(&id(1)).unwrap().domains.len(), 256);
    let mut too_many = e.clone();
    too_many.domains.push("one.more".into());
    assert_eq!(v.add(too_many).err(), Some(VaultError::TooLarge));
    let mut too_many = e;
    too_many.packages.push("one.more".into());
    assert_eq!(v.add(too_many).err(), Some(VaultError::TooLarge));
}

#[test]
fn an_entry_of_nothing_roundtrips() {
    let vk = VaultKey::generate();
    let mut v = Vault::new();
    let mut e = entry(1, "", "", "");
    e.domains.clear();
    v.add(e.clone()).unwrap();
    let back = Vault::from_sealed(&v.to_sealed(&vk), &vk).unwrap();
    assert_eq!(back.get(&id(1)).unwrap(), &e);
}

#[test]
fn debug_output_never_shows_a_password() {
    let mut e = entry(1, "Mail", "me", "hunter2-very-secret");
    e.history = vec![(SecretString::new("older-secret"), 1)];
    let shown = format!("{e:?} {:?}", e.password);
    assert!(!shown.contains("hunter2") && !shown.contains("older-secret"), "{shown}");
}

#[test]
fn a_secret_compares_by_value() {
    assert_eq!(SecretString::new("abc"), SecretString::new("abc"));
    assert_ne!(SecretString::new("abc"), SecretString::new("abd"));
    assert_ne!(SecretString::new("abc"), SecretString::new("abcd"));
    assert_ne!(SecretString::new(""), SecretString::new("a"));
}

#[test]
fn the_size_bookkeeping_matches_the_real_plaintext() {
    let vk = VaultKey::generate();
    let mut v = Vault::new();
    let real = |v: &Vault| plaintext_of(v, &vk).len();
    assert_eq!(v.plain_size(), real(&v));
    v.add(entry(1, "A", "a", "one")).unwrap();
    v.add(entry(2, "BB", "bb", "two")).unwrap();
    assert_eq!(v.plain_size(), real(&v), "after add");
    v.add(entry(2, "A much longer title for the same id", "bb", "two")).unwrap();
    assert_eq!(v.plain_size(), real(&v), "after a replace");
    v.edit(&id(1), |e| {
        e.password = SecretString::new("a different and longer password");
        e.notes = "notes".into();
    })
    .unwrap();
    assert_eq!(v.plain_size(), real(&v), "after an edit that grows the history");
    v.mark_filled(&id(1), 5);
    v.mark_filled(&id(1), 6);
    v.mark_filled(&id(2), 7);
    assert_eq!(v.plain_size(), real(&v), "after fills (eight bytes once per entry)");
    assert!(v.delete(&id(2)));
    assert_eq!(v.plain_size(), real(&v), "after a delete");
}

#[test]
fn replacing_an_entry_over_and_over_does_not_use_up_the_room() {
    let mut v = Vault::new();
    let big = "x".repeat(MAX_FIELD);
    for _ in 0..300 {
        let mut e = entry(1, &big, &big, &big);
        e.notes = big.clone();
        v.add(e).expect("a replace must release the room of the entry it replaces");
    }
    assert_eq!(v.len(), 1);
}

#[test]
fn an_edit_cannot_change_the_id() {
    let mut v = Vault::new();
    v.add(entry(1, "A", "a", "a")).unwrap();
    v.edit(&id(1), |e| e.id = id(9)).unwrap();
    assert!(v.get(&id(1)).is_some());
    assert!(v.get(&id(9)).is_none());
}

#[test]
fn two_entries_with_one_id_in_a_file_are_corrupt() {
    let vk = VaultKey::generate();
    let mut v = Vault::new();
    v.add(entry(1, "A", "a", "a")).unwrap();
    v.add(entry(2, "B", "b", "b")).unwrap();
    let mut plain = plaintext_of(&v, &vk);
    let two = id(2);
    let at = plain.windows(16).position(|w| w == two).unwrap();
    plain[at..at + 16].copy_from_slice(&id(1));
    assert_eq!(Vault::from_sealed(&reseal(&vk, &plain), &vk).err(), Some(VaultError::Corrupt));
}
