// SPDX-License-Identifier: Apache-2.0
//! The CSV import of other managers' plain files: the reader, the row rules and the merge into a vault.
use vault_core::api::{self, Code};
use vault_core::csvimport::{merge, parse, MAX_CSV};
use vault_core::error::VaultError;
use vault_core::kdf::KdfParams;
use vault_core::secret::SecretString;
use vault_core::vault::{Entry, Vault, MAX_FIELD};

const NOW: i64 = 1_800_000_000_000;

fn titles(csv: &str) -> Vec<String> {
    parse(csv.as_bytes(), NOW).unwrap().entries.into_iter().map(|e| e.title).collect()
}

fn one(csv: &str) -> Entry {
    let mut p = parse(csv.as_bytes(), NOW).unwrap();
    assert_eq!(p.entries.len(), 1, "{csv}");
    p.entries.remove(0)
}

fn entry(user: &str, domain: &str, password: &str) -> Entry {
    Entry {
        id: [9; 16],
        title: "t".into(),
        domains: vec![domain.into()],
        packages: vec![],
        username: user.into(),
        password: SecretString::new(password),
        notes: String::new(),
        created_ms: 1,
        edited_ms: 1,
        last_filled_ms: None,
        history: vec![],
    }
}

#[test]
fn bitwarden_logins_are_read_and_other_items_are_left_out() {
    let csv = "folder,favorite,type,name,notes,fields,reprompt,login_uri,login_username,login_password,login_totp\r\n\
        Work,1,login,GitHub,\"line one\nline two\",,0,https://github.com/login,anna,pw-1,\r\n\
        ,0,note,A secret note,body,,0,,,,\r\n\
        ,0,card,Visa,,,0,,,,\r\n\
        ,0,login,Phone app,,,0,androidapp://com.example.app,bob,pw-2,\r\n";
    let p = parse(csv.as_bytes(), NOW).unwrap();
    assert_eq!(p.skipped, 2, "the note and the card");
    assert_eq!(p.entries.len(), 2);
    let e = &p.entries[0];
    assert_eq!((e.title.as_str(), e.username.as_str(), e.password.expose()), ("GitHub", "anna", "pw-1"));
    assert_eq!(e.domains, ["github.com"]);
    assert_eq!(e.notes, "line one\nline two", "a quoted line break stays in the field");
    assert_eq!((e.created_ms, e.edited_ms, e.last_filled_ms), (NOW, NOW, None));
    assert_eq!(p.entries[1].packages, ["com.example.app"]);
    assert!(p.entries[1].domains.is_empty());
}

#[test]
fn chrome_edge_and_brave_files_are_read() {
    let e = one("name,url,username,password,note\nexample,https://www.example.org/login?x=1,carol,pw,hello\n");
    assert_eq!((e.title.as_str(), e.username.as_str(), e.notes.as_str()), ("example", "carol", "hello"));
    assert_eq!(e.domains, ["example.org"]);
    let android = one("name,url,username,password,note\napp,android://c2hhMQ==@com.chrome.app/,dave,pw,\n");
    assert_eq!(android.packages, ["com.chrome.app"]);
}

#[test]
fn firefox_times_are_kept() {
    let e = one("url,username,password,httpRealm,formSubmitURL,guid,timeCreated,timeLastUsed,timePasswordChanged\nhttps://a.example,u,p,,,{g},1600000000000,1,1650000000000\n");
    assert_eq!((e.created_ms, e.edited_ms), (1_600_000_000_000, 1_650_000_000_000));
    let bad = one("url,username,password,timeCreated,timePasswordChanged\nhttps://a.example,u,p,0,abc\n");
    assert_eq!((bad.created_ms, bad.edited_ms), (NOW, NOW), "a time that is not a positive number is the import time");
    let negative = one("url,username,password,timeCreated\nhttps://a.example,u,p,-5\n");
    assert_eq!(negative.created_ms, NOW);
    let only_created = one("url,username,password,timeCreated\nhttps://a.example,u,p,1600000000000\n");
    assert_eq!((only_created.created_ms, only_created.edited_ms), (1_600_000_000_000, 1_600_000_000_000), "no change time: the creation time");
}

