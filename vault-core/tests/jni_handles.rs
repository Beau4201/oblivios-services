// SPDX-License-Identifier: Apache-2.0
//! The safe layer under the JNI glue (`api`): handles, codes, and the whole life of a vault file.
use std::collections::HashSet;
use std::time::Instant;
use vault_core::api::{self, Code};
use vault_core::error::VaultError;
use vault_core::kdf::KdfParams;
use vault_core::secret::SecretString;
use vault_core::vault::Entry;

const FAST: KdfParams = KdfParams { m_kib: 64, t: 1, p: 1 };
const HW: [u8; 32] = [0x77; 32];
const PASS: &[u8] = b"tundra-wicker-48";

fn entry(n: u8, title: &str, password: &str) -> Entry {
    Entry {
        id: [n; 16],
        title: title.into(),
        domains: vec![format!("site{n}.example")],
        packages: vec![],
        username: format!("user{n}"),
        password: SecretString::new(password),
        notes: "note".into(),
        created_ms: 10,
        edited_ms: 20,
        last_filled_ms: None,
        history: vec![],
    }
}

fn wire(e: &Entry) -> Vec<u8> {
    api::encode_entry(e).to_vec()
}

fn new_vault() -> (Vec<u8>, [u8; 32]) {
    let c = api::create_with_params(PASS, &HW, &FAST).unwrap();
    (c.file.clone(), *c.vault_key)
}

fn open_file(file: &[u8]) -> u64 {
    api::unlock_with_passphrase(file, PASS, &HW).unwrap()
}

/// Reads the count of a list or search reply and the titles in it (the wire form is the spec of this parser).
fn titles(reply: &[u8]) -> Vec<String> {
    let mut at = 0;
    let u32_at = |at: &mut usize| {
        let v = u32::from_le_bytes(reply[*at..*at + 4].try_into().unwrap()) as usize;
        *at += 4;
        v
    };
    let str_at = |at: &mut usize| {
        let n = u32::from_le_bytes(reply[*at..*at + 4].try_into().unwrap()) as usize;
        *at += 4;
        let s = String::from_utf8(reply[*at..*at + n].to_vec()).unwrap();
        *at += n;
        s
    };
    let count = u32_at(&mut at);
    let mut out = Vec::new();
    for _ in 0..count {
        at += 16; // id
        out.push(str_at(&mut at));
        let _username = str_at(&mut at);
        for _ in 0..2 {
            for _ in 0..u32_at(&mut at) {
                str_at(&mut at);
            }
        }
        at += 8; // edited_ms
        at += if reply[at] == 1 { 9 } else { 1 };
    }
    assert_eq!(at, reply.len(), "the reply holds exactly the summaries");
    out
}

#[test]
fn a_locked_handle_is_dead() {
    let (file, _) = new_vault();
    let h = open_file(&file);
    assert_eq!(api::lock(h), Code::Ok);
    let id = [1u8; 16];
    let e = wire(&entry(1, "t", "p"));
    assert_eq!(api::list(h).err(), Some(Code::DeadHandle));
    assert_eq!(api::vault_key(h).err(), Some(Code::DeadHandle));
    assert_eq!(api::search(h, "x").err(), Some(Code::DeadHandle));
    assert_eq!(api::get(h, &id).err(), Some(Code::DeadHandle));
    assert_eq!(api::add_or_edit(h, &e).err(), Some(Code::DeadHandle));
    assert_eq!(api::delete(h, &id).err(), Some(Code::DeadHandle));
    assert_eq!(api::mark_filled(h, &id, 1).err(), Some(Code::DeadHandle));
    assert_eq!(api::checkup(h).err(), Some(Code::DeadHandle));
    assert_eq!(api::export_with_params(h, b"x", &FAST).err(), Some(Code::DeadHandle));
    assert_eq!(api::import_entries(h, b"x", b"x").err(), Some(Code::DeadHandle));
    assert_eq!(api::index_build(h, &[0; 32]).err(), Some(Code::DeadHandle));
    assert_eq!(api::lock(h), Code::Ok, "locking twice is not an error");
}

