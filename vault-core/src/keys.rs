// SPDX-License-Identifier: Apache-2.0
//! The vault key and its passphrase wrapper.
//!
//! The vault key is 256 random bits. The passphrase wrapper seals it under a KEK that needs two things: the passphrase
//! (through Argon2id) and the hardware secret (a non-exportable Keystore HMAC output, the HKDF salt). Losing either
//! gives `BadCredentials`, and the two cases cannot be told apart.

use hkdf::Hkdf;
use rand::rngs::OsRng;
use rand::RngCore;
use sha2::Sha256;
use zeroize::{Zeroize, Zeroizing};

use crate::envelope::{open, seal};
use crate::error::{fatal, VaultError};
use crate::kdf::{argon2id, KdfParams};

const KEK_INFO: &[u8] = b"oblivios-passwords/passphrase-kek/v1";
const WRAP_AAD: &[u8] = b"oblivios-passwords/wrapped-vault-key/v1";

/// The 256-bit key of the sealed vault. Wiped on drop; deliberately no `Debug` and no `Clone`.
pub struct VaultKey([u8; 32]);

impl VaultKey {
    pub fn generate() -> Self {
        let mut k = [0u8; 32];
        if OsRng.try_fill_bytes(&mut k).is_err() {
            fatal();
        }
        VaultKey(k)
    }

    pub fn from_bytes(bytes: [u8; 32]) -> Self {
        VaultKey(bytes)
    }

    pub fn as_bytes(&self) -> &[u8; 32] {
        &self.0
    }
}

impl Drop for VaultKey {
    fn drop(&mut self) {
        self.0.zeroize();
    }
}

/// `HKDF-SHA256(salt = hw_secret, ikm = Argon2id(passphrase, salt, params), info = KEK_INFO)`, 32 bytes.
pub(crate) fn derive_kek(
    passphrase: &[u8],
    hw_secret: &[u8; 32],
    params: &KdfParams,
    salt: &[u8; 16],
) -> Result<Zeroizing<[u8; 32]>, VaultError> {
    let ikm = argon2id(passphrase, salt, params)?;
    let mut kek = Zeroizing::new([0u8; 32]);
    Hkdf::<Sha256>::new(Some(hw_secret), &*ikm).expand(KEK_INFO, &mut *kek).map_err(|_| VaultError::Corrupt)?;
    Ok(kek)
}

/// Seals the vault key under the passphrase KEK. `params` are validated first (`TooLarge` or `Corrupt`).
pub fn wrap_with_passphrase(
    vk: &VaultKey,
    passphrase: &[u8],
    hw_secret: &[u8; 32],
    params: &KdfParams,
    salt: &[u8; 16],
) -> Result<Vec<u8>, VaultError> {
    let kek = derive_kek(passphrase, hw_secret, params, salt)?;
    Ok(seal(&kek, WRAP_AAD, vk.as_bytes()))
}

/// Opens a wrapped vault key. Hostile `params` are refused before any hashing; a wrong passphrase and a wrong hardware
/// secret both give `BadCredentials`.
pub fn unwrap_with_passphrase(
    wrapped: &[u8],
    passphrase: &[u8],
    hw_secret: &[u8; 32],
    params: &KdfParams,
    salt: &[u8; 16],
) -> Result<VaultKey, VaultError> {
    let params = params.validated()?;
    let kek = derive_kek(passphrase, hw_secret, &params, salt)?;
    let plain = Zeroizing::new(open(&kek, WRAP_AAD, wrapped)?);
    let bytes: [u8; 32] = plain.as_slice().try_into().map_err(|_| VaultError::Corrupt)?;
    Ok(VaultKey(bytes))
}

#[cfg(test)]
mod tests {
    use super::*;

    const P: KdfParams = KdfParams { m_kib: 64, t: 1, p: 1 };
    const GOLDEN_KEK: [u8; 32] = [
        158, 96, 81, 54, 125, 75, 28, 189, 14, 201, 29, 255, 182, 46, 202, 197, 211, 166, 78, 10, 44, 133, 147, 149, 111, 185, 77, 141, 151, 239, 180, 158,
    ];

    fn kek(pass: &[u8], hw: u8, salt: u8, params: &KdfParams) -> [u8; 32] {
        *derive_kek(pass, &[hw; 32], params, &[salt; 16]).unwrap()
    }

    /// Pins Argon2id (version 0x13) + HKDF-SHA256 with this info string: the RFC 9106 and RFC 5869 vectors prove the
    /// primitives, this proves how the core combines them. Changing the algorithm, the version, the cost handling or the
    /// info string changes the value, and every vault ever written stops opening.
    #[test]
    fn the_kek_for_fixed_inputs_never_changes() {
        let want: [u8; 32] = GOLDEN_KEK;
        assert_eq!(kek(b"pw", 1, 2, &P), want);
    }

    #[test]
    fn kek_is_deterministic() {
        assert_eq!(kek(b"pw", 1, 2, &P), kek(b"pw", 1, 2, &P));
    }

    #[test]
    fn kek_depends_on_the_hardware_secret() {
        assert_ne!(kek(b"pw", 1, 2, &P), kek(b"pw", 3, 2, &P));
    }

    #[test]
    fn kek_depends_on_the_passphrase_the_salt_and_the_params() {
        let base = kek(b"pw", 1, 2, &P);
        assert_ne!(base, kek(b"pW", 1, 2, &P));
        assert_ne!(base, kek(b"pw", 1, 3, &P));
        assert_ne!(base, kek(b"pw", 1, 2, &KdfParams { t: 2, ..P }));
    }
}
