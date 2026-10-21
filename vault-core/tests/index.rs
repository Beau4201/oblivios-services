// SPDX-License-Identifier: Apache-2.0
use vault_core::error::VaultError;
use vault_core::index::{registrable_domain, DomainIndex, Kind};
use vault_core::secret::SecretString;
use vault_core::vault::{Entry, Vault};

const KEY: [u8; 32] = [0x5a; 32];

fn entry(n: u8, domains: &[&str], packages: &[&str]) -> Entry {
    Entry {
        id: [n; 16],
        title: format!("entry {n}"),
        domains: domains.iter().map(|s| s.to_string()).collect(),
        packages: packages.iter().map(|s| s.to_string()).collect(),
        username: "u".into(),
        password: SecretString::new("p"),
        notes: String::new(),
        created_ms: 0,
        edited_ms: 0,
        last_filled_ms: None,
        history: vec![],
    }
}

fn index_for(entries: Vec<Entry>) -> DomainIndex {
    let mut v = Vault::new();
    for e in entries {
        v.add(e).unwrap();
    }
    DomainIndex::build(&KEY, &v)
}

fn github() -> DomainIndex {
    index_for(vec![entry(1, &["github.com"], &[])])
}

#[test]
fn subdomain_matches_its_registrable_domain() {
    let idx = github();
    assert!(idx.contains(&KEY, "github.com", Kind::Domain));
    assert!(idx.contains(&KEY, "login.github.com", Kind::Domain));
    assert!(idx.contains(&KEY, "a.b.c.github.com", Kind::Domain));
}

#[test]
fn an_entry_stored_with_a_subdomain_matches_the_whole_site() {
    let idx = index_for(vec![entry(1, &["login.GitHub.com"], &[])]);
    assert!(idx.contains(&KEY, "github.com", Kind::Domain));
}

#[test]
fn look_alikes_do_not_match() {
    let idx = github();
    for host in [
        "github.com.evil.example",
        "githubb.com",
        "gith\u{fb}b.com",
        "g\u{456}thub.com", // Cyrillic i
        "xn--gthub-8ve.com",
        "evil.example/github.com",
        "github.com@evil.example",
        "github.com.",
        "notgithub.com",
        "github.co",
        "github.com.au",
        "",
    ] {
        // The trailing-dot form is the same host and is allowed to match; every other one is a different site.
        let expect = host == "github.com.";
        assert_eq!(idx.contains(&KEY, host, Kind::Domain), expect, "{host:?}");
    }
    assert!(idx.contains(&KEY, "GITHUB.COM.", Kind::Domain), "case and the trailing dot are normalised");
}

#[test]
fn ip_literals_and_ports() {
    assert_eq!(registrable_domain("192.0.2.1"), None);
    assert_eq!(registrable_domain("[::1]"), None);
    assert_eq!(registrable_domain("[::1]:8443"), None);
    assert_eq!(registrable_domain("::1"), None);
    assert_eq!(registrable_domain("2130706433"), None);
    assert_eq!(registrable_domain("127.1"), None);
    assert_eq!(registrable_domain("github.com:8443"), Some("github.com".into()));
    assert_eq!(registrable_domain("login.github.com:443"), Some("github.com".into()));
    assert_eq!(registrable_domain("github.com:"), None);
    assert_eq!(registrable_domain("github.com:99999999"), None);
    let idx = index_for(vec![entry(1, &["192.0.2.1"], &[])]);
    assert!(!idx.contains(&KEY, "192.0.2.1", Kind::Domain), "an IP literal is never indexed");
}

#[test]
fn public_suffix_alone_is_none() {
    assert_eq!(registrable_domain("co.uk"), None);
    assert_eq!(registrable_domain("com"), None);
    assert_eq!(registrable_domain("example.co.uk"), Some("example.co.uk".into()));
    assert_eq!(registrable_domain("a.b.example.co.uk"), Some("example.co.uk".into()));
}

#[test]
fn normalisation() {
    assert_eq!(registrable_domain("WWW.GitHub.COM."), Some("github.com".into()));
    for bad in ["", ".", "a..com", " github.com", "git hub.com", "github.com/path", "-a.com", "a-.com", "a_b.com"] {
        assert_eq!(registrable_domain(bad), None, "{bad:?}");
    }
    let long_label = format!("{}.com", "a".repeat(64));
    assert_eq!(registrable_domain(&long_label), None);
    assert!(registrable_domain(&format!("{}.com", "a".repeat(63))).is_some());
    let long_host = format!("{}.com", ["abcdefghi"; 30].join("."));
    assert_eq!(registrable_domain(&long_host), None, "a host over 253 bytes is refused");
}