#[test]
fn every_column_alias_is_recognised() {
    let cases: [(&str, &[&str]); 5] = [
        ("title", &["name", "title"]),
        ("url", &["url", "login_uri", "website", "web site", "login url", "origin"]),
        ("user", &["username", "login_username", "user name", "login", "user", "email", "e-mail", "login_email"]),
        ("notes", &["note", "notes", "extra", "comments"]),
        ("pass", &["password", "login_password"]),
    ];
    for (role, aliases) in cases {
        for alias in aliases {
            let (header, row) = match role {
                "title" => (format!("{alias},password,username"), "T,p,u"),
                "url" => (format!("{alias},password,username"), "https://x.example,p,u"),
                "user" => (format!("{alias},password"), "u,p"),
                "notes" => (format!("{alias},password,username"), "N,p,u"),
                _ => (format!("{alias},username"), "p,u"),
            };
            let e = one(&format!("{header}\n{row}\n"));
            let ok = match role {
                "title" => e.title == "T",
                "url" => e.domains == ["x.example"],
                "user" => e.username == "u",
                "notes" => e.notes == "N",
                _ => e.password.expose() == "p",
            };
            assert!(ok, "the column {alias} is not read as {role}");
        }
    }
}

#[test]
fn the_headers_of_the_other_managers_are_recognised() {
    for csv in [
        "Title,Website,Username,Password,OTPAuth,Favorite,Archived,Tags,Notes\nT,https://x.example,u,p,,,,,\n", // 1Password
        "Group,Title,Username,Password,URL,Notes\ng,T,u,p,https://x.example,\n",                              // KeePass
        "url,username,password,totp,extra,name,grouping,fav\nhttps://x.example,u,p,,,T,,0\n",                // LastPass
        "type,name,url,email,username,password,note,totp,createTime,modifyTime,vault\nlogin,T,https://x.example,e@x.example,u,p,,,,,\n", // Proton Pass
        "username,username2,username3,title,password,note,url,category,otpSecret\nu,,,T,p,,https://x.example,c,\n", // Dashlane
        "Title,URL,Username,Password,Notes,OTPAuth\nT,https://x.example,u,p,,\n",                            // Apple Passwords
        "name,url,username,password,note,cardholdername\nT,https://x.example,u,p,,\n",                       // NordPass
    ] {
        let e = one(csv);
        assert_eq!((e.title.as_str(), e.username.as_str(), e.password.expose()), ("T", "u", "p"), "{csv}");
        assert_eq!(e.domains, ["x.example"], "{csv}");
    }
}

#[test]
fn a_header_in_capitals_and_a_byte_order_mark_are_fine() {
    let e = one("\u{feff}NAME,URL,USERNAME,PASSWORD\nT,https://x.example,u,p\n");
    assert_eq!(e.title, "T");
}

#[test]
fn csv_quoting_follows_rfc_4180() {
    let e = one("name,url,username,password,note\n\"Smith, \"\"J\"\"\",https://x.example,\"u,v\",\"p\"\"q\",\"a\r\nb\"\n");
    assert_eq!((e.title.as_str(), e.username.as_str(), e.password.expose(), e.notes.as_str()), ("Smith, \"J\"", "u,v", "p\"q", "a\r\nb"));
    // A quote inside an unquoted field is an ordinary character.
    assert_eq!(one("name,url,username,password\nab\"c,https://x.example,u,p\n").title, "ab\"c");
    // A file without a final line break, blank lines, a lone carriage return, empty trailing fields.
    assert_eq!(titles("name,url,username,password\n\nA,,u,p\n\nB,,u,p"), ["A", "B"]);
    assert_eq!(titles("name,url,username,password\rA,,u,p\rB,,u,p\r"), ["A", "B"]);
    assert_eq!(one("name,url,username,password\nA,,,p,,,\n").username, "");
}

