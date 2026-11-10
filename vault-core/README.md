# vault-core

The Rust core of the ObliviOS Passwords app (ADR-185): the sealed vault file, the key wrappers, the domain index, the
checkup and the export file. The Kotlin app in `oblivios-platform/passwords/` reaches it through one JNI module.

Status: Task 1 of the plan (`2026-10-20-password-manager.md` in `oblivios-foundation`) is the feasibility gate. The
crate holds the build tooling and a known-answer check of the dependencies; the vault modules follow task by task.

## Build and test

Nothing is built on the workstation. Both scripts copy the tree to the build server and run there with the AOSP Rust.

| Command | What it does |
| --- | --- |
| `VAULT_BUILD_SSH=user@host tools/check-on-server.sh [filter]` | `cargo test --locked --offline`; fails when no test ran |
| `VAULT_BUILD_SSH=user@host tools/build-android.sh` | builds `libvault_core.so` for `aarch64-linux-android` and checks the library itself (architecture, needed libraries, exported symbol, no network import, no build path, size) |
| `VAULT_BUILD_SSH=user@host tools/update-lock.sh` | after a `Cargo.toml` change: resolves and fetches (network), copies `Cargo.lock` back |

`tools/env.sh` is sourced on the server and needs a finished `out-lynx` build for the Bionic sysroot. Dependencies and
their checksums: [`SOURCE.md`](SOURCE.md).

## Rules of the crate

- `#![deny(unsafe_code)]` everywhere except `src/ffi.rs` (replaced by `jni.rs` in Task 8).
- `panic = "abort"`: every panic ends the process, so code reachable from the JNI surface validates its input first.
- No network crate in `Cargo.lock`, no network import in the library.
