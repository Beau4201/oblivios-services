// SPDX-License-Identifier: Apache-2.0
//! The vault: a list of password entries held in memory and sealed as one blob.
//!
//! Plaintext layout (sealed by [`crate::envelope`] under the vault key): `u8 schema (1) | u32 entry count | entries`.
//! The limits below are enforced when an entry is added or edited, not only when a file is read, so that the vault can
//! never hold something that its own file could not reopen.

use std::collections::HashSet;

use zeroize::Zeroizing;

pub use crate::codec::MAX_FIELD;
use crate::codec::{Reader, Writer};
use crate::envelope::{open, seal};
use crate::error::VaultError;
use crate::keys::VaultKey;
use crate::secret::SecretString;

pub const VAULT_AAD: &[u8] = b"freeholdos-passwords/vault/v1";
const SCHEMA: u8 = 1;
pub const MAX_ENTRIES: usize = 100_000;
/// Domains or linked packages per entry.
pub const MAX_LIST: usize = 256;
pub const MAX_HISTORY: usize = 10;
/// The plaintext may not outgrow what one sealed blob can carry.
pub const MAX_PLAIN: usize = 64 * 1024 * 1024;
/// id 16, six length or count words of 4, two times 8, the flag and the history count.
const MIN_ENTRY: usize = 16 + 6 * 4 + 2 * 8 + 1 + 1;
const HEADER: usize = 1 + 4;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Entry {
    pub id: [u8; 16],
    pub title: String,
    /// Registrable domains (Task 5 matches on these).
    pub domains: Vec<String>,
    /// Linked Android package names.
    pub packages: Vec<String>,
    pub username: String,
    pub password: SecretString,
    pub notes: String,
    pub created_ms: i64,
    pub edited_ms: i64,
    pub last_filled_ms: Option<i64>,
    /// Earlier passwords with the time they were replaced, newest first, at most [`MAX_HISTORY`].
    pub history: Vec<(SecretString, i64)>,
}

fn encoded_len(e: &Entry) -> usize {
    let strs = |v: &Vec<String>| v.iter().map(|s| 4 + s.len()).sum::<usize>();
    16 + (4 + e.title.len())
        + (4 + strs(&e.domains))
        + (4 + strs(&e.packages))
        + (4 + e.username.len())
        + (4 + e.password.expose().len())
        + (4 + e.notes.len())
        + 8
        + 8
        + 1
        + if e.last_filled_ms.is_some() { 8 } else { 0 }
        + 1
        + e.history.iter().map(|(p, _)| 4 + p.expose().len() + 8).sum::<usize>()
}

fn validate(e: &Entry) -> Result<(), VaultError> {
    let field_ok = |s: &str| s.len() <= MAX_FIELD;
    let ok = field_ok(&e.title)
        && field_ok(&e.username)
        && field_ok(e.password.expose())
        && field_ok(&e.notes)
        && e.domains.len() <= MAX_LIST
        && e.packages.len() <= MAX_LIST
        && e.domains.iter().chain(e.packages.iter()).all(|s| field_ok(s))
        && e.history.len() <= MAX_HISTORY
        && e.history.iter().all(|(p, _)| field_ok(p.expose()));
    if ok {
        Ok(())
    } else {
        Err(VaultError::TooLarge)
    }
}

fn encode(e: &Entry, w: &mut Writer) {
    w.raw(&e.id);
    w.str(&e.title);
    w.u32(e.domains.len() as u32);
    e.domains.iter().for_each(|s| w.str(s));
    w.u32(e.packages.len() as u32);
    e.packages.iter().for_each(|s| w.str(s));
    w.str(&e.username);
    w.str(e.password.expose());
    w.str(&e.notes);
    w.i64(e.created_ms);
    w.i64(e.edited_ms);
    match e.last_filled_ms {
        Some(t) => {
            w.u8(1);
            w.i64(t);
        }
        None => w.u8(0),
    }
    w.u8(e.history.len() as u8);
    for (p, t) in &e.history {
        w.str(p.expose());
        w.i64(*t);
    }
}

fn decode(r: &mut Reader) -> Result<Entry, VaultError> {
    let id = r.id()?;
    let title = r.str()?;
    let mut domains = Vec::new();
    for _ in 0..r.count(MAX_LIST, 4)? {
        domains.push(r.str()?);
    }
    let mut packages = Vec::new();
    for _ in 0..r.count(MAX_LIST, 4)? {
        packages.push(r.str()?);
    }
    let username = r.str()?;
    let password = SecretString::new(r.str()?);
    let notes = r.str()?;
    let created_ms = r.i64()?;
    let edited_ms = r.i64()?;
    let last_filled_ms = match r.u8()? {
        0 => None,
        1 => Some(r.i64()?),
        _ => return Err(VaultError::Corrupt),
    };
    let mut history = Vec::new();
    let n = r.u8()? as usize;
    if n > MAX_HISTORY {
        return Err(VaultError::TooLarge);
    }
    for _ in 0..n {
        history.push((SecretString::new(r.str()?), r.i64()?));
    }
    Ok(Entry { id, title, domains, packages, username, password, notes, created_ms, edited_ms, last_filled_ms, history })
}

