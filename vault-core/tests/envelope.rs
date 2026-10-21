// SPDX-License-Identifier: Apache-2.0
use std::collections::HashSet;
use vault_core::envelope::{open, seal, MAX_BLOB};
use vault_core::error::VaultError;

const KEY: [u8; 32] = [0x42; 32];
const AAD: &[u8] = b"freeholdos-passwords/test";
/// magic 4 + version 2 + nonce 24 + tag 16 = 46 bytes of overhead: 154 bytes of plaintext make a 200-byte blob.
const PLAINTEXT_FOR_200: usize = 154;

fn blob_200() -> Vec<u8> {
    let plain: Vec<u8> = (0..PLAINTEXT_FOR_200).map(|i| i as u8).collect();
    let blob = seal(&KEY, AAD, &plain);
    assert_eq!(blob.len(), 200);
    blob
}

#[test]
fn roundtrip() {
    let plain = b"correct horse battery staple".to_vec();
    let blob = seal(&KEY, AAD, &plain);
    assert_eq!(open(&KEY, AAD, &blob).unwrap(), plain);
}

#[test]
fn empty_plaintext_roundtrips() {
    let blob = seal(&KEY, AAD, b"");
    assert_eq!(open(&KEY, AAD, &blob).unwrap(), Vec::<u8>::new());
}

#[test]
fn flipping_any_single_byte_fails() {
    let blob = blob_200();
    for i in 0..blob.len() {
        let mut bad = blob.clone();
        bad[i] ^= 0x01;
        assert!(open(&KEY, AAD, &bad).is_err(), "byte {i} flipped but the blob opened");
    }
}

#[test]
fn wrong_aad_fails() {
    let blob = seal(&KEY, AAD, b"x");
    assert!(open(&KEY, b"another context", &blob).is_err());
}

#[test]
fn wrong_key_is_bad_credentials() {
    let blob = seal(&KEY, AAD, b"x");
    assert_eq!(open(&[0x43; 32], AAD, &blob), Err(VaultError::BadCredentials));
}

#[test]
fn every_truncation_fails_without_panic() {
    let blob = blob_200();
    for len in 0..blob.len() {
        assert!(open(&KEY, AAD, &blob[..len]).is_err(), "a blob cut to {len} bytes opened");
    }
}

#[test]
fn version_two_is_unsupported() {
    let mut blob = seal(&KEY, AAD, b"x");
    blob[4..6].copy_from_slice(&2u16.to_le_bytes());
    assert_eq!(open(&KEY, AAD, &blob), Err(VaultError::UnsupportedVersion(2)));
}

#[test]
fn a_wrong_magic_is_corrupt() {
    let mut blob = seal(&KEY, AAD, b"x");
    blob[0] ^= 0xff;
    assert_eq!(open(&KEY, AAD, &blob), Err(VaultError::Corrupt));
}

#[test]
fn an_oversized_blob_is_refused_before_any_decryption() {
    let blob = vec![0u8; MAX_BLOB + 1];
    assert_eq!(open(&KEY, AAD, &blob), Err(VaultError::TooLarge));
}

#[test]
fn nonces_are_unique() {
    let mut seen = HashSet::new();
    for _ in 0..10_000 {
        assert!(seen.insert(seal(&KEY, AAD, b"same plaintext")), "two seals gave the same blob");
    }
}

#[test]
fn a_blob_of_exactly_the_limit_is_structure_checked_not_too_large() {
    assert_eq!(open(&KEY, AAD, &vec![0u8; MAX_BLOB]), Err(VaultError::Corrupt));
}
