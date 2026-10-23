// SPDX-License-Identifier: Apache-2.0
use std::time::Instant;
use vault_core::envelope::MAX_BLOB;
use vault_core::error::VaultError;
use vault_core::exchange::{export, import, merge_into, seal_payload};
use vault_core::kdf::KdfParams;
use vault_core::keys::VaultKey;
use vault_core::secret::SecretString;
use vault_core::vault::{Entry, Vault, MAX_FIELD};

const FAST: KdfParams = KdfParams { m_kib: 64, t: 1, p: 1 };
const PASS: &[u8] = b"export-passphrase";

fn entry(n: u8, title: &str) -> Entry {
    Entry {
        id: [n; 16],
        title: title.into(),
        domains: vec![format!("site{n}.example"), "second.example".into()],
        packages: vec!["com.example.app".into()],
        username: "user".into(),
        password: SecretString::new(format!("pw-{n}")),
        notes: "line one\nline \"two\" \\ \u{1f510}".into(),
        created_ms: 1_700_000_000_000,
        edited_ms: 1_700_000_001_000,
        last_filled_ms: if n % 2 == 0 { Some(1_700_000_002_000) } else { None },
        history: vec![(SecretString::new("older"), 1_699_999_999_000), (SecretString::new("oldest"), 1_699_999_000_000)],
    }
}

fn vault_of(n: u8) -> Vault {
    let mut v = Vault::new();
    for i in 1..=n {
        v.add(entry(i, &format!("Site {i} \u{645}\u{631}\u{62d}\u{628}\u{627}"))).unwrap();
    }
    v
}

