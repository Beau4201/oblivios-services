// SPDX-License-Identifier: Apache-2.0
//! The safe layer under the JNI glue: sessions behind unguessable handles, stable error codes, and every operation of
//! the app as a function of bytes. `jni.rs` only converts Java arrays to slices and back, so everything that can go wrong
//! is in here, where the host tests reach it.
//!
//! Code reachable from the JNI surface may not panic (the release profile aborts the whole app process on a panic): no
//! `unwrap`, `expect`, indexing or `panic!`, checked by clippy through the deny list below.
#![deny(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::panic)]

use std::collections::HashMap;
use std::sync::{Mutex, MutexGuard, OnceLock, PoisonError};

use rand::rngs::OsRng;
use rand::RngCore;
use zeroize::Zeroizing;

use crate::checkup::{checkup as run_checkup, strength as run_strength, Strength, WeakReason};
use crate::codec::Writer;
use crate::container::{build_file, parse_file, FileHeader};
use crate::error::{fatal, VaultError};
use crate::exchange;
use crate::generator::{generate as run_generate, GenOpts};
use crate::index::{DomainIndex, Kind};
use crate::kdf::KdfParams;
use crate::keys::{unwrap_with_passphrase, wrap_with_passphrase, VaultKey};
pub use crate::vault::{decode_entry, encode_entry};
use crate::vault::{Entry, Vault};

/// What a call reports. The numbers are the wire contract with the Kotlin side and never change.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Code {
    Ok,
    BadCredentials,
    Corrupt,
    UnsupportedVersion,
    TooLarge,
    Io,
    DeadHandle,
    BadArgument,
}

impl Code {
    pub const fn code(self) -> u8 {
        match self {
            Code::Ok => 0,
            Code::BadCredentials => 1,
            Code::Corrupt => 2,
            Code::UnsupportedVersion => 3,
            Code::TooLarge => 4,
            Code::Io => 5,
            Code::DeadHandle => 6,
            Code::BadArgument => 7,
        }
    }
}

impl From<VaultError> for Code {
    fn from(e: VaultError) -> Code {
        match e {
            VaultError::BadCredentials => Code::BadCredentials,
            VaultError::Corrupt => Code::Corrupt,
            VaultError::UnsupportedVersion(_) => Code::UnsupportedVersion,
            VaultError::TooLarge => Code::TooLarge,
            VaultError::Io => Code::Io,
        }
    }
}

/// An open vault: the entries, the key that seals them and the passphrase wrapper that goes back into every file.
struct Session {
    vault: Vault,
    vk: VaultKey,
    header: FileHeader,
}

fn table() -> MutexGuard<'static, HashMap<u64, Session>> {
    static TABLE: OnceLock<Mutex<HashMap<u64, Session>>> = OnceLock::new();
    TABLE.get_or_init(|| Mutex::new(HashMap::new())).lock().unwrap_or_else(PoisonError::into_inner)
}

fn random_u64() -> u64 {
    let mut b = [0u8; 8];
    if OsRng.try_fill_bytes(&mut b).is_err() {
        fatal();
    }
    u64::from_le_bytes(b)
}

/// A random 63-bit number that is not a live handle. Handles are never counters, so one app cannot guess another's.
fn insert(session: Session) -> u64 {
    let mut t = table();
    loop {
        let h = random_u64() & (i64::MAX as u64);
        if h != 0 && !t.contains_key(&h) {
            t.insert(h, session);
            return h;
        }
    }
}

fn with_session<R>(handle: u64, f: impl FnOnce(&mut Session) -> Result<R, Code>) -> Result<R, Code> {
    let mut t = table();
    f(t.get_mut(&handle).ok_or(Code::DeadHandle)?)
}

fn file_of(s: &Session) -> Vec<u8> {
    build_file(&s.header, &s.vault.to_sealed(&s.vk))
}

fn key32(bytes: &[u8]) -> Result<[u8; 32], Code> {
    <[u8; 32]>::try_from(bytes).map_err(|_| Code::BadArgument)
}

fn id16(bytes: &[u8]) -> Result<[u8; 16], Code> {
    <[u8; 16]>::try_from(bytes).map_err(|_| Code::BadArgument)
}

/// A new vault: the file (passphrase wrapper and an empty sealed vault) and the vault key, which the app wraps with the
/// Keystore for the biometric path. `hw_secret` is the 32-byte output of the non-exportable Keystore HMAC key.
pub struct Created {
    pub file: Vec<u8>,
    pub vault_key: Zeroizing<[u8; 32]>,
}