#[test]
fn internationalised_names_use_punycode() {
    assert_eq!(registrable_domain("b\u{fc}cher.de"), Some("xn--bcher-kva.de".into()));
    assert_eq!(registrable_domain("www.M\u{fc}nchen.de"), Some("xn--mnchen-3ya.de".into()));
    assert_eq!(registrable_domain("\u{65e5}\u{672c}\u{8a9e}.jp"), Some("xn--wgv71a119e.jp".into()));
    assert_eq!(registrable_domain("\u{43f}\u{440}\u{430}\u{432}\u{434}\u{430}.ru"), Some("xn--80aafi6cg.ru".into()));
    // The browser hands over the punycode form; the user may have typed either.
    let by_unicode = index_for(vec![entry(1, &["b\u{fc}cher.de"], &[])]);
    assert!(by_unicode.contains(&KEY, "xn--bcher-kva.de", Kind::Domain));
    let by_punycode = index_for(vec![entry(1, &["xn--bcher-kva.de"], &[])]);
    assert!(by_punycode.contains(&KEY, "shop.b\u{fc}cher.de", Kind::Domain));
    assert!(!by_unicode.contains(&KEY, "bucher.de", Kind::Domain));
}

#[test]
fn package_and_domain_do_not_cross() {
    let pkg = index_for(vec![entry(1, &[], &["example.app"])]);
    assert!(pkg.contains(&KEY, "example.app", Kind::Package));
    assert!(!pkg.contains(&KEY, "example.app", Kind::Domain));
    let dom = index_for(vec![entry(1, &["example.app"], &[])]);
    assert!(dom.contains(&KEY, "example.app", Kind::Domain));
    assert!(!dom.contains(&KEY, "example.app", Kind::Package));
}

#[test]
fn packages_match_exactly() {
    let idx = index_for(vec![entry(1, &[], &["com.example.Mail"])]);
    assert!(idx.contains(&KEY, "com.example.Mail", Kind::Package));
    assert!(!idx.contains(&KEY, "com.example.mail", Kind::Package));
    assert!(!idx.contains(&KEY, "com.example.Mail2", Kind::Package));
    assert!(!idx.contains(&KEY, "com.example", Kind::Package));
}

#[test]
fn index_holds_no_plaintext() {
    let idx = index_for(vec![entry(1, &["secret-bank.example", "github.com"], &["org.freeholdos.secretapp"])]);
    let bytes = idx.to_bytes();
    for needle in ["secret-bank", "bank.example", "github", "freeholdos", "secretapp", ".com"] {
        assert!(!bytes.windows(needle.len()).any(|w| w == needle.as_bytes()), "{needle} found in the index");
    }
}

#[test]
fn different_keys_give_different_indexes() {
    let v = {
        let mut v = Vault::new();
        v.add(entry(1, &["github.com"], &[])).unwrap();
        v
    };
    let a = DomainIndex::build(&[1; 32], &v);
    let b = DomainIndex::build(&[2; 32], &v);
    assert_ne!(a.to_bytes(), b.to_bytes());
    assert!(a.contains(&[1; 32], "github.com", Kind::Domain));
    assert!(!a.contains(&[2; 32], "github.com", Kind::Domain), "an index does not answer for another key");
}

#[test]
fn serialisation_roundtrips_and_is_deterministic() {
    let idx = index_for(vec![entry(1, &["github.com", "example.org"], &["a.b.c"]), entry(2, &["github.com"], &[])]);
    let bytes = idx.to_bytes();
    assert_eq!(bytes, idx.to_bytes());
    let back = DomainIndex::from_bytes(&bytes).unwrap();
    assert_eq!(back.to_bytes(), bytes);
    assert!(back.contains(&KEY, "example.org", Kind::Domain));
    assert!(back.contains(&KEY, "a.b.c", Kind::Package));
    assert!(!back.contains(&KEY, "other.org", Kind::Domain));
}

#[test]
fn from_bytes_refuses_hostile_input() {
    let bytes = github().to_bytes();
    for len in 0..bytes.len() {
        assert!(DomainIndex::from_bytes(&bytes[..len]).is_err(), "cut to {len}");
    }
    let mut extra = bytes.clone();
    extra.push(0);
    assert_eq!(DomainIndex::from_bytes(&extra).err(), Some(VaultError::Corrupt));
    let mut magic = bytes.clone();
    magic[0] ^= 1;
    assert_eq!(DomainIndex::from_bytes(&magic).err(), Some(VaultError::Corrupt));
    // magic 4 | version 1 | count 4
    let mut huge = bytes.clone();
    huge[5..9].copy_from_slice(&u32::MAX.to_le_bytes());
    assert_eq!(DomainIndex::from_bytes(&huge).err(), Some(VaultError::TooLarge));
    // Hashes out of order would make the binary search answer wrongly.
    let two = index_for(vec![entry(1, &["github.com", "example.org"], &[])]).to_bytes();
    let mut swapped = two.clone();
    let (a, b) = (two[9..25].to_vec(), two[25..41].to_vec());
    swapped[9..25].copy_from_slice(&b);
    swapped[25..41].copy_from_slice(&a);
    assert_eq!(DomainIndex::from_bytes(&swapped).err(), Some(VaultError::Corrupt));
}