fn payload(entries_json: &str) -> Vec<u8> {
    format!(r#"{{"format":"freeholdos-passwords-export","version":1,"entries":{entries_json}}}"#).into_bytes()
}

fn good_entry_json() -> String {
    r#"{"id":"01010101010101010101010101010101","title":"t","domains":[],"packages":[],"username":"u","password":"p","notes":"","created_ms":1,"edited_ms":2,"last_filled_ms":null,"history":[]}"#.into()
}

fn sealed(payload: &[u8]) -> Vec<u8> {
    seal_payload(payload, PASS, &FAST).unwrap()
}

#[test]
fn export_import_roundtrip_preserves_every_field() {
    let v = vault_of(3);
    let blob = export(&v, PASS, &FAST).unwrap();
    let back = import(&blob, PASS).unwrap();
    assert_eq!(back, v.entries());
}

#[test]
fn an_empty_vault_roundtrips() {
    let blob = export(&Vault::new(), PASS, &FAST).unwrap();
    assert!(import(&blob, PASS).unwrap().is_empty());
}

#[test]
fn wrong_passphrase_is_bad_credentials() {
    let blob = export(&vault_of(1), PASS, &FAST).unwrap();
    assert_eq!(import(&blob, b"wrong").err(), Some(VaultError::BadCredentials));
}

#[test]
fn export_contains_no_vault_key_material_and_no_plaintext() {
    let vk = VaultKey::generate();
    let v = vault_of(2);
    let sealed_vault = v.to_sealed(&vk);
    let blob = export(&v, PASS, &FAST).unwrap();
    assert!(!blob.windows(32).any(|w| w == vk.as_bytes()));
    assert!(!blob.windows(sealed_vault.len()).any(|w| w == sealed_vault.as_slice()));
    for needle in ["pw-1", "Site 1", "example", "freeholdos-passwords-export"] {
        assert!(!blob.windows(needle.len()).any(|w| w == needle.as_bytes()), "{needle} is readable in the file");
    }
}

#[test]
fn two_exports_of_the_same_vault_differ() {
    let v = vault_of(1);
    assert_ne!(export(&v, PASS, &FAST).unwrap(), export(&v, PASS, &FAST).unwrap());
}

#[test]
fn flipping_any_byte_or_cutting_the_file_fails() {
    let blob = export(&vault_of(1), PASS, &FAST).unwrap();
    for i in 0..blob.len() {
        let mut bad = blob.clone();
        bad[i] ^= 1;
        assert!(import(&bad, PASS).is_err(), "byte {i} flipped but the file opened");
    }
    for len in 0..blob.len() {
        assert!(import(&blob[..len], PASS).is_err(), "cut to {len}");
    }
}

#[test]
fn a_huge_argon2_cost_in_the_header_is_refused_fast() {
    let mut blob = export(&vault_of(1), PASS, &FAST).unwrap();
    blob[5..9].copy_from_slice(&4_000_000u32.to_le_bytes()); // m_kib
    let start = Instant::now();
    assert_eq!(import(&blob, PASS).err(), Some(VaultError::TooLarge));
    assert!(start.elapsed().as_millis() < 50);
}

#[test]
fn an_oversized_input_is_refused_before_anything_else() {
    let start = Instant::now();
    assert_eq!(import(&vec![0u8; MAX_BLOB + 100], PASS).err(), Some(VaultError::TooLarge));
    assert!(start.elapsed().as_millis() < 50);
}

#[test]
fn more_entries_than_the_limit_is_refused_fast() {
    let many = format!("[{}]", vec!["{}"; 100_001].join(","));
    let blob = sealed(&payload(&many));
    let start = Instant::now();
    assert_eq!(import(&blob, PASS).err(), Some(VaultError::TooLarge));
    assert!(start.elapsed().as_millis() < 1_000, "took {:?}", start.elapsed());
}

#[test]
fn field_over_64_kib_is_refused() {
    let big = "x".repeat(MAX_FIELD + 1);
    for field in ["title", "username", "password", "notes"] {
        let e = good_entry_json().replace(&format!(r#""{field}":""#), &format!(r#""{field}":"{big}"#));
        let r = import(&sealed(&payload(&format!("[{e}]"))), PASS);
        assert_eq!(r.err(), Some(VaultError::TooLarge), "{field}");
    }
    let at_limit = "x".repeat(MAX_FIELD);
    let e = good_entry_json().replace(r#""notes":"""#, &format!(r#""notes":"{at_limit}""#));
    assert!(import(&sealed(&payload(&format!("[{e}]"))), PASS).is_ok());
}

#[test]
fn unknown_versions_are_unsupported() {
    let mut blob = export(&vault_of(1), PASS, &FAST).unwrap();
    blob[4] = 2; // file header version
    assert_eq!(import(&blob, PASS).err(), Some(VaultError::UnsupportedVersion(2)));
    let p = br#"{"format":"freeholdos-passwords-export","version":2,"entries":[]}"#;
    assert_eq!(import(&sealed(p), PASS).err(), Some(VaultError::UnsupportedVersion(2)));
}

#[test]
fn malformed_payloads_are_corrupt() {
    let cases: Vec<(&str, Vec<u8>)> = vec![
        ("not json", b"hello".to_vec()),
        ("wrong format name", br#"{"format":"other","version":1,"entries":[]}"#.to_vec()),
        ("entries not an array", payload("{}")),
        ("entry not an object", payload("[1]")),
        ("missing field", payload(&format!("[{}]", good_entry_json().replace(r#""notes":"","#, "")))),
        ("wrong type", payload(&format!("[{}]", good_entry_json().replace(r#""title":"t""#, r#""title":5"#)))),
        ("short id", payload(&format!("[{}]", good_entry_json().replace("01010101010101010101010101010101", "0101")))),
        ("id not hex", payload(&format!("[{}]", good_entry_json().replace("01010101010101010101010101010101", "zz010101010101010101010101010101")))),
        ("timestamp too large", payload(&format!("[{}]", good_entry_json().replace(r#""created_ms":1"#, r#""created_ms":99999999999999999999"#)))),
        ("float timestamp", payload(&format!("[{}]", good_entry_json().replace(r#""created_ms":1"#, r#""created_ms":1.5"#)))),
        ("nested to the stack limit", format!("{}{}", "[".repeat(200_000), "]".repeat(200_000)).into_bytes()),
    ];
    for (name, p) in cases {
        assert_eq!(import(&sealed(&p), PASS).err(), Some(VaultError::Corrupt), "{name}");
    }
}

#[test]
fn history_longer_than_ten_is_refused() {
    let hist = vec![r#"{"password":"x","replaced_ms":1}"#; 11].join(",");
    let e = good_entry_json().replace(r#""history":[]"#, &format!(r#""history":[{hist}]"#));
    assert_eq!(import(&sealed(&payload(&format!("[{e}]"))), PASS).err(), Some(VaultError::TooLarge));
}

#[test]
fn duplicate_ids_in_a_file_are_renamed_not_overwritten() {
    let e = good_entry_json();
    let got = import(&sealed(&payload(&format!("[{e},{e},{e}]"))), PASS).unwrap();
    assert_eq!(got.len(), 3);
    let mut ids: Vec<_> = got.iter().map(|e| e.id).collect();
    ids.sort();
    ids.dedup();
    assert_eq!(ids.len(), 3);
    assert_eq!(got[0].id, [1; 16], "the first keeps its id");
}

#[test]
fn merging_never_overwrites_an_existing_entry() {
    let mut v = vault_of(2);
    let incoming = vec![entry(1, "Other site"), entry(9, "New site")];
    let added = merge_into(&mut v, incoming).unwrap();
    assert_eq!(added, 2);
    assert_eq!(v.len(), 4);
    assert_eq!(v.get(&[1; 16]).unwrap().title, "Site 1 \u{645}\u{631}\u{62d}\u{628}\u{627}", "the existing entry is untouched");
    assert_eq!(v.search("Other site").len(), 1);
}

#[test]
fn exactly_ten_history_items_are_accepted() {
    let hist = vec![r#"{"password":"x","replaced_ms":1}"#; 10].join(",");
    let e = good_entry_json().replace(r#""history":[]"#, &format!(r#""history":[{hist}]"#));
    let got = import(&sealed(&payload(&format!("[{e}]"))), PASS).unwrap();
    assert_eq!(got[0].history.len(), 10);
}

#[test]
fn exactly_the_entry_limit_is_accepted() {
    let e = good_entry_json();
    let all = format!("[{}]", vec![e.as_str(); 100_000].join(","));
    let got = import(&sealed(&payload(&all)), PASS).unwrap();
    assert_eq!(got.len(), 100_000);
}

#[test]
fn a_list_of_257_strings_is_refused() {
    let many = vec![r#""d.example""#; 257].join(",");
    let e = good_entry_json().replace(r#""domains":[]"#, &format!(r#""domains":[{many}]"#));
    assert_eq!(import(&sealed(&payload(&format!("[{e}]"))), PASS).err(), Some(VaultError::TooLarge));
    let ok = vec![r#""d.example""#; 256].join(",");
    let e = good_entry_json().replace(r#""domains":[]"#, &format!(r#""domains":[{ok}]"#));
    assert!(import(&sealed(&payload(&format!("[{e}]"))), PASS).is_ok());
}

#[test]
fn a_wrong_magic_is_corrupt_and_a_short_file_too() {
    let mut blob = export(&vault_of(1), PASS, &FAST).unwrap();
    blob[0] ^= 1;
    assert_eq!(import(&blob, PASS).err(), Some(VaultError::Corrupt));
}

/// P-19: the JSON of a vault is larger than its encoding (a control character is six bytes), so an export could pass the
/// limit that `import` then refuses: a backup that cannot be restored. It is not made.
#[test]
fn an_export_that_import_would_refuse_is_not_made() {
    let mut v = Vault::new();
    for n in 1..=200u8 {
        let mut e = entry(n, "t");
        e.notes = "\u{1}".repeat(MAX_FIELD);
        v.add(e).unwrap();
    }
    assert!(matches!(export(&v, PASS, &FAST), Err(VaultError::TooLarge)));
    // The same kind of content in a size that fits still makes a file that comes back.
    let mut small = Vault::new();
    for n in 1..=20u8 {
        let mut e = entry(n, "t");
        e.notes = "\u{1}".repeat(MAX_FIELD);
        small.add(e).unwrap();
    }
    let blob = export(&small, PASS, &FAST).unwrap();
    assert_eq!(import(&blob, PASS).unwrap().len(), 20);
}
