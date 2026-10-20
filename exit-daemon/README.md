# exit-daemon

`exitd`, the exit daemon of Compartments (ADR-184, ADR-178): a Rust process that reads the packets of the apps that the
platform routes to the Tor network and carries each uid's streams on its own Tor circuit family. The design is
`docs/superpowers/specs/2026-10-18-network-exit-design.md` and the plan
`docs/superpowers/plans/2026-10-20-compartments-exit-service.md` in `freeholdos-foundation`.

| Part | Role |
| --- | --- |
| `src/control.rs` | the typed control protocol (frames, bounds, memory policy) |
| `src/sockdiag.rs`, `src/sockdiag/sys.rs` | owner uid of a flow through `sock_diag`; `sys.rs` holds the only `unsafe` |
| `src/scaffolding.rs` | the `TunnelScaffolding` of the daemon: refuses what it cannot attribute to a uid, sets no mark |
| `src/config.rs` | the Arti configuration with an explicit memory quota |
| `src/server.rs`, `src/main.rs` | the control server (peer uid checked first) and the entry point |
| `third_party/onionmasq/` | the vendored library, see `SOURCE.md` |

## Build and test

The toolchain is the AOSP Rust 1.93.1 on the build server; nothing is built on the workstation.

    EXIT_BUILD_SSH=<user@build-server> tools/check-on-server.sh [cargo test arguments]

copies the tree to the server, runs `cargo test --locked --offline` through `tools/env.sh` and fails when no test ran.
`tools/build-android.sh` builds `exitd` for `aarch64-linux-android` and checks the binary itself, not the exit code.
`tools/run-mutations.py` applies single-line mutations to the sources and requires the tests to kill every one.

`Cargo.lock` is committed and every build uses `--locked`. The release profile sets `panic = "abort"`: the Android
standard library of the AOSP tree has no unwind strategy, so any panic ends the process and the init service restarts
it (milestone 3).

## Memory

Arti's memory quota (`system.memory.max`) is set explicitly from the configuration and the daemon refuses to start when
that setting does not load; the library default is several GiB. The quota is approximate by design (`tor-memquota`): a
hard limit is a job of the service definition, not of this crate.

The key is `system.memory.max` with the quota as an integer number of bytes (the loader accepts it). After the file is
written it is loaded the way the tunnel will load it and the quota is read back from the loaded configuration; Arti ignores
an unknown key without an error, so this read-back is what keeps the default from staying in force. `tor_memquota::Config`
has no public accessor (`system_memory()` needs arti-client's `testing` feature, which a production build must not enable),
so the read-back matches the `Debug` text `max: ByteQty(<bytes>)`; a changed text fails closed, and the unit tests show it at
the next Arti upgrade.
