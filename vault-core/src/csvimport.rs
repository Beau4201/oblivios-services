// SPDX-License-Identifier: Apache-2.0
//! Import of the plain CSV files other password managers export (Bitwarden, 1Password, Chrome, Edge, Brave, Firefox,
//! KeePass, KeePassXC, LastPass, Proton Pass, Dashlane, NordPass, Apple Passwords). One header-driven reader instead of one
//! per manager: the columns are found by name, so a manager the list does not know still imports when it names its
//! columns the usual way.
//!
//! The input is untrusted text, so everything is bounded: 16 MiB of input, 100 000 entries, 64 KiB per field, 256
//! domains per entry. A row that does not fit (no password, another kind of item, a field over the limit) is skipped and
//! counted, never half imported. The file itself holds every password in plain text; the app tells the user to delete
//! it. Residual: the parsed rows are wiped when this module drops them, but the caller's copy of the file is the
//! caller's to wipe.
#![deny(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::panic)]

use std::collections::HashMap;

use zeroize::Zeroize;

use crate::error::VaultError;
use crate::exchange::random_16;
use crate::index::registrable_domain;
use crate::secret::SecretString;
use crate::vault::{validate, Entry, Vault, MAX_ENTRIES, MAX_LIST};

/// The largest file read.
pub const MAX_CSV: usize = 16 * 1024 * 1024;

/// What a file gave: the entries and the number of rows left out.
#[derive(Debug)]
pub struct Parsed {
    pub entries: Vec<Entry>,
    pub skipped: usize,
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Col {
    Title,
    Url,
    User,
    Email,
    Pass,
    Notes,
    Kind,
    Created,
    Changed,
}

fn column(name: &str) -> Option<Col> {
    Some(match name.trim().trim_start_matches('\u{feff}').to_lowercase().as_str() {
        "name" | "title" => Col::Title,
        "url" | "login_uri" | "website" | "web site" | "login url" | "origin" => Col::Url,
        "username" | "login_username" | "user name" | "login" | "user" => Col::User,
        "email" | "e-mail" | "login_email" => Col::Email,
        "password" | "login_password" => Col::Pass,
        "note" | "notes" | "extra" | "comments" => Col::Notes,
        "type" => Col::Kind,
        "timecreated" => Col::Created,
        "timepasswordchanged" => Col::Changed,
        _ => return None,
    })
}

/// The rows of the file, wiped when dropped: they hold the passwords as plain strings.
struct Rows(Vec<Vec<String>>);

impl Drop for Rows {
    fn drop(&mut self) {
        self.0.iter_mut().flatten().for_each(Zeroize::zeroize);
    }
}

/// Ends the record: a line with a single empty field that was not quoted is a blank line and is dropped.
fn end_row(rows: &mut Rows, row: &mut Vec<String>, field: &mut String, was_quoted: &mut bool) -> Result<(), VaultError> {
    row.push(std::mem::take(field));
    let blank = row.len() == 1 && row.first().is_some_and(String::is_empty) && !*was_quoted;
    *was_quoted = false;
    let done = std::mem::take(row);
    if blank {
        return Ok(());
    }
    if rows.0.len() > MAX_ENTRIES {
        return Err(VaultError::TooLarge);
    }
    rows.0.push(done);
    Ok(())
}

/// RFC 4180: fields separated by commas, a field in double quotes may hold commas, line breaks and `""` for a quote.
/// A quote opens a field only at its start; anywhere else it is an ordinary character.
fn records(text: &str) -> Result<Rows, VaultError> {
    let mut rows = Rows(Vec::new());
    let mut row: Vec<String> = Vec::new();
    let mut field = String::new();
    let (mut in_quotes, mut was_quoted) = (false, false);
    let mut chars = text.chars().peekable();
    while let Some(c) = chars.next() {
        if in_quotes {
            if c == '"' {
                if chars.peek() == Some(&'"') {
                    chars.next();
                    field.push('"');
                } else {
                    in_quotes = false;
                }
            } else {
                field.push(c);
            }
            continue;
        }
        match c {
            '"' if field.is_empty() && !was_quoted => {
                in_quotes = true;
                was_quoted = true;
            }
            ',' => {
                row.push(std::mem::take(&mut field));
                was_quoted = false;
            }
            '\r' => {
                if chars.peek() == Some(&'\n') {
                    chars.next();
                }
                end_row(&mut rows, &mut row, &mut field, &mut was_quoted)?;
            }
            '\n' => end_row(&mut rows, &mut row, &mut field, &mut was_quoted)?,
            _ => field.push(c),
        }
    }
    if in_quotes {
        return Err(VaultError::Corrupt);
    }
    if !field.is_empty() || !row.is_empty() || was_quoted {
        end_row(&mut rows, &mut row, &mut field, &mut was_quoted)?;
    }
    Ok(rows)
}

enum Place {
    Domain(String),
    Package(String),
}

fn package(name: &str) -> Option<Place> {
    let name = name.trim_end_matches('/');
    let ok = !name.is_empty()
        && name.len() <= 255
        && name.contains('.')
        && !name.starts_with('.')
        && !name.ends_with('.')
        && name.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'.' || b == b'_');
    ok.then(|| Place::Package(name.to_owned()))
}

