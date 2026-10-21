// SPDX-License-Identifier: Apache-2.0
//! Known-answer check of every crate the core builds on. Not a substitute for the per-module tests of Tasks 2 to 7:
//! it proves that each crate compiled for this target and still computes what its specification says.

use argon2::{Algorithm, Argon2, Params, Version};
use chacha20poly1305::aead::{Aead, KeyInit};
use chacha20poly1305::{XChaCha20Poly1305, XNonce};
use hkdf::Hkdf;
use hmac::{Hmac, Mac};
use sha2::Sha256;

/// RFC 4231 test case 1.
fn hmac_sha256() -> Option<bool> {
    let mut mac = <Hmac<Sha256> as Mac>::new_from_slice(&[0x0b; 20]).ok()?;
    mac.update(b"Hi There");
    let tag = mac.finalize().into_bytes();
    Some(
        tag.as_slice()
            == [
                0xb0, 0x34, 0x4c, 0x61, 0xd8, 0xdb, 0x38, 0x53, 0x5c, 0xa8, 0xaf, 0xce, 0xaf, 0x0b, 0xf1, 0x2b, 0x88, 0x1d,
                0xc2, 0x00, 0xc9, 0x83, 0x3d, 0xa7, 0x26, 0xe9, 0x37, 0x6c, 0x2e, 0x32, 0xcf, 0xf7,
            ],
    )
}

/// RFC 5869 test case 1.
fn hkdf_sha256() -> Option<bool> {
    let salt: Vec<u8> = (0x00..=0x0c).collect();
    let info: Vec<u8> = (0xf0..=0xf9).collect();
    let hk = Hkdf::<Sha256>::new(Some(&salt), &[0x0b; 22]);
    let mut okm = [0u8; 42];
    hk.expand(&info, &mut okm).ok()?;
    Some(
        okm == [
            0x3c, 0xb2, 0x5f, 0x25, 0xfa, 0xac, 0xd5, 0x7a, 0x90, 0x43, 0x4f, 0x64, 0xd0, 0x36, 0x2f, 0x2a, 0x2d, 0x2d,
            0x0a, 0x90, 0xcf, 0x1a, 0x5a, 0x4c, 0x5d, 0xb0, 0x2d, 0x56, 0xec, 0xc4, 0xc5, 0xbf, 0x34, 0x00, 0x72, 0x08,
            0xd5, 0xb8, 0x87, 0x18, 0x58, 0x65,
        ],
    )
}

/// Argon2id with small parameters: deterministic, and the salt matters. The RFC 9106 vector is Task 3's test.
fn argon2id() -> Option<bool> {
    let argon = Argon2::new(Algorithm::Argon2id, Version::V0x13, Params::new(32, 1, 1, Some(32)).ok()?);
    let (mut a, mut b, mut c) = ([0u8; 32], [0u8; 32], [0u8; 32]);
    argon.hash_password_into(b"passphrase", b"saltsalt", &mut a).ok()?;
    argon.hash_password_into(b"passphrase", b"saltsalt", &mut b).ok()?;
    argon.hash_password_into(b"passphrase", b"saltSALT", &mut c).ok()?;
    Some(a == b && a != c && a != [0u8; 32])
}

/// XChaCha20-Poly1305: a round trip works and a flipped bit is refused.
fn xchacha() -> Option<bool> {
    let cipher = XChaCha20Poly1305::new(&[7u8; 32].into());
    let nonce = XNonce::from([9u8; 24]);
    let mut sealed = cipher.encrypt(&nonce, b"secret".as_slice()).ok()?;
    let opened = cipher.decrypt(&nonce, sealed.as_slice()).ok()?;
    sealed[0] ^= 1;
    Some(opened == b"secret" && cipher.decrypt(&nonce, sealed.as_slice()).is_err())
}

/// The public suffix list is compiled in.
fn suffix_list() -> Option<bool> {
    Some(psl::domain_str("login.example.co.uk")? == "example.co.uk")
}

/// True when every crate passes its check.
pub fn run() -> bool {
    [hmac_sha256(), hkdf_sha256(), argon2id(), xchacha(), suffix_list()]
        .iter()
        .all(|r| *r == Some(true))
}