#[test]
fn a_quoted_empty_field_at_the_end_is_a_row() {
    let p = parse(b"name,url,username,password\nA,,u,\"\"", NOW).unwrap();
    assert_eq!((p.entries.len(), p.skipped), (0, 1), "a row with an empty password is skipped and counted");
    let lone = parse(b"password\n\"\"\n", NOW).unwrap();
    assert_eq!(lone.skipped, 1, "a line holding only a quoted empty field is a row, not a blank line");
    assert_eq!(parse(b"password\n\"\"", NOW).unwrap().skipped, 1, "the same without a final line break");
    assert_eq!(parse(b"password\n\n\n", NOW).unwrap().skipped, 0, "blank lines are not rows");
}

#[test]
fn a_file_of_exactly_the_largest_size_is_read() {
    let head = b"name,url,username,password\nA,,u,p,";
    let mut file = head.to_vec();
    file.resize(MAX_CSV, b'x'); // the last field of the row is 16 MiB of padding: the row is too long and skipped
    let p = parse(&file, NOW).unwrap();
    assert_eq!(p.entries.len() + p.skipped, 1);
}

#[test]
fn an_unterminated_quote_is_corrupt() {
    assert_eq!(parse(b"name,url,username,password\nA,,u,\"never closed", NOW).err(), Some(VaultError::Corrupt));
}

#[test]
fn a_file_without_a_password_column_or_without_text_is_corrupt() {
    assert_eq!(parse(b"name,url,username\nA,,u\n", NOW).err(), Some(VaultError::Corrupt));
    assert_eq!(parse(b"", NOW).err(), Some(VaultError::Corrupt));
    assert_eq!(parse(b"\n\n", NOW).err(), Some(VaultError::Corrupt));
    assert_eq!(parse(&[0xff, 0xfe, b'p', b'a', b's', b's'], NOW).err(), Some(VaultError::Corrupt), "not UTF-8");
}

#[test]
fn a_header_alone_is_an_empty_import() {
    let p = parse(b"name,url,username,password\n", NOW).unwrap();
    assert_eq!((p.entries.len(), p.skipped), (0, 0));
}

#[test]
fn the_size_limits_hold() {
    assert_eq!(parse(&vec![b'a'; MAX_CSV + 1], NOW).err(), Some(VaultError::TooLarge));
    let mut rows = String::from("name,url,username,password\n");
    for _ in 0..100_000 {
        rows.push_str("a,,u,p\n");
    }
    assert!(parse(rows.as_bytes(), NOW).is_ok(), "100 000 rows and the header fit");
    rows.push_str("a,,u,p\n");
    assert_eq!(parse(rows.as_bytes(), NOW).err(), Some(VaultError::TooLarge), "one row more does not");
}

#[test]
fn a_row_with_a_field_over_the_limit_is_skipped_not_half_imported() {
    let big = "x".repeat(MAX_FIELD + 1);
    let csv = format!("name,url,username,password,note\nbig,,u,p,{big}\nok,,u,p,fine\n");
    let p = parse(csv.as_bytes(), NOW).unwrap();
    assert_eq!((p.entries.len(), p.skipped), (1, 1));
    assert_eq!(p.entries[0].title, "ok");
}

#[test]
fn addresses_become_registrable_domains_or_packages() {
    let url = |u: &str| one(&format!("name,url,username,password\nT,\"{u}\",u,p\n")).domains;
    assert_eq!(url("HTTPS://User:Pw@Login.GitHub.com:8443/a/b?c=d#e"), ["github.com"]);
    assert_eq!(url("login.example.co.uk"), ["example.co.uk"], "no scheme");
    assert_eq!(url("https://bücher.example/"), ["xn--bcher-kva.example"], "punycode");
    assert_eq!(url("https://q.example?x=1"), ["q.example"], "a query right after the host");
    assert_eq!(url("https://f.example#frag"), ["f.example"], "a fragment right after the host");
    assert!(url("http://192.168.1.1/admin").is_empty(), "an IP address owns no account");
    assert!(url("http://localhost/").is_empty());
    assert!(url("https://co.uk/").is_empty(), "a bare public suffix");
    assert!(url("http://sn").is_empty(), "LastPass's marker of a secure note");
    assert!(url("not a url at all").is_empty());
    assert_eq!(url("https://a.example/\nhttps://www.a.example/x https://b.example"), ["a.example", "b.example"], "several, duplicates once");
    let e = one("name,url,username,password\nT,\"androidapp://com.a.b androidapp://com.a.b androidapp://bad..\",u,p\n");
    assert_eq!(e.packages, ["com.a.b"]);
    assert!(parse(b"name,url,username,password\nT,androidapp://nodots,u,p\n", NOW).unwrap().entries[0].packages.is_empty());
}