pub fn create_with_params(passphrase: &[u8], hw_secret: &[u8], params: &KdfParams) -> Result<Created, Code> {
    if passphrase.is_empty() {
        return Err(Code::BadArgument);
    }
    let hw = key32(hw_secret)?;
    let params = params.validated()?;
    let vk = VaultKey::generate();
    let mut salt = [0u8; 16];
    if OsRng.try_fill_bytes(&mut salt).is_err() {
        fatal();
    }
    let wrapped = wrap_with_passphrase(&vk, passphrase, &hw, &params, &salt)?;
    let file = build_file(&FileHeader { params, salt, wrapped }, &Vault::new().to_sealed(&vk));
    Ok(Created { file, vault_key: Zeroizing::new(*vk.as_bytes()) })
}

pub fn create(passphrase: &[u8], hw_secret: &[u8]) -> Result<Created, Code> {
    create_with_params(passphrase, hw_secret, &KdfParams::default())
}

pub fn unlock_with_passphrase(file: &[u8], passphrase: &[u8], hw_secret: &[u8]) -> Result<u64, Code> {
    let hw = key32(hw_secret)?;
    let (header, sealed) = parse_file(file)?;
    let vk = unwrap_with_passphrase(&header.wrapped, passphrase, &hw, &header.params, &header.salt)?;
    let vault = Vault::from_sealed(sealed, &vk)?;
    Ok(insert(Session { vault, vk, header }))
}

pub fn unlock_with_vault_key(file: &[u8], vault_key: &[u8]) -> Result<u64, Code> {
    let vk = VaultKey::from_bytes(key32(vault_key)?);
    let (header, sealed) = parse_file(file)?;
    let vault = Vault::from_sealed(sealed, &vk)?;
    Ok(insert(Session { vault, vk, header }))
}

/// Wipes and forgets the session. Locking a handle that is already dead is not an error.
pub fn lock(handle: u64) -> Code {
    table().remove(&handle);
    Code::Ok
}

fn summary(e: &Entry, w: &mut Writer) {
    w.raw(&e.id);
    w.str(&e.title);
    w.str(&e.username);
    for list in [&e.domains, &e.packages] {
        w.u32(list.len() as u32);
        list.iter().for_each(|s| w.str(s));
    }
    w.i64(e.edited_ms);
    match e.last_filled_ms {
        Some(t) => {
            w.u8(1);
            w.i64(t);
        }
        None => w.u8(0),
    }
}

/// `u32 count | summaries`; a summary is `id | title | username | domains | packages | edited_ms | last filled`, and
/// holds no password and no notes.
fn summaries<'a>(entries: impl Iterator<Item = &'a Entry>) -> Vec<u8> {
    let list: Vec<&Entry> = entries.collect();
    let mut w = Writer::default();
    w.u32(list.len() as u32);
    list.into_iter().for_each(|e| summary(e, &mut w));
    w.0
}

/// The vault key of an open session, for the app to wrap with its biometric Keystore key. It passes through the JVM for
/// an instant (a residual of the design, recorded in the threat model); the app overwrites it at once.
pub fn vault_key(handle: u64) -> Result<Zeroizing<Vec<u8>>, Code> {
    with_session(handle, |s| Ok(Zeroizing::new(s.vk.as_bytes().to_vec())))
}

pub fn list(handle: u64) -> Result<Vec<u8>, Code> {
    with_session(handle, |s| Ok(summaries(s.vault.entries().iter())))
}

pub fn search(handle: u64, query: &str) -> Result<Vec<u8>, Code> {
    with_session(handle, |s| Ok(summaries(s.vault.search(query).into_iter())))
}

/// One entry in full, password included: the detail screen and the autofill service ask for it by id.
pub fn get(handle: u64, id: &[u8]) -> Result<Vec<u8>, Code> {
    let id = id16(id)?;
    with_session(handle, |s| s.vault.get(&id).map(|e| encode_entry(e).to_vec()).ok_or(Code::BadArgument))
}

/// Adds the entry or, when its id exists, replaces the editable fields (title, domains, packages, username, password,
/// notes, edited time). The creation time, the last fill and the history stay the vault's own; a changed password
/// pushes the old one into the history. Returns the new file, which the caller writes atomically.
pub fn add_or_edit(handle: u64, entry: &[u8]) -> Result<Vec<u8>, Code> {
    let e = decode_entry(entry)?;
    with_session(handle, |s| {
        if s.vault.get(&e.id).is_some() {
            let id = e.id;
            s.vault.edit(&id, move |cur| {
                cur.title = e.title;
                cur.domains = e.domains;
                cur.packages = e.packages;
                cur.username = e.username;
                cur.password = e.password;
                cur.notes = e.notes;
                cur.edited_ms = e.edited_ms;
            })?;
        } else {
            s.vault.add(e)?;
        }
        Ok(file_of(s))
    })
}

