// SPDX-License-Identifier: Apache-2.0
//! The vault file as the app stores it: the passphrase wrapper next to the sealed vault.
//!
//! `"FHPF" | u8 version (1) | u32 m_kib | u32 t | u32 p | salt (16) | u32 wrapped length | wrapped vault key | sealed vault`
//!
//! The biometric wrapper is not in this file: the Keystore makes it, the app stores it beside this file, and the core
//! never sees it. Changing the parameters or the salt cannot downgrade the passphrase wrapper: the KEK depends on both,
//! so the wrapped key stops opening.

#![deny(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::panic)]

use crate::codec::Reader;
use crate::envelope::MAX_BLOB;
use crate::error::VaultError;
use crate::kdf::KdfParams;

const MAGIC: &[u8; 4] = b"FHPF";
const VERSION: u8 = 1;
/// A wrapped 32-byte key is 78 bytes; anything much longer is not one.
const MAX_WRAPPED: usize = 128;
const MAX_FILE: usize = MAX_BLOB + 4 + 1 + 12 + 16 + 4 + MAX_WRAPPED;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FileHeader {
    pub params: KdfParams,
    pub salt: [u8; 16],
    pub wrapped: Vec<u8>,
}

/// Header and sealed vault in one buffer.
pub fn build_file(header: &FileHeader, sealed_vault: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(41 + header.wrapped.len() + sealed_vault.len());
    out.extend_from_slice(MAGIC);
    out.push(VERSION);
    out.extend_from_slice(&header.params.m_kib.to_le_bytes());
    out.extend_from_slice(&header.params.t.to_le_bytes());
    out.extend_from_slice(&header.params.p.to_le_bytes());
    out.extend_from_slice(&header.salt);
    out.extend_from_slice(&(header.wrapped.len() as u32).to_le_bytes());
    out.extend_from_slice(&header.wrapped);
    out.extend_from_slice(sealed_vault);
    out
}

/// Splits a file. The parameters are validated here, before anything uses them.
pub fn parse_file(file: &[u8]) -> Result<(FileHeader, &[u8]), VaultError> {
    if file.len() > MAX_FILE {
        return Err(VaultError::TooLarge);
    }
    let mut r = Reader::new(file);
    if r.take(4)? != MAGIC {
        return Err(VaultError::Corrupt);
    }
    let version = r.u8()?;
    if version != VERSION {
        return Err(VaultError::UnsupportedVersion(u16::from(version)));
    }
    let params = KdfParams { m_kib: r.u32()?, t: r.u32()?, p: r.u32()? }.validated()?;
    let mut salt = [0u8; 16];
    salt.copy_from_slice(r.take(16)?);
    let wrapped_len = r.u32()? as usize;
    if wrapped_len > MAX_WRAPPED {
        return Err(VaultError::Corrupt);
    }
    let wrapped = r.take(wrapped_len)?.to_vec();
    Ok((FileHeader { params, salt, wrapped }, r.rest()))
}