/// A site (its registrable domain) or an Android app (`androidapp://pkg` as Bitwarden writes it, `android://hash@pkg/` as
/// Chrome does) named by one address of the file.
fn place(token: &str) -> Option<Place> {
    if let Some(p) = token.strip_prefix("androidapp://") {
        return package(p);
    }
    if let Some(p) = token.strip_prefix("android://") {
        return package(p.rsplit_once('@').map_or(p, |(_, after)| after));
    }
    let rest = token.split_once("://").map_or(token, |(_, after)| after);
    let authority = rest.split(['/', '?', '#']).next().unwrap_or("");
    let host = authority.rsplit('@').next().unwrap_or("");
    registrable_domain(host).map(Place::Domain)
}

fn cell(row: &[String], at: Option<usize>) -> &str {
    at.and_then(|i| row.get(i)).map_or("", String::as_str)
}

/// Reads the file. `now_ms` stamps the entries that the file gives no time for. `Corrupt`: not UTF-8, or no password column;
/// `TooLarge`: over 16 MiB or over 100 000 rows.
pub fn parse(input: &[u8], now_ms: i64) -> Result<Parsed, VaultError> {
    if input.len() > MAX_CSV {
        return Err(VaultError::TooLarge);
    }
    let text = std::str::from_utf8(input).map_err(|_| VaultError::Corrupt)?;
    let rows = records(text.strip_prefix('\u{feff}').unwrap_or(text))?;
    let mut lines = rows.0.iter();
    let header = lines.next().ok_or(VaultError::Corrupt)?;
    let mut at: HashMap<usize, usize> = HashMap::new();
    for (i, name) in header.iter().enumerate() {
        if let Some(col) = column(name) {
            at.entry(col as usize).or_insert(i); // the first column of a name wins
        }
    }
    let find = |col: Col| at.get(&(col as usize)).copied();
    let Some(pass_at) = find(Col::Pass) else { return Err(VaultError::Corrupt) };
    let mut out = Parsed { entries: Vec::new(), skipped: 0 };
    for row in lines {
        let kind = cell(row, find(Col::Kind)).trim().to_lowercase();
        let password = cell(row, Some(pass_at));
        if !matches!(kind.as_str(), "" | "login" | "logins" | "password" | "passwords") || password.is_empty() {
            out.skipped += 1;
            continue;
        }
        let mut domains: Vec<String> = Vec::new();
        let mut packages: Vec<String> = Vec::new();
        for token in cell(row, find(Col::Url)).split_whitespace() {
            match place(token) {
                Some(Place::Domain(d)) if !domains.contains(&d) => domains.push(d),
                Some(Place::Package(p)) if !packages.contains(&p) => packages.push(p),
                _ => {}
            }
        }
        domains.truncate(MAX_LIST);
        packages.truncate(MAX_LIST);
        let username = match cell(row, find(Col::User)) {
            "" => cell(row, find(Col::Email)),
            u => u,
        };
        let title = match cell(row, find(Col::Title)) {
            "" => domains.first().or(packages.first()).map_or("Imported", String::as_str),
            t => t,
        };
        let time = |col: Col| cell(row, find(col)).trim().parse::<i64>().ok().filter(|t| *t > 0);
        let created_ms = time(Col::Created).unwrap_or(now_ms);
        let entry = Entry {
            id: random_16(),
            title: title.to_owned(),
            domains,
            packages,
            username: username.to_owned(),
            password: SecretString::new(password),
            notes: cell(row, find(Col::Notes)).to_owned(),
            created_ms,
            edited_ms: time(Col::Changed).unwrap_or(created_ms),
            last_filled_ms: None,
            history: Vec::new(),
        };
        if validate(&entry).is_err() {
            out.skipped += 1;
            continue;
        }
        out.entries.push(entry);
    }
    Ok(out)
}

type Place3 = (String, Vec<String>, Vec<String>);

/// What identifies an account apart from its password: the username and the sorted sites and apps.
fn account_key(e: &Entry) -> Place3 {
    let sorted = |v: &[String]| {
        let mut v = v.to_vec();
        v.sort();
        v
    };
    (e.username.clone(), sorted(&e.domains), sorted(&e.packages))
}

/// Adds imported entries to a vault, leaving out an entry that has the same username, the same sites and apps and the
/// same password as one already there (so importing a file twice adds nothing). Nothing is overwritten. All or nothing:
/// on a vault limit the vault is unchanged. Returns `(added, duplicates)`.
pub fn merge(vault: &mut Vault, entries: Vec<Entry>) -> Result<(usize, usize), VaultError> {
    let mut work = vault.clone();
    let mut known: HashMap<Place3, Vec<usize>> = HashMap::new();
    for (i, e) in work.entries().iter().enumerate() {
        known.entry(account_key(e)).or_default().push(i);
    }
    let (mut added, mut duplicates) = (0, 0);
    for e in entries {
        let key = account_key(&e);
        let twin = known.get(&key).is_some_and(|ix| ix.iter().any(|i| work.entries().get(*i).is_some_and(|o| o.password == e.password)));
        if twin {
            duplicates += 1;
            continue;
        }
        known.entry(key).or_default().push(work.len());
        work.add(e)?;
        added += 1;
    }
    *vault = work;
    Ok((added, duplicates))
}