#[test]
fn an_empty_vault_gives_an_empty_index() {
    let idx = DomainIndex::build(&KEY, &Vault::new());
    assert!(!idx.contains(&KEY, "github.com", Kind::Domain));
    assert_eq!(DomainIndex::from_bytes(&idx.to_bytes()).unwrap().to_bytes(), idx.to_bytes());
}

#[test]
fn private_suffixes_keep_tenants_apart() {
    // github.io is on the private part of the public suffix list: every user is their own site.
    assert_eq!(registrable_domain("alice.github.io"), Some("alice.github.io".into()));
    assert_eq!(registrable_domain("www.alice.github.io"), Some("alice.github.io".into()));
    assert_eq!(registrable_domain("github.io"), None);
    let idx = index_for(vec![entry(1, &["alice.github.io"], &[])]);
    assert!(!idx.contains(&KEY, "bob.github.io", Kind::Domain));
}

#[test]
fn ports_up_to_65535_are_accepted() {
    assert_eq!(registrable_domain("github.com:65535"), Some("github.com".into()));
    assert_eq!(registrable_domain("github.com:0"), Some("github.com".into()));
    assert_eq!(registrable_domain("github.com:65536"), None);
    assert_eq!(registrable_domain("github.com:123456"), None);
    assert_eq!(registrable_domain("github.com:12a"), None);
}

#[test]
fn a_host_may_be_253_bytes_but_not_254() {
    // 3 labels of 63 + the registrable "ab.com" tail: 63 + 1 + 63 + 1 + 63 + 1 + 61 = 253
    let l63 = "a".repeat(63);
    let ok = format!("{l63}.{l63}.{l63}.{}.com", "b".repeat(57));
    assert_eq!(ok.len(), 253);
    assert!(registrable_domain(&ok).is_some());
    let too_long = format!("{l63}.{l63}.{l63}.{}.com", "b".repeat(58));
    assert_eq!(too_long.len(), 254);
    assert_eq!(registrable_domain(&too_long), None);
}

#[test]
fn duplicate_tags_and_unknown_versions_are_refused() {
    let two = index_for(vec![entry(1, &["github.com", "example.org"], &[])]).to_bytes();
    let mut dup = two.clone();
    let first = two[9..25].to_vec();
    dup[25..41].copy_from_slice(&first);
    assert_eq!(DomainIndex::from_bytes(&dup).err(), Some(VaultError::Corrupt));
    let mut v2 = two.clone();
    v2[4] = 2;
    assert_eq!(DomainIndex::from_bytes(&v2).err(), Some(VaultError::UnsupportedVersion(2)));
}

#[test]
fn a_port_is_digits_only() {
    // u32::parse accepts a leading plus sign; a port with one is not a port.
    assert_eq!(registrable_domain("github.com:+80"), None);
    assert_eq!(registrable_domain("github.com:-80"), None);
    assert_eq!(registrable_domain("github.com: 80"), None);
}

#[test]
fn longer_internationalised_labels_match_the_rfc_3492_samples() {
    // RFC 3492 section 7.1, (I) Russian, and (N) a mix of ASCII and Japanese (here in lower case, as the host is lowered first).
    let russian = "\u{43f}\u{43e}\u{447}\u{435}\u{43c}\u{443}\u{436}\u{435}\u{43e}\u{43d}\u{438}\u{43d}\u{435}\u{433}\u{43e}\u{432}\u{43e}\u{440}\u{44f}\u{442}\u{43f}\u{43e}\u{440}\u{443}\u{441}\u{441}\u{43a}\u{438}";
    assert_eq!(registrable_domain(&format!("{russian}.com")), Some("xn--b1abfaaepdrnnbgefbadotcwatmq2g4l.com".into()));
    let mixed = "hello-another-way-\u{305d}\u{308c}\u{305e}\u{308c}\u{306e}\u{5834}\u{6240}";
    assert_eq!(registrable_domain(&format!("{mixed}.com")), Some("xn--hello-another-way--fc4qua05auwb3674vfr0b.com".into()));
    // Japanese with the sample (L) digits first: "3年B組金八先生" lower-cased.
    let digits = "3\u{5e74}b\u{7d44}\u{91d1}\u{516b}\u{5148}\u{751f}";
    assert_eq!(registrable_domain(&format!("{digits}.com")), Some("xn--3b-ww4c5e180e575a65lsy2b.com".into()));
}
