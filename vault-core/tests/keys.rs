// SPDX-License-Identifier: Apache-2.0
use std::time::Instant;
use vault_core::error::VaultError;
use vault_core::kdf::KdfParams;
use vault_core::keys::{unwrap_with_passphrase, wrap_with_passphrase, VaultKey};

/// Small parameters keep the host tests fast; the production default is measured on a device (Task 17).
const FAST: KdfParams = KdfParams { m_kib: 64, t: 1, p: 1 };
const SALT: [u8; 16] = [0x11; 16];
const HW: [u8; 32] = [0x22; 32];

#[test]
fn argon2id_matches_rfc9106_vector() {
    use argon2::{Algorithm, Argon2, AssociatedData, ParamsBuilder, Version};
    // RFC 9106 section 5.3: Argon2id, 32 KiB, 3 passes, 4 lanes, 32-byte tag.
    let params = ParamsBuilder::new()
        .m_cost(32)
        .t_cost(3)
        .p_cost(4)
        .data(AssociatedData::new(&[0x04; 12]).unwrap())
        .output_len(32)
        .build()
        .unwrap();
    let argon = Argon2::new_with_secret(&[0x03; 8], Algorithm::Argon2id, Version::V0x13, params).unwrap();
    let mut tag = [0u8; 32];
    argon.hash_password_into(&[0x01; 32], &[0x02; 16], &mut tag).unwrap();
    let expected: [u8; 32] = [
        0x0d, 0x64, 0x0d, 0xf5, 0x8d, 0x78, 0x76, 0x6c, 0x08, 0xc0, 0x37, 0xa3, 0x4a, 0x8b, 0x53, 0xc9, 0xd0, 0x1e, 0xf0,
        0x45, 0x2d, 0x75, 0xb6, 0x5e, 0xb5, 0x25, 0x20, 0xe9, 0x6b, 0x01, 0xe6, 0x59,
    ];
    assert_eq!(tag, expected);
}

#[test]
fn roundtrip_with_passphrase_and_hardware_secret() {
    let vk = VaultKey::generate();
    let wrapped = wrap_with_passphrase(&vk, b"tundra-wicker-48", &HW, &FAST, &SALT).unwrap();
    let back = unwrap_with_passphrase(&wrapped, b"tundra-wicker-48", &HW, &FAST, &SALT).unwrap();
    assert_eq!(back.as_bytes(), vk.as_bytes());
}

#[test]
fn wrong_passphrase_and_wrong_hw_secret_give_the_same_error() {
    let vk = VaultKey::generate();
    let wrapped = wrap_with_passphrase(&vk, b"right", &HW, &FAST, &SALT).unwrap();
    let wrong_pass = unwrap_with_passphrase(&wrapped, b"wrong", &HW, &FAST, &SALT).err();
    let wrong_hw = unwrap_with_passphrase(&wrapped, b"right", &[0x23; 32], &FAST, &SALT).err();
    assert_eq!(wrong_pass, Some(VaultError::BadCredentials));
    assert_eq!(wrong_hw, Some(VaultError::BadCredentials));
}

#[test]
fn other_params_or_salt_do_not_open() {
    let vk = VaultKey::generate();
    let wrapped = wrap_with_passphrase(&vk, b"right", &HW, &FAST, &SALT).unwrap();
    let other_t = KdfParams { t: 2, ..FAST };
    assert!(unwrap_with_passphrase(&wrapped, b"right", &HW, &other_t, &SALT).is_err());
    assert!(unwrap_with_passphrase(&wrapped, b"right", &HW, &FAST, &[0x12; 16]).is_err());
}

#[test]
fn hostile_params_are_refused_before_hashing() {
    let vk = VaultKey::generate();
    let wrapped = wrap_with_passphrase(&vk, b"right", &HW, &FAST, &SALT).unwrap();
    for hostile in [
        KdfParams { m_kib: 4_000_000, t: 1, p: 1 },
        KdfParams { m_kib: 262_145, t: 1, p: 1 },
        KdfParams { m_kib: 64, t: 11, p: 1 },
        KdfParams { m_kib: 64, t: 1, p: 5 },
    ] {
        let start = Instant::now();
        let r = unwrap_with_passphrase(&wrapped, b"right", &HW, &hostile, &SALT);
        assert_eq!(r.err(), Some(VaultError::TooLarge), "{hostile:?}");
        assert!(start.elapsed().as_millis() < 50, "{hostile:?} took {:?}", start.elapsed());
        assert_eq!(wrap_with_passphrase(&vk, b"right", &HW, &hostile, &SALT).err(), Some(VaultError::TooLarge));
    }
}

#[test]
fn zero_and_impossible_params_are_corrupt() {
    for bad in [
        KdfParams { m_kib: 0, t: 1, p: 1 },
        KdfParams { m_kib: 64, t: 0, p: 1 },
        KdfParams { m_kib: 64, t: 1, p: 0 },
        // Argon2 needs at least 8 KiB per lane.
        KdfParams { m_kib: 16, t: 1, p: 4 },
    ] {
        assert_eq!(bad.validated().err(), Some(VaultError::Corrupt), "{bad:?}");
    }
    assert!(KdfParams::default().validated().is_ok());
    assert_eq!(KdfParams::default(), KdfParams { m_kib: 65_536, t: 3, p: 1 });
}

#[test]
fn change_passphrase_keeps_the_vault_key() {
    let vk = VaultKey::generate();
    let old = wrap_with_passphrase(&vk, b"old", &HW, &FAST, &SALT).unwrap();
    let opened = unwrap_with_passphrase(&old, b"old", &HW, &FAST, &SALT).unwrap();
    let new = wrap_with_passphrase(&opened, b"new", &HW, &FAST, &SALT).unwrap();
    let again = unwrap_with_passphrase(&new, b"new", &HW, &FAST, &SALT).unwrap();
    assert_eq!(again.as_bytes(), vk.as_bytes());
    assert!(unwrap_with_passphrase(&new, b"old", &HW, &FAST, &SALT).is_err());
}

#[test]
fn a_truncated_wrapped_key_is_refused() {
    let vk = VaultKey::generate();
    let wrapped = wrap_with_passphrase(&vk, b"right", &HW, &FAST, &SALT).unwrap();
    for len in 0..wrapped.len() {
        assert!(unwrap_with_passphrase(&wrapped[..len], b"right", &HW, &FAST, &SALT).is_err());
    }
}
