// SPDX-License-Identifier: Apache-2.0
//! Export and import: the only way a vault leaves the app.
//!
//! File: `"FHPX" | u8 version (1) | u32 m_kib | u32 t | u32 p | salt (16) | envelope`, all integers little endian. The
//! key is `HKDF-SHA256(Argon2id(export passphrase, salt, params))`: it has nothing to do with the vault key or the
//! hardware secret, so an export opens on any device with the passphrase alone. The header is bound into the
//! authenticated data. The decrypted payload is JSON, so the plaintext format is open:
//! `{"format":"freeholdos-passwords-export","version":1,"entries":[{"id":"<32 hex>","title":..,"domains":[..],
//! "packages":[..],"username":..,"password":..,"notes":..,"created_ms":..,"edited_ms":..,"last_filled_ms":null|..,
//! "history":[{"password":..,"replaced_ms":..}]}]}`.
//!
//! Everything read is bounded: 64 MiB of input, 100 000 entries, 64 KiB per field, 10 history items, and the Argon2
//! cost in the header is validated before any hashing.
//! Residual: the JSON text and the parsed values hold passwords in heap copies that are wiped only where this module
//! owns the buffer; they live for the duration of one export or import.

use hkdf::Hkdf;
use rand::rngs::OsRng;
use rand::RngCore;
use serde_json::{json, Map, Value};
use sha2::Sha256;
use zeroize::Zeroizing;

use crate::envelope::{open, seal, MAX_BLOB};
use crate::error::{fatal, VaultError};
use crate::kdf::{argon2id, KdfParams};
use crate::secret::SecretString;
use crate::vault::{validate, Entry, Vault, MAX_ENTRIES, MAX_FIELD, MAX_HISTORY, MAX_LIST};

const MAGIC: &[u8; 4] = b"FHPX";
const VERSION: u8 = 1;
const HEADER: usize = 4 + 1 + 12 + 16;
const FORMAT: &str = "freeholdos-passwords-export";
const AAD: &[u8] = b"freeholdos-passwords/export/v1";
const KEY_INFO: &[u8] = b"freeholdos-passwords/export-key/v1";

pub(crate) fn random_16() -> [u8; 16] {
    let mut b = [0u8; 16];
    if OsRng.try_fill_bytes(&mut b).is_err() {
        fatal();
    }
    b
}

fn header(params: &KdfParams, salt: &[u8; 16]) -> [u8; HEADER] {
    let mut h = [0u8; HEADER];
    h[..4].copy_from_slice(MAGIC);
    h[4] = VERSION;
    h[5..9].copy_from_slice(&params.m_kib.to_le_bytes());
    h[9..13].copy_from_slice(&params.t.to_le_bytes());
    h[13..17].copy_from_slice(&params.p.to_le_bytes());
    h[17..33].copy_from_slice(salt);
    h
}

fn export_key(passphrase: &[u8], salt: &[u8; 16], params: &KdfParams) -> Result<Zeroizing<[u8; 32]>, VaultError> {
    let ikm = argon2id(passphrase, salt, params)?;
    let mut key = Zeroizing::new([0u8; 32]);
    Hkdf::<Sha256>::new(None, &*ikm).expand(KEY_INFO, &mut *key).map_err(|_| VaultError::Corrupt)?;
    Ok(key)
}

fn aad_for(header: &[u8]) -> Vec<u8> {
    [AAD, header].concat()
}

/// Encrypts `payload` into an export file. Public so that tests can build hostile payloads; the app uses [`export`].
pub fn seal_payload(payload: &[u8], passphrase: &[u8], params: &KdfParams) -> Result<Vec<u8>, VaultError> {
    let params = params.validated()?;
    let salt = random_16();
    let h = header(&params, &salt);
    let key = export_key(passphrase, &salt, &params)?;
    let mut out = h.to_vec();
    out.extend_from_slice(&seal(&key, &aad_for(&h), payload));
    Ok(out)
}

fn hex(id: &[u8; 16]) -> String {
    id.iter().map(|b| format!("{b:02x}")).collect()
}

fn unhex(s: &str) -> Result<[u8; 16], VaultError> {
    if s.len() != 32 || !s.is_ascii() {
        return Err(VaultError::Corrupt);
    }
    let mut id = [0u8; 16];
    for (i, b) in id.iter_mut().enumerate() {
        *b = u8::from_str_radix(&s[2 * i..2 * i + 2], 16).map_err(|_| VaultError::Corrupt)?;
    }
    Ok(id)
}

/// Encrypts every entry of the vault under its own passphrase. `params` are validated (`TooLarge`, `Corrupt`).
pub fn export(vault: &Vault, passphrase: &[u8], params: &KdfParams) -> Result<Vec<u8>, VaultError> {
    let entries: Vec<Value> = vault
        .entries()
        .iter()
        .map(|e| {
            json!({
                "id": hex(&e.id),
                "title": e.title,
                "domains": e.domains,
                "packages": e.packages,
                "username": e.username,
                "password": e.password.expose(),
                "notes": e.notes,
                "created_ms": e.created_ms,
                "edited_ms": e.edited_ms,
                "last_filled_ms": e.last_filled_ms,
                "history": e.history.iter().map(|(p, t)| json!({"password": p.expose(), "replaced_ms": t})).collect::<Vec<_>>(),
            })
        })
        .collect();
    let payload = json!({"format": FORMAT, "version": 1, "entries": entries});
    let bytes = Zeroizing::new(serde_json::to_vec(&payload).map_err(|_| VaultError::Corrupt)?);
    seal_payload(&bytes, passphrase, params)
}

