# Source record: vault-core dependencies

`vault-core` is the Rust core of the Passwords app (ADR-185). Every dependency is a crate from crates.io, pinned in
`Cargo.lock` (73 packages, each with the SHA-256 `checksum` that cargo verifies on download) and built with
`--locked --offline` by the AOSP Rust 1.93.1 (`tools/env.sh`). The lock was resolved and the crates fetched on the build
server on 2026-10-21 with the owner's yes (`tools/update-lock.sh`); the fetched archives are 14 MB, 123 MB unpacked.

| Crate | Version | SHA-256 of the crate archive | Licence | Used for |
| --- | --- | --- | --- | --- |
| `argon2` | 0.5.3 | `3c3610892ee6e0cbce8ae2700349fcf8f98adb0dbfbee85aec3c9179d29cc072` | MIT OR Apache-2.0 | passphrase hashing (Task 3) |
| `chacha20poly1305` | 0.10.1 | `10cd79432192d1c0f4e1a0fef9527696cc039165d729fb41b3f4f4f354c2dc35` | Apache-2.0 OR MIT | the sealed envelope (Task 2) |
| `hkdf` | 0.12.4 | `7b5f8eb2ad728638ea2c7d47a21db23b7b58a72ed6a38256b8a1849f15fbbdf7` | MIT OR Apache-2.0 | key derivation (Task 3) |
| `hmac` | 0.12.1 | `6c49c37c09c17a53d937dfbb742eb3a961d65a994e6bcdcf37e7399d0cc8ab5e` | MIT OR Apache-2.0 | domain index (Task 5) |
| `sha2` | 0.10.9 | `a7507d819769d01a365ab707794a4084392c824f54a7a6a7862f8c3d0892b283` | MIT OR Apache-2.0 | HMAC and HKDF hash |
| `psl` | 2.1.244 | `bc3ac0c25b0c40554a755e56dfe268855c53f391bbb7922a1c1fe59b73b163a9` | MIT/Apache-2.0 | registrable domain (Task 5) |
| `zeroize` | 1.9.1 | `e13084392c5e4bc371903e2935a5eaeed24905a7511356b883835e18a78f6879` | Apache-2.0 OR MIT | wiping secrets |
| `subtle` | 2.6.1 | `13c2bddecc57b384dee18652358fb23172facb8a2c51ccc10d74c157bdea3292` | BSD-3-Clause | constant-time comparison |
| `rand` | 0.8.8 | `e058c7de0b26af77780c769414d6257830bb240f3c38477dbc2c16e5f54d6d4c` | MIT OR Apache-2.0 | nonces, salts, the generator |
| `serde_json` | 1.0.154 | `e7e9cc8b1b85264074fbcc02a88680c4096b1e47df8f739dceb03bf482f04bd6` | MIT OR Apache-2.0 | the export file (Task 7) |
| `jni` | 0.21.1 | `1a87aa2bb7d2af34197c04845522473242e1aa17c12f4935d5856491a7fb8c97` | MIT/Apache-2.0 | the JNI surface (Task 8) |

The checksums are the `checksum` fields of `Cargo.lock`. The lock holds no network crate (no `tokio`, `hyper`,
`reqwest`, `openssl`, `rustls`, `mio`, `socket2`); the build check also fails when the library imports a socket function
or links a network library.

`psl` compiles in the Public Suffix List (`data/rules.txt`, 158 kB in this version). The list is Mozilla's, published at
publicsuffix.org [assumption: MPL-2.0, not read from a licence file in the crate; the crate ships its own MIT licence
only]. It is a snapshot at the crate's release, so a suffix added later is unknown to the app until the crate is updated;
a licence review of the list and a refresh rule belong before shipping (Task 17).

## Why the plan's `android-crates-io` line differs

The plan named `external/rust/android-crates-io` as the source. That tree holds `jni`, `zeroize`, `rand`, `serde_json`
and `subtle`, and none of the RustCrypto crates or their dependencies (`digest`, `crypto-common`, `cipher`, `aead`,
`poly1305`, `chacha20`, `password-hash`, `generic-array`, `block-buffer`; read on the build server, 603 crates).
A standalone cargo build cannot reach that copy either, so every crate comes from crates.io through the lock, the way
`exit-daemon` does it.

## Build record

| Item | Value (Task 1, 2026-10-21) |
| --- | --- |
| Toolchain | AOSP `prebuilts/rust-toolchain` 1.93.1, target `aarch64-linux-android`, `--release`, `panic = "abort"`, `lto`, `opt-level = "z"` |
| `libvault_core.so` | 1 336 264 bytes, SHA-256 `4069ed693fe70d12c82ac83e5da62ab7433658e2718aff0b7b8203d1eb8024ed` |
| Reproducible | two builds into two target directories gave the same SHA-256 |
| Needed libraries | `libc.so`, `libdl.so` (`readelf -d`, read on the workstation) |
| Exports | `freeholdos_vault_selftest` |

The size and hash are snapshots of the Task 1 build (a known-answer self-check of each crate, no vault code yet); they
change with the first real module and are re-recorded by Task 9.