#[test]
fn username_and_title_fall_back() {
    let e = one("type,name,url,email,username,password\nlogin,,https://x.example,mail@x.example,,p\n");
    assert_eq!((e.username.as_str(), e.title.as_str()), ("mail@x.example", "x.example"));
    let app = one("name,url,username,password\n,androidapp://com.a.b,u,p\n");
    assert_eq!(app.title, "com.a.b");
    let none = one("name,url,username,password\n,,u,p\n");
    assert_eq!(none.title, "Imported");
}

#[test]
fn the_first_column_of_a_name_wins() {
    let e = one("name,username,username,password\nT,first,second,p\n");
    assert_eq!(e.username, "first");
}

#[test]
fn rows_with_no_password_or_another_kind_are_counted() {
    let csv = "type,name,username,password\nlogin,A,u,\nsecure note,B,u,p\nLogin,C,u,p\npassword,D,u,p\nlogins,E,u,p\n Passwords ,F,u,p\n,G,u,p\n";
    let p = parse(csv.as_bytes(), NOW).unwrap();
    assert_eq!(p.skipped, 2);
    assert_eq!(p.entries.iter().map(|e| e.title.as_str()).collect::<Vec<_>>(), ["C", "D", "E", "F", "G"]);
}

#[test]
fn at_most_256_sites_and_apps_are_kept_per_entry() {
    let urls: Vec<String> = (0..300).map(|i| format!("https://site{i}.example")).collect();
    let apps: Vec<String> = (0..300).map(|i| format!("androidapp://com.app{i}.x")).collect();
    let e = one(&format!("name,url,username,password\nT,\"{} {}\",u,p\n", urls.join(" "), apps.join(" ")));
    assert_eq!((e.domains.len(), e.packages.len()), (256, 256));
}

#[test]
fn a_package_name_must_look_like_one() {
    let pkg = |p: &str| one(&format!("name,url,username,password\nT,androidapp://{p},u,p\n")).packages;
    assert_eq!(pkg("com.a_b.c9"), ["com.a_b.c9"]);
    assert!(pkg(".com.a").is_empty(), "leading dot");
    assert!(pkg("com.a-b").is_empty(), "a hyphen is not allowed in a package name");
    assert!(pkg(&format!("com.{}", "a".repeat(260))).is_empty(), "over 255 characters");
}

#[test]
fn a_merge_that_hits_a_vault_limit_changes_nothing() {
    let big = |n: u32, user: &str| Entry {
        id: {
            let mut id = [0u8; 16];
            id[..4].copy_from_slice(&n.to_le_bytes());
            id
        },
        title: "big".into(),
        domains: vec![],
        packages: vec![],
        username: user.into(),
        password: SecretString::new(format!("pw-{n}")),
        notes: "x".repeat(MAX_FIELD),
        created_ms: 1,
        edited_ms: 1,
        last_filled_ms: None,
        history: vec![],
    };
    let mut v = Vault::new();
    for n in 0..950 {
        v.add(big(n, "old")).unwrap();
    }
    let incoming: Vec<Entry> = (1000..1100).map(|n| big(n, "new")).collect();
    assert_eq!(merge(&mut v, incoming).err(), Some(VaultError::TooLarge));
    assert_eq!(v.len(), 950, "the vault is as it was");
}

#[test]
fn every_imported_entry_gets_its_own_id() {
    let p = parse(b"name,username,password\na,u,p\nb,u,p\nc,u,p\n", NOW).unwrap();
    let mut ids: Vec<_> = p.entries.iter().map(|e| e.id).collect();
    ids.sort();
    ids.dedup();
    assert_eq!(ids.len(), 3);
}

