// SPDX-License-Identifier: Apache-2.0
//! The errors of the core. Each maps to one stable code at the JNI boundary (Task 8).

/// Why an operation on a vault, a blob or an export file failed.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum VaultError {
    /// The authentication tag did not verify. A wrong key, a wrong passphrase and a changed byte look the same on
    /// purpose: AEAD cannot tell them apart, and nothing here tries to.
    BadCredentials,
    /// The bytes are not a well-formed blob (too short, wrong magic, impossible lengths).
    Corrupt,
    /// A well-formed blob of a format version this build does not read.
    UnsupportedVersion(u16),
    /// A size or a parameter above the limits of the core, refused before anything is allocated or hashed.
    TooLarge,
    /// Reading or writing a file failed.
    Io,
}

impl core::fmt::Display for VaultError {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        match self {
            VaultError::BadCredentials => f.write_str("bad credentials"),
            VaultError::Corrupt => f.write_str("corrupt data"),
            VaultError::UnsupportedVersion(v) => write!(f, "unsupported version {v}"),
            VaultError::TooLarge => f.write_str("too large"),
            VaultError::Io => f.write_str("i/o error"),
        }
    }
}
