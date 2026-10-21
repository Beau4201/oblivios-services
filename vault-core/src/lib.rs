// SPDX-License-Identifier: Apache-2.0
//! Core of the FreeholdOS Passwords app (ADR-185).
#![deny(unsafe_code)]

mod ffi;
mod selftest;

/// Version of the core, from `Cargo.toml`.
pub fn core_version() -> &'static str {
    env!("CARGO_PKG_VERSION")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn core_version_is_non_empty() {
        assert!(!core_version().is_empty());
    }

    #[test]
    fn crypto_selftest_passes() {
        assert!(selftest::run());
    }

    #[test]
    fn exported_selftest_symbol_returns_one() {
        assert_eq!(ffi::freeholdos_vault_selftest(), 1);
    }
}