#[test]
fn merge_adds_new_accounts_and_leaves_out_what_is_there() {
    let mut v = Vault::new();
    v.add(entry("anna", "x.example", "pw")).unwrap();
    let mut same = entry("anna", "x.example", "pw");
    same.id = [1; 16];
    let mut other_pw = entry("anna", "x.example", "changed");
    other_pw.id = [2; 16];
    let mut other_user = entry("bob", "x.example", "pw");
    other_user.id = [3; 16];
    let mut other_site = entry("anna", "y.example", "pw");
    other_site.id = [4; 16];
    let mut again = entry("bob", "x.example", "pw");
    again.id = [5; 16];
    let (added, dups) = merge(&mut v, vec![same, other_pw, other_user, other_site, again]).unwrap();
    assert_eq!((added, dups), (3, 2), "a different password, user or site is a new entry; the same account twice is one");
    assert_eq!(v.len(), 4);
}

#[test]
fn importing_a_file_twice_adds_nothing_the_second_time() {
    let csv = "name,url,username,password\nA,https://a.example,u,p1\nB,https://b.example,u,p2\n";
    let mut v = Vault::new();
    let first = parse(csv.as_bytes(), NOW).unwrap();
    assert_eq!(merge(&mut v, first.entries).unwrap(), (2, 0));
    let second = parse(csv.as_bytes(), NOW).unwrap();
    assert_eq!(merge(&mut v, second.entries).unwrap(), (0, 2));
    assert_eq!(v.len(), 2);
}

#[test]
fn the_site_order_does_not_make_an_account_new() {
    let mut a = entry("u", "a.example", "p");
    a.domains.push("b.example".into());
    let mut v = Vault::new();
    v.add(a).unwrap();
    let mut b = entry("u", "b.example", "p");
    b.domains.push("a.example".into());
    b.id = [8; 16];
    assert_eq!(merge(&mut v, vec![b]).unwrap(), (0, 1));
}

#[test]
fn the_linked_apps_are_part_of_the_account() {
    let mut a = entry("u", "a.example", "p");
    a.packages.push("com.example.one".into());
    let mut v = Vault::new();
    v.add(a).unwrap();
    let mut same_apps = entry("u", "a.example", "p");
    same_apps.packages.push("com.example.one".into());
    same_apps.id = [8; 16];
    let mut other_app = entry("u", "a.example", "p");
    other_app.packages.push("com.example.two".into());
    other_app.id = [9; 16];
    assert_eq!(merge(&mut v, vec![same_apps, other_app]).unwrap(), (1, 1));
}

#[test]
fn the_api_merges_a_file_and_reports_the_counts() {
    let fast = KdfParams { m_kib: 64, t: 1, p: 1 };
    let c = api::create_with_params(b"tundra-wicker-48", &[0x77; 32], &fast).unwrap();
    let h = api::unlock_with_passphrase(&c.file, b"tundra-wicker-48", &[0x77; 32]).unwrap();
    let csv = b"name,url,username,password\nA,https://a.example,u,p1\nA again,https://a.example,u,p1\nNo password,https://c.example,u,\nB,https://b.example,u,p2\n";
    let reply = api::import_csv(h, csv, NOW).unwrap();
    let word = |i: usize| u32::from_le_bytes(reply[4 * i..4 * i + 4].try_into().unwrap());
    assert_eq!((word(0), word(1), word(2)), (2, 1, 1), "added, duplicates, skipped rows");
    let reopened = api::unlock_with_passphrase(&reply[12..], b"tundra-wicker-48", &[0x77; 32]).unwrap();
    assert!(api::get(reopened, &entry_id_of_first(h)).is_ok(), "the new file holds the entries");
    assert_eq!(api::import_csv(h, b"a,b\n1,2\n", NOW).err(), Some(Code::Corrupt), "no password column");
    assert_eq!(api::lock(h), Code::Ok);
    assert_eq!(api::import_csv(h, csv, NOW).err(), Some(Code::DeadHandle));
}

fn entry_id_of_first(h: u64) -> [u8; 16] {
    let list = api::list(h).unwrap();
    // count (u32), then the first summary starts with its 16-byte id (the wire form is the spec of this slice).
    list[4..20].try_into().unwrap()
}