pub fn delete(handle: u64, id: &[u8]) -> Result<Vec<u8>, Code> {
    let id = id16(id)?;
    with_session(handle, |s| if s.vault.delete(&id) { Ok(file_of(s)) } else { Err(Code::BadArgument) })
}

pub fn mark_filled(handle: u64, id: &[u8], now_ms: i64) -> Result<Vec<u8>, Code> {
    let id = id16(id)?;
    with_session(handle, |s| {
        if s.vault.get(&id).is_none() {
            return Err(Code::BadArgument);
        }
        s.vault.mark_filled(&id, now_ms);
        Ok(file_of(s))
    })
}

/// `u32 groups | (u32 n | n ids)... | u32 weak | (id | reason)...`; reason 0 too short, 1 common, 2 pattern.
pub fn checkup(handle: u64) -> Result<Vec<u8>, Code> {
    with_session(handle, |s| {
        let c = run_checkup(&s.vault);
        let mut w = Writer::default();
        w.u32(c.reused.len() as u32);
        for group in &c.reused {
            w.u32(group.len() as u32);
            group.iter().for_each(|id| w.raw(id));
        }
        w.u32(c.weak.len() as u32);
        for (id, reason) in &c.weak {
            w.raw(id);
            w.u8(match reason {
                WeakReason::TooShort => 0,
                WeakReason::Common => 1,
                WeakReason::Pattern => 2,
            });
        }
        Ok(w.0)
    })
}

pub fn export_with_params(handle: u64, passphrase: &[u8], params: &KdfParams) -> Result<Vec<u8>, Code> {
    // The vault is copied out and the lock released first: hashing takes about a second and must not hold up `lock`.
    let vault = with_session(handle, |s| Ok(s.vault.clone()))?;
    Ok(exchange::export(&vault, passphrase, params)?)
}

pub fn export(handle: u64, passphrase: &[u8]) -> Result<Vec<u8>, Code> {
    export_with_params(handle, passphrase, &KdfParams::default())
}

/// Merges an export file into the open vault (ids that already exist get new ids; nothing is overwritten). Returns
/// `u32 count | new file`.
pub fn import_entries(handle: u64, blob: &[u8], passphrase: &[u8]) -> Result<Vec<u8>, Code> {
    with_session(handle, |_| Ok(()))?;
    let entries = exchange::import(blob, passphrase)?;
    with_session(handle, |s| {
        let count = exchange::merge_into(&mut s.vault, entries)?;
        let mut out = (count as u32).to_le_bytes().to_vec();
        out.extend_from_slice(&file_of(s));
        Ok(out)
    })
}

pub fn index_build(handle: u64, key: &[u8]) -> Result<Vec<u8>, Code> {
    let key = key32(key)?;
    with_session(handle, |s| Ok(DomainIndex::build(&key, &s.vault).to_bytes()))
}

/// `kind` 0 is a domain, 1 a package name; anything else, a bad key or a bad index answers `false`.
pub fn index_contains(index: &[u8], key: &[u8], subject: &str, kind: i32) -> bool {
    let (Ok(key), Ok(index)) = (key32(key), DomainIndex::from_bytes(index)) else { return false };
    match kind {
        0 => index.contains(&key, subject, Kind::Domain),
        1 => index.contains(&key, subject, Kind::Package),
        _ => false,
    }
}

pub fn generate(length: i32, lower: bool, upper: bool, digits: bool, symbols: bool) -> Result<Zeroizing<Vec<u8>>, Code> {
    let length = usize::try_from(length).map_err(|_| Code::BadArgument)?;
    let opts = GenOpts { length, lower, upper, digits, symbols };
    Ok(Zeroizing::new(run_generate(&mut OsRng, &opts)?.into_bytes()))
}

/// 0 weak, 1 fair, 2 good, 3 strong. Text that is not UTF-8 is weak.
pub fn strength(password: &[u8]) -> i32 {
    match core::str::from_utf8(password).map(run_strength) {
        Ok(Strength::Fair) => 1,
        Ok(Strength::Good) => 2,
        Ok(Strength::Strong) => 3,
        _ => 0,
    }
}

/// Known-answer check of every crate, for the first run on a device.
pub fn self_test() -> bool {
    crate::selftest::run()
}