#[test]
fn handles_that_were_never_issued_are_dead() {
    for h in [0u64, 1, 2, u64::MAX, 1 << 63] {
        assert_eq!(api::list(h).err(), Some(Code::DeadHandle), "{h}");
    }
}

#[test]
fn handles_are_not_guessable_or_reused() {
    let (file, vk) = new_vault();
    let mut seen = HashSet::new();
    let mut handles = Vec::new();
    for _ in 0..1_000 {
        let h = api::unlock_with_vault_key(&file, &vk).unwrap();
        assert!(seen.insert(h), "handle {h} was issued twice");
        assert_ne!(h, 0);
        assert!(h <= i64::MAX as u64, "a handle must fit a positive Java long, or it reads as an error");
        handles.push(h);
        assert_eq!(api::lock(h), Code::Ok);
    }
    // Not a counter: the steps between handles differ.
    let steps: HashSet<i128> = handles.windows(2).map(|w| i128::from(w[1]) - i128::from(w[0])).collect();
    assert!(steps.len() > 900, "handles look sequential");
    // Dead after the lock, whatever the order.
    assert!(handles.iter().all(|&h| api::list(h).is_err()));
}

#[test]
fn every_error_maps_to_a_stable_code() {
    let table = [
        (Code::Ok, 0u8),
        (Code::from(VaultError::BadCredentials), 1),
        (Code::from(VaultError::Corrupt), 2),
        (Code::from(VaultError::UnsupportedVersion(9)), 3),
        (Code::from(VaultError::TooLarge), 4),
        (Code::from(VaultError::Io), 5),
        (Code::DeadHandle, 6),
        (Code::BadArgument, 7),
    ];
    for (code, number) in table {
        assert_eq!(code.code(), number, "{code:?}");
    }
    let distinct: HashSet<u8> = table.iter().map(|(c, _)| c.code()).collect();
    assert_eq!(distinct.len(), table.len());
}

#[test]
fn create_unlock_edit_and_reopen() {
    let (file, _) = new_vault();
    let h = open_file(&file);
    assert_eq!(titles(&api::list(h).unwrap()), Vec::<String>::new());

    let file2 = api::add_or_edit(h, &wire(&entry(1, "Hetzner", "pw-one"))).unwrap();
    let file3 = api::add_or_edit(h, &wire(&entry(2, "Bank", "pw-two"))).unwrap();
    assert_ne!(file2, file3);
    assert_eq!(api::lock(h), Code::Ok);

    // The file the last call returned holds both entries, and the passphrase opens it.
    let h2 = open_file(&file3);
    let mut t = titles(&api::list(h2).unwrap());
    t.sort();
    assert_eq!(t, ["Bank", "Hetzner"]);
    assert_eq!(titles(&api::search(h2, "HETZ").unwrap()), ["Hetzner"]);
    assert_eq!(titles(&api::search(h2, "pw-one").unwrap()), Vec::<String>::new(), "no password search");
    let got = api::decode_entry(&api::get(h2, &[1; 16]).unwrap()).unwrap();
    assert_eq!(got.password.expose(), "pw-one");

    // An older file does not hold the later entry.
    let h_old = open_file(&file2);
    assert_eq!(titles(&api::list(h_old).unwrap()), ["Hetzner"]);
}

#[test]
fn the_vault_key_opens_the_same_file() {
    let (file, vk) = new_vault();
    let h = api::unlock_with_vault_key(&file, &vk).unwrap();
    let file2 = api::add_or_edit(h, &wire(&entry(1, "A", "p"))).unwrap();
    let h2 = api::unlock_with_vault_key(&file2, &vk).unwrap();
    assert_eq!(titles(&api::list(h2).unwrap()), ["A"]);
    assert_eq!(api::unlock_with_vault_key(&file2, &[9; 32]).err(), Some(Code::BadCredentials));
    assert_eq!(api::unlock_with_vault_key(&file2, &vk[..31]).err(), Some(Code::BadArgument));
}