fn take_str(o: &mut Map<String, Value>, key: &str) -> Result<String, VaultError> {
    match o.remove(key) {
        Some(Value::String(s)) if s.len() <= MAX_FIELD => Ok(s),
        Some(Value::String(_)) => Err(VaultError::TooLarge),
        _ => Err(VaultError::Corrupt),
    }
}

fn take_i64(o: &mut Map<String, Value>, key: &str) -> Result<i64, VaultError> {
    o.remove(key).and_then(|v| v.as_i64()).ok_or(VaultError::Corrupt)
}

fn take_strings(o: &mut Map<String, Value>, key: &str) -> Result<Vec<String>, VaultError> {
    let Some(Value::Array(items)) = o.remove(key) else { return Err(VaultError::Corrupt) };
    if items.len() > MAX_LIST {
        return Err(VaultError::TooLarge);
    }
    items
        .into_iter()
        .map(|v| match v {
            Value::String(s) if s.len() <= MAX_FIELD => Ok(s),
            Value::String(_) => Err(VaultError::TooLarge),
            _ => Err(VaultError::Corrupt),
        })
        .collect()
}

fn entry_from(value: Value) -> Result<Entry, VaultError> {
    let Value::Object(mut o) = value else { return Err(VaultError::Corrupt) };
    let id = unhex(&take_str(&mut o, "id")?)?;
    let title = take_str(&mut o, "title")?;
    let domains = take_strings(&mut o, "domains")?;
    let packages = take_strings(&mut o, "packages")?;
    let username = take_str(&mut o, "username")?;
    let password = SecretString::new(take_str(&mut o, "password")?);
    let notes = take_str(&mut o, "notes")?;
    let created_ms = take_i64(&mut o, "created_ms")?;
    let edited_ms = take_i64(&mut o, "edited_ms")?;
    let last_filled_ms = match o.remove("last_filled_ms") {
        Some(Value::Null) => None,
        Some(v) => Some(v.as_i64().ok_or(VaultError::Corrupt)?),
        None => return Err(VaultError::Corrupt),
    };
    let Some(Value::Array(items)) = o.remove("history") else { return Err(VaultError::Corrupt) };
    if items.len() > MAX_HISTORY {
        return Err(VaultError::TooLarge);
    }
    let mut history = Vec::with_capacity(items.len());
    for item in items {
        let Value::Object(mut h) = item else { return Err(VaultError::Corrupt) };
        history.push((SecretString::new(take_str(&mut h, "password")?), take_i64(&mut h, "replaced_ms")?));
    }
    let entry = Entry { id, title, domains, packages, username, password, notes, created_ms, edited_ms, last_filled_ms, history };
    validate(&entry)?;
    Ok(entry)
}

/// Opens an export file. Ids that occur twice in the file are renamed (the first keeps its id), never merged.
pub fn import(blob: &[u8], passphrase: &[u8]) -> Result<Vec<Entry>, VaultError> {
    if blob.len() > MAX_BLOB + HEADER {
        return Err(VaultError::TooLarge);
    }
    if blob.len() < HEADER || &blob[..4] != MAGIC {
        return Err(VaultError::Corrupt);
    }
    if blob[4] != VERSION {
        return Err(VaultError::UnsupportedVersion(u16::from(blob[4])));
    }
    let word = |at: usize| u32::from_le_bytes([blob[at], blob[at + 1], blob[at + 2], blob[at + 3]]);
    let params = KdfParams { m_kib: word(5), t: word(9), p: word(13) }.validated()?;
    let mut salt = [0u8; 16];
    salt.copy_from_slice(&blob[17..33]);
    let key = export_key(passphrase, &salt, &params)?;
    let payload = Zeroizing::new(open(&key, &aad_for(&blob[..HEADER]), &blob[HEADER..])?);

    let Value::Object(mut root) = serde_json::from_slice::<Value>(&payload).map_err(|_| VaultError::Corrupt)? else {
        return Err(VaultError::Corrupt);
    };
    if root.remove("format").as_ref().and_then(Value::as_str) != Some(FORMAT) {
        return Err(VaultError::Corrupt);
    }
    match root.remove("version").and_then(|v| v.as_i64()) {
        Some(1) => {}
        Some(v) => return Err(VaultError::UnsupportedVersion(u16::try_from(v).unwrap_or(u16::MAX))),
        None => return Err(VaultError::Corrupt),
    }
    let Some(Value::Array(items)) = root.remove("entries") else { return Err(VaultError::Corrupt) };
    if items.len() > MAX_ENTRIES {
        return Err(VaultError::TooLarge);
    }
    let mut seen = std::collections::HashSet::with_capacity(items.len());
    let mut entries = Vec::with_capacity(items.len());
    for item in items {
        let mut e = entry_from(item)?;
        while !seen.insert(e.id) {
            e.id = random_16();
        }
        entries.push(e);
    }
    Ok(entries)
}

/// Adds imported entries to a vault. An entry whose id is already in the vault gets a new id; nothing is overwritten.
/// All or nothing: on an error (a limit of the vault) the vault is unchanged. Returns the number added.
pub fn merge_into(vault: &mut Vault, entries: Vec<Entry>) -> Result<usize, VaultError> {
    let mut work = vault.clone();
    let count = entries.len();
    for mut e in entries {
        while work.get(&e.id).is_some() {
            e.id = random_16();
        }
        work.add(e)?;
    }
    *vault = work;
    Ok(count)
}
