// SPDX-License-Identifier: Apache-2.0
//! Argon2id for the passphrase wrapper. The parameters live in the vault file, so they are hostile input until they
//! have passed [`KdfParams::validated`]: a file that asks for gigabytes must be refused before a byte is allocated.

use argon2::{Algorithm, Argon2, Params, Version};
use zeroize::Zeroizing;

use crate::error::VaultError;

/// Upper limits for parameters read from a file (a 256 MiB hash is already heavier than any phone should be asked for).
pub const MAX_M_KIB: u32 = 262_144;
pub const MAX_T: u32 = 10;
pub const MAX_P: u32 = 4;

/// Argon2id cost: memory in KiB, passes, lanes.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct KdfParams {
    pub m_kib: u32,
    pub t: u32,
    pub p: u32,
}

impl Default for KdfParams {
    /// 64 MiB, 3 passes, 1 lane. Measured on `lynx` and `stallion` by `PWM-011` (Task 17); the owner can change it there.
    fn default() -> Self {
        KdfParams { m_kib: 65_536, t: 3, p: 1 }
    }
}

impl KdfParams {
    /// `TooLarge` above the limits, `Corrupt` for zero values or fewer than 8 KiB per lane (Argon2's own minimum).
    pub fn validated(self) -> Result<Self, VaultError> {
        if self.m_kib == 0 || self.t == 0 || self.p == 0 {
            return Err(VaultError::Corrupt);
        }
        if self.m_kib > MAX_M_KIB || self.t > MAX_T || self.p > MAX_P {
            return Err(VaultError::TooLarge);
        }
        if self.m_kib < 8 * self.p {
            return Err(VaultError::Corrupt);
        }
        Ok(self)
    }
}

/// 32 bytes of Argon2id (version 0x13) over `passphrase` and `salt`; the parameters are validated first.
pub(crate) fn argon2id(passphrase: &[u8], salt: &[u8; 16], params: &KdfParams) -> Result<Zeroizing<[u8; 32]>, VaultError> {
    let p = params.validated()?;
    let params = Params::new(p.m_kib, p.t, p.p, Some(32)).map_err(|_| VaultError::Corrupt)?;
    let mut out = Zeroizing::new([0u8; 32]);
    Argon2::new(Algorithm::Argon2id, Version::V0x13, params)
        .hash_password_into(passphrase, salt, &mut *out)
        .map_err(|_| VaultError::Corrupt)?;
    Ok(out)
}