#[test]
fn wrong_credentials_and_bad_arguments() {
    let (file, _) = new_vault();
    assert_eq!(api::unlock_with_passphrase(&file, b"nope", &HW).err(), Some(Code::BadCredentials));
    assert_eq!(api::unlock_with_passphrase(&file, PASS, &[1; 32]).err(), Some(Code::BadCredentials));
    assert_eq!(api::unlock_with_passphrase(&file, PASS, &HW[..31]).err(), Some(Code::BadArgument));
    assert_eq!(api::create_with_params(b"", &HW, &FAST).err(), Some(Code::BadArgument), "empty passphrase");
    assert_eq!(api::create_with_params(PASS, &HW[..5], &FAST).err(), Some(Code::BadArgument));
    let h = open_file(&file);
    assert_eq!(api::get(h, &[1; 15]).err(), Some(Code::BadArgument));
    assert_eq!(api::get(h, &[1; 16]).err(), Some(Code::BadArgument), "no such entry");
    assert_eq!(api::add_or_edit(h, b"garbage").err(), Some(Code::Corrupt));
}

#[test]
fn hostile_files_are_refused_without_panic() {
    let (file, vk) = new_vault();
    for len in 0..file.len() {
        assert!(api::unlock_with_passphrase(&file[..len], PASS, &HW).is_err(), "cut to {len}");
        assert!(api::unlock_with_vault_key(&file[..len], &vk).is_err(), "cut to {len}");
    }
    let mut flipped = file.clone();
    for i in 0..flipped.len() {
        flipped[i] ^= 1;
        // Through the passphrase path: the vault-key path never reads the wrapper, so a changed wrapper byte is
        // (rightly) invisible there.
        assert!(api::unlock_with_passphrase(&flipped, PASS, &HW).is_err(), "byte {i}");
        flipped[i] ^= 1;
    }
    let mut huge = file.clone();
    huge[5..9].copy_from_slice(&4_000_000u32.to_le_bytes()); // m_kib
    let start = Instant::now();
    assert_eq!(api::unlock_with_passphrase(&huge, PASS, &HW).err(), Some(Code::TooLarge));
    assert!(start.elapsed().as_millis() < 50);
    assert_eq!(api::unlock_with_passphrase(&vec![0u8; 200 * 1024 * 1024], PASS, &HW).err(), Some(Code::TooLarge));
}

#[test]
fn editing_through_the_api_keeps_history_and_the_creation_time() {
    let (file, _) = new_vault();
    let h = open_file(&file);
    api::add_or_edit(h, &wire(&entry(1, "A", "first"))).unwrap();
    let mut changed = entry(1, "A renamed", "second");
    changed.created_ms = 999; // a client cannot rewrite when the entry was created
    changed.edited_ms = 500;
    changed.domains = vec!["new.example".into()];
    changed.packages = vec!["org.example.new".into()];
    changed.username = "new-user".into();
    changed.notes = "new note".into();
    api::add_or_edit(h, &wire(&changed)).unwrap();
    let got = api::decode_entry(&api::get(h, &[1; 16]).unwrap()).unwrap();
    assert_eq!(got.title, "A renamed");
    assert_eq!(got.domains, ["new.example"]);
    assert_eq!(got.packages, ["org.example.new"]);
    assert_eq!(got.username, "new-user");
    assert_eq!(got.notes, "new note");
    assert_eq!(got.edited_ms, 500);
    assert_eq!(got.password.expose(), "second");
    assert_eq!(got.created_ms, 10);
    assert_eq!(got.history.len(), 1);
    assert_eq!(got.history[0].0.expose(), "first");
}

