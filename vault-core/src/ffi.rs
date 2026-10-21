// SPDX-License-Identifier: Apache-2.0
//! The one exported symbol of Task 1: it keeps the crypto crates reachable from the shared library, so that the
//! build check proves they link (an unreferenced crate is dropped by LTO). Task 8 replaces it with `jni.rs`,
//! which becomes the only file of the crate that allows `unsafe`.
#![allow(unsafe_code)]

/// 1 when every crate passes its known-answer check, 0 otherwise.
#[no_mangle]
pub extern "C" fn freeholdos_vault_selftest() -> i32 {
    i32::from(crate::selftest::run())
}
