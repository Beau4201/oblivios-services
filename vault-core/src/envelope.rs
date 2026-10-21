// SPDX-License-Identifier: Apache-2.0
//! The sealed envelope: XChaCha20-Poly1305 with a random 24-byte nonce per seal.
//!
//! Layout: `"FHPV" | u16 version (little endian, 1) | nonce (24) | ciphertext | tag (16)`. The header is bound into the
//! authenticated data together with the caller's `aad`, so a changed version or magic never opens.

use chacha20poly1305::aead::{Aead, KeyInit, Payload};
use chacha20poly1305::{Key, XChaCha20Poly1305, XNonce};
use rand::rngs::OsRng;
use rand::RngCore;

use crate::error::{fatal, VaultError};

const MAGIC: &[u8; 4] = b"FHPV";
const VERSION: u16 = 1;
const HEADER: usize = 6;
const NONCE: usize = 24;
const TAG: usize = 16;
/// The largest plaintext a blob carries (64 MiB: a vault of 100 000 entries is far below it), plus the overhead.
pub const MAX_BLOB: usize = 64 * 1024 * 1024 + HEADER + NONCE + TAG;

fn bound_aad(header: &[u8], aad: &[u8]) -> Vec<u8> {
    let mut v = Vec::with_capacity(header.len() + aad.len());
    v.extend_from_slice(header);
    v.extend_from_slice(aad);
    v
}

/// Seals `plaintext` under `key`; `aad` names the context (a vault file never opens as an export file).
pub fn seal(key: &[u8; 32], aad: &[u8], plaintext: &[u8]) -> Vec<u8> {
    let mut nonce = [0u8; NONCE];
    if OsRng.try_fill_bytes(&mut nonce).is_err() {
        fatal();
    }
    let mut out = Vec::with_capacity(HEADER + NONCE + plaintext.len() + TAG);
    out.extend_from_slice(MAGIC);
    out.extend_from_slice(&VERSION.to_le_bytes());
    out.extend_from_slice(&nonce);
    let cipher = XChaCha20Poly1305::new(Key::from_slice(key));
    let payload = Payload { msg: plaintext, aad: &bound_aad(&out[..HEADER], aad) };
    match cipher.encrypt(XNonce::from_slice(&nonce), payload) {
        Ok(ct) => out.extend_from_slice(&ct),
        Err(_) => fatal(),
    }
    out
}

/// Opens a blob made by [`seal`]. Structure is checked before anything is decrypted.
pub fn open(key: &[u8; 32], aad: &[u8], blob: &[u8]) -> Result<Vec<u8>, VaultError> {
    if blob.len() > MAX_BLOB {
        return Err(VaultError::TooLarge);
    }
    if blob.len() < HEADER + NONCE + TAG || &blob[..4] != MAGIC {
        return Err(VaultError::Corrupt);
    }
    let version = u16::from_le_bytes([blob[4], blob[5]]);
    if version != VERSION {
        return Err(VaultError::UnsupportedVersion(version));
    }
    let cipher = XChaCha20Poly1305::new(Key::from_slice(key));
    let payload = Payload { msg: &blob[HEADER + NONCE..], aad: &bound_aad(&blob[..HEADER], aad) };
    cipher
        .decrypt(XNonce::from_slice(&blob[HEADER..HEADER + NONCE]), payload)
        .map_err(|_| VaultError::BadCredentials)
}