#[test]
fn delete_and_mark_filled_return_the_new_file() {
    let (file, _) = new_vault();
    let h = open_file(&file);
    api::add_or_edit(h, &wire(&entry(1, "A", "p"))).unwrap();
    let f = api::mark_filled(h, &[1; 16], 4242).unwrap();
    let h2 = open_file(&f);
    assert_eq!(api::decode_entry(&api::get(h2, &[1; 16]).unwrap()).unwrap().last_filled_ms, Some(4242));
    let f = api::delete(h, &[1; 16]).unwrap();
    assert_eq!(titles(&api::list(open_file(&f)).unwrap()), Vec::<String>::new());
    assert_eq!(api::delete(h, &[1; 16]).err(), Some(Code::BadArgument), "already gone");
    assert_eq!(api::mark_filled(h, &[1; 16], 1).err(), Some(Code::BadArgument), "no such entry");
}

#[test]
fn checkup_reports_reuse_and_weakness() {
    let (file, _) = new_vault();
    let h = open_file(&file);
    api::add_or_edit(h, &wire(&entry(1, "A", "same-strong-Passw0rd-xyz!"))).unwrap();
    api::add_or_edit(h, &wire(&entry(2, "B", "same-strong-Passw0rd-xyz!"))).unwrap();
    api::add_or_edit(h, &wire(&entry(3, "C", "abc"))).unwrap();
    api::add_or_edit(h, &wire(&entry(4, "D", "password123"))).unwrap();
    api::add_or_edit(h, &wire(&entry(5, "E", "zzzzzzzzzzzz"))).unwrap();
    let r = api::checkup(h).unwrap();
    // u32 groups | (u32 n | ids)... | u32 weak | (id | reason)...
    assert_eq!(&r[..4], &1u32.to_le_bytes());
    assert_eq!(&r[4..8], &2u32.to_le_bytes());
    assert_eq!(&r[8..24], &[1u8; 16]);
    assert_eq!(&r[24..40], &[2u8; 16]);
    assert_eq!(&r[40..44], &3u32.to_le_bytes());
    assert_eq!(&r[44..60], &[3u8; 16]);
    assert_eq!(r[60], 0, "TooShort");
    assert_eq!(&r[61..77], &[4u8; 16]);
    assert_eq!(r[77], 1, "Common");
    assert_eq!(&r[78..94], &[5u8; 16]);
    assert_eq!(r[94], 2, "Pattern");
    assert_eq!(r.len(), 95);
}

#[test]
fn export_and_import_through_the_api() {
    let (file, _) = new_vault();
    let h = open_file(&file);
    api::add_or_edit(h, &wire(&entry(1, "A", "pw-a"))).unwrap();
    api::add_or_edit(h, &wire(&entry(2, "B", "pw-b"))).unwrap();
    let exported = api::export_with_params(h, b"export-pass", &FAST).unwrap();

    let (file2, _) = new_vault();
    let h2 = open_file(&file2);
    api::add_or_edit(h2, &wire(&entry(1, "Existing", "keep-me"))).unwrap();
    let reply = api::import_entries(h2, &exported, b"export-pass").unwrap();
    assert_eq!(&reply[..4], &2u32.to_le_bytes(), "two entries imported");
    let merged = open_file(&reply[4..]);
    let mut t = titles(&api::list(merged).unwrap());
    t.sort();
    assert_eq!(t, ["A", "B", "Existing"]);
    assert_eq!(api::decode_entry(&api::get(merged, &[1; 16]).unwrap()).unwrap().password.expose(), "keep-me");

    assert_eq!(api::import_entries(h2, &exported, b"wrong").err(), Some(Code::BadCredentials));
}