#[derive(Debug)]
pub struct Vault {
    entries: Vec<Entry>,
    /// Encoded size of the plaintext, kept up to date so the 64 MiB bound is checked in O(1).
    size: usize,
}

impl Default for Vault {
    fn default() -> Self {
        Self::new()
    }
}

impl Vault {
    pub fn new() -> Self {
        Vault { entries: Vec::new(), size: HEADER }
    }

    pub fn len(&self) -> usize {
        self.entries.len()
    }

    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    pub fn entries(&self) -> &[Entry] {
        &self.entries
    }

    pub fn get(&self, id: &[u8; 16]) -> Option<&Entry> {
        self.entries.iter().find(|e| &e.id == id)
    }

    /// Adds an entry; an entry with the same id is replaced. `TooLarge` (and no change) when a field, a list or the
    /// whole vault would exceed its limit.
    pub fn add(&mut self, entry: Entry) -> Result<(), VaultError> {
        validate(&entry)?;
        let existing = self.entries.iter().position(|e| e.id == entry.id);
        let old_len = existing.map_or(0, |i| encoded_len(&self.entries[i]));
        if existing.is_none() && self.entries.len() >= MAX_ENTRIES {
            return Err(VaultError::TooLarge);
        }
        let new_size = self.size - old_len + encoded_len(&entry);
        if new_size > MAX_PLAIN {
            return Err(VaultError::TooLarge);
        }
        self.size = new_size;
        match existing {
            Some(i) => self.entries[i] = entry,
            None => self.entries.push(entry),
        }
        Ok(())
    }

    /// Applies `f` to a copy of the entry and commits it if the result is within the limits: `Ok(true)` edited,
    /// `Ok(false)` no such entry, `Err(TooLarge)` refused with the entry unchanged. When `f` changes the password the
    /// old one goes to the front of `history` with the entry's new `edited_ms`; the id cannot be changed.
    pub fn edit(&mut self, id: &[u8; 16], f: impl FnOnce(&mut Entry)) -> Result<bool, VaultError> {
        let Some(i) = self.entries.iter().position(|e| &e.id == id) else {
            return Ok(false);
        };
        let mut e = self.entries[i].clone();
        let old_password = e.password.clone();
        f(&mut e);
        e.id = *id;
        if e.password != old_password {
            e.history.insert(0, (old_password, e.edited_ms));
            e.history.truncate(MAX_HISTORY);
        }
        validate(&e)?;
        let new_size = self.size - encoded_len(&self.entries[i]) + encoded_len(&e);
        if new_size > MAX_PLAIN {
            return Err(VaultError::TooLarge);
        }
        self.size = new_size;
        self.entries[i] = e;
        Ok(true)
    }

    pub fn delete(&mut self, id: &[u8; 16]) -> bool {
        match self.entries.iter().position(|e| &e.id == id) {
            Some(i) => {
                self.size -= encoded_len(&self.entries[i]);
                self.entries.remove(i);
                true
            }
            None => false,
        }
    }

    pub fn mark_filled(&mut self, id: &[u8; 16], now_ms: i64) {
        if let Some(i) = self.entries.iter().position(|e| &e.id == id) {
            let had = self.entries[i].last_filled_ms.is_some();
            self.entries[i].last_filled_ms = Some(now_ms);
            if !had {
                self.size += 8;
            }
        }
    }

    /// Case-insensitive substring search over title, domains, username and notes. Never the password.
    pub fn search(&self, query: &str) -> Vec<&Entry> {
        let q = query.to_lowercase();
        self.entries
            .iter()
            .filter(|e| {
                q.is_empty()
                    || e.title.to_lowercase().contains(&q)
                    || e.username.to_lowercase().contains(&q)
                    || e.notes.to_lowercase().contains(&q)
                    || e.domains.iter().any(|d| d.to_lowercase().contains(&q))
            })
            .collect()
    }

    pub fn to_sealed(&self, vk: &VaultKey) -> Vec<u8> {
        let mut w = Writer(Vec::with_capacity(self.size));
        w.u8(SCHEMA);
        w.u32(self.entries.len() as u32);
        for e in &self.entries {
            encode(e, &mut w);
        }
        let plain = Zeroizing::new(w.0);
        seal(vk.as_bytes(), VAULT_AAD, &plain)
    }

    pub fn from_sealed(blob: &[u8], vk: &VaultKey) -> Result<Vault, VaultError> {
        let plain = Zeroizing::new(open(vk.as_bytes(), VAULT_AAD, blob)?);
        let mut r = Reader::new(&plain);
        let schema = r.u8()?;
        if schema != SCHEMA {
            return Err(VaultError::UnsupportedVersion(schema as u16));
        }
        let n = r.count(MAX_ENTRIES, MIN_ENTRY)?;
        let mut entries = Vec::with_capacity(n);
        let mut seen = HashSet::with_capacity(n);
        for _ in 0..n {
            let e = decode(&mut r)?;
            if !seen.insert(e.id) {
                return Err(VaultError::Corrupt);
            }
            entries.push(e);
        }
        r.finish()?;
        Ok(Vault { entries, size: plain.len() })
    }
}