#[test]
fn the_index_answers_for_the_vault() {
    let (file, _) = new_vault();
    let h = open_file(&file);
    api::add_or_edit(h, &wire(&entry(1, "A", "p"))).unwrap();
    let key = [0x3c; 32];
    let index = api::index_build(h, &key).unwrap();
    assert!(api::index_contains(&index, &key, "login.site1.example", 0));
    assert!(!api::index_contains(&index, &key, "site2.example", 0));
    assert!(!api::index_contains(&index, &[1; 32], "site1.example", 0));
    assert!(!api::index_contains(&index, &key, "site1.example", 1), "a domain is not a package");
    assert!(!api::index_contains(&index, &key, "site1.example", 7), "an unknown kind never matches");
    assert!(!api::index_contains(b"garbage", &key, "site1.example", 0));
    assert!(!api::index_contains(&index, &key[..5], "site1.example", 0));
}

#[test]
fn generator_and_strength_through_the_api() {
    let p = api::generate(20, true, true, true, true).unwrap();
    assert_eq!(String::from_utf8(p.to_vec()).unwrap().chars().count(), 20);
    assert_eq!(api::generate(0, true, true, true, true).err(), Some(Code::Corrupt));
    assert_eq!(api::generate(2_000, true, false, false, false).err(), Some(Code::TooLarge));
    assert_eq!(api::strength(b"q9Lz-tundra-wicker-48"), 3);
    assert_eq!(api::strength(b"password"), 0);
    assert_eq!(api::strength(b"xkqzvjwfm"), 1);
    assert_eq!(api::strength(b"xkqzvjwfmhtpl"), 2);
    assert_eq!(api::strength(&[0xff, 0xfe]), 0, "not UTF-8 is weak, not a crash");
}

#[test]
fn the_self_test_passes() {
    assert!(api::self_test());
}

#[test]
fn many_threads_share_the_table_without_trouble() {
    let (file, vk) = new_vault();
    let threads: Vec<_> = (0..8u8)
        .map(|t| {
            let (file, vk) = (file.clone(), vk);
            std::thread::spawn(move || {
                for i in 0..40u8 {
                    let h = api::unlock_with_vault_key(&file, &vk).unwrap();
                    api::add_or_edit(h, &wire(&entry(t.wrapping_mul(40).wrapping_add(i), "x", "p"))).unwrap();
                    assert!(api::list(h).is_ok());
                    assert_eq!(api::lock(h), Code::Ok);
                    assert!(api::list(h).is_err());
                }
            })
        })
        .collect();
    for t in threads {
        t.join().unwrap();
    }
}

#[test]
fn the_vault_key_of_an_open_session_is_the_one_made_at_creation() {
    let (file, vk) = new_vault();
    let h = open_file(&file);
    assert_eq!(*api::vault_key(h).unwrap(), vk.to_vec(), "after a passphrase unlock");
    let h2 = api::unlock_with_vault_key(&file, &vk).unwrap();
    assert_eq!(*api::vault_key(h2).unwrap(), vk.to_vec(), "after a vault-key unlock");
    assert_eq!(api::lock(h), Code::Ok);
    assert_eq!(api::vault_key(h).err(), Some(Code::DeadHandle), "a locked handle has no key to give");
    assert_eq!(api::vault_key(0).err(), Some(Code::DeadHandle));
}

// --- the wire format, pinned: the Kotlin side (passwords/app/tests/host/WireTest.kt) holds the same hex strings ---

fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

fn golden_entry() -> Entry {
    Entry {
        id: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16],
        title: "Hetzner".into(),
        domains: vec!["hetzner.com".into()],
        packages: vec!["de.hetzner.app".into()],
        username: "anna".into(),
        password: SecretString::new("pw-\u{fc}"),
        notes: "n\n".into(),
        created_ms: 1,
        edited_ms: 2,
        last_filled_ms: Some(3),
        history: vec![(SecretString::new("old"), 4)],
    }
}

const GOLDEN_ENTRY: &str = "0102030405060708090a0b0c0d0e0f10070000004865747a6e6572010000000b0000006865747a6e65722e636f6d010000000e00000064652e6865747a6e65722e61707004000000616e6e610500000070772dc3bc020000006e0a0100000000000000020000000000000001030000000000000001030000006f6c640400000000000000";
const GOLDEN_LIST: &str = "020000000102030405060708090a0b0c0d0e0f10070000004865747a6e657204000000616e6e61010000000b0000006865747a6e65722e636f6d010000000e00000064652e6865747a6e65722e617070020000000000000001030000000000000009090909090909090909090909090909040000005765616b04000000616e6e610000000000000000020000000000000000";
const GOLDEN_CHECKUP: &str = "00000000020000000102030405060708090a0b0c0d0e0f10000909090909090909090909090909090900";

#[test]
fn the_wire_format_is_pinned() {
    let e = golden_entry();
    let actual = hex(&api::encode_entry(&e));
    assert_eq!(actual, GOLDEN_ENTRY, "entry");
    assert_eq!(api::decode_entry(&api::encode_entry(&e)).unwrap(), e);

    let (file, _) = new_vault();
    let h = open_file(&file);
    api::add_or_edit(h, &api::encode_entry(&e)).unwrap();
    let mut weak = golden_entry();
    weak.id = [9; 16];
    weak.title = "Weak".into();
    weak.password = SecretString::new("abc");
    weak.last_filled_ms = None;
    weak.history = vec![];
    weak.domains = vec![];
    weak.packages = vec![];
    api::add_or_edit(h, &api::encode_entry(&weak)).unwrap();
    assert_eq!(hex(&api::list(h).unwrap()), GOLDEN_LIST, "list");
    assert_eq!(hex(&api::checkup(h).unwrap()), GOLDEN_CHECKUP, "checkup");
}

#[test]
fn matches_returns_the_accounts_of_a_site_or_an_app() {
    let (file, _) = new_vault();
    let h = open_file(&file);
    let mut a = entry(1, "GitHub", "p1");
    a.domains = vec!["github.com".into()];
    let mut b = entry(2, "GitHub work", "p2");
    b.domains = vec!["login.GitHub.com".into(), "example.org".into()];
    let mut c = entry(3, "Mail app", "p3");
    c.domains = vec![];
    c.packages = vec!["org.example.mail".into()];
    let mut d = entry(4, "Other", "p4");
    d.domains = vec!["githubb.com".into()];
    let mut blank = entry(5, "Blank package", "p5");
    blank.domains = vec![];
    blank.packages = vec![String::new()];
    for e in [&a, &b, &c, &d, &blank] {
        api::add_or_edit(h, &wire(e)).unwrap();
    }
    let mut hit = titles(&api::matches(h, "gist.github.com", 0).unwrap());
    hit.sort();
    assert_eq!(hit, ["GitHub", "GitHub work"], "any subdomain of a registrable domain, either way round");
    assert_eq!(titles(&api::matches(h, "example.org:8443", 0).unwrap()), ["GitHub work"]);
    assert_eq!(titles(&api::matches(h, "github.com.evil.example", 0).unwrap()), Vec::<String>::new(), "a look-alike");
    assert_eq!(titles(&api::matches(h, "192.0.2.1", 0).unwrap()), Vec::<String>::new());
    assert_eq!(titles(&api::matches(h, "org.example.mail", 1).unwrap()), ["Mail app"]);
    assert_eq!(titles(&api::matches(h, "org.example.mail", 0).unwrap()), Vec::<String>::new(), "a package is not a domain");
    assert_eq!(titles(&api::matches(h, "github.com", 1).unwrap()), Vec::<String>::new(), "a domain is not a package");
    assert_eq!(titles(&api::matches(h, "org.example.Mail", 1).unwrap()), Vec::<String>::new(), "packages are exact");
    assert_eq!(titles(&api::matches(h, "org.example", 1).unwrap()), Vec::<String>::new(), "not by prefix");
    assert_eq!(titles(&api::matches(h, "", 1).unwrap()), Vec::<String>::new(), "an empty package matches nothing");
    assert_eq!(api::matches(h, "github.com", 9).err(), Some(Code::BadArgument));
    assert_eq!(api::matches(0, "github.com", 0).err(), Some(Code::DeadHandle));
}
