# exit-daemon

`exitd`, the exit daemon of Compartments (ADR-184, ADR-178): a Rust process that reads the packets of the apps that the
platform routes to the Tor network and carries each uid's streams on its own Tor circuit family. The design is
`docs/superpowers/specs/2026-10-18-network-exit-design.md` and the plan
`docs/superpowers/plans/2026-10-20-compartments-exit-service.md` in `freeholdos-foundation`.

| Part | Role |
| --- | --- |
| `src/control.rs` | the typed control protocol (frames, bounds, memory policy) |
| `src/sockdiag.rs`, `src/sockdiag/sys.rs` | owner uid of a flow through `sock_diag` (only an established-flow state counts as an owner; a LISTEN or `TIME_WAIT` answer does not) and the startup `self_check`; `sys.rs` holds the only `unsafe` of this crate's own code (the vendored `tuntap.rs` and `scaffolding/linux.rs` have their own, not reviewed here) |
| `src/scaffolding.rs` | the `TunnelScaffolding` of the daemon: refuses what it cannot attribute to a uid, sets no mark |
| `src/config.rs` | the Arti configuration with an explicit memory quota |
| `src/server.rs`, `src/main.rs` | the control server (peer uid checked first) and the entry point |
| `third_party/onionmasq/` | the vendored library, see `SOURCE.md` |

## Build and test

The toolchain is the AOSP Rust 1.93.1 on the build server; nothing is built on the workstation.

    EXIT_BUILD_SSH=<user@build-server> tools/check-on-server.sh [cargo test arguments]

copies the tree to the server, runs `cargo test --locked --offline` through `tools/env.sh` and fails when no test ran.
`tools/build-android.sh` builds `exitd` for `aarch64-linux-android` and checks the binary itself, not the exit code.
`tools/run-mutations.py` applies single-line mutations to the sources and counts one as killed only when the tests ran and failed. `check-on-server.sh` tests the whole workspace, the vendored crates included.

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

## Known gaps before an image carries the daemon

Found by the whole-branch review of 2026-10-21; none is a defect of the tested core, all are work for milestones 3 and 4 or a
dedicated task. They are listed so that nobody reads the host tests as more than they are.

- Control protocol: no state query without a side effect (`State` is only a reply to a refresh or a quota request), no
  explicit acknowledgement or error reply (a refused quota request is answered like an accepted one, an accepted one is stored
  and applied nowhere), a `Hello` of another version gets no reply and the connection stays open, `Failures` is never sent,
  and `Ready` carries only the interface name. The exit app (milestone 4) needs these first.
- Control socket: mode 0600 and owned by the daemon, so the peer-uid check and the file mode must be reconciled with the
  exit app's uid (the socket from `init`, or a configurable mode); the `chmod` after `bind` is a short window.
- Availability: `sock_diag` is called synchronously in the packet loop (2 s timeouts) and opens a socket per flow; every flow
  allocates smoltcp buffers outside Arti's quota; the command channel is unbounded; Onionmasq's DNS limiter is global; the
  control server serves one connection at a time and an idle limit that resets on every byte.
- Parsers that app-controlled bytes reach (DNS decode, IPv4/IPv6 and extension headers) are not fuzzed and the vendored code
  has about 44 `unwrap`/`expect`/`assert` sites; `panic = "abort"` makes a panic an outage of every exit app. A fuzzing and
  panic audit is required before an image (design section 7).
- The Arti quota is checked at load time; its enforcement at run time is not shown. The configuration file is loaded twice
  (by the check and by the tunnel).
- The three-line `match` in the vendored `build_answer` that returns the SERVFAIL answer is not covered by a test.
- Licence aggregation for the 552 packages and `cargo audit` (see `NOTICE`).
- A TCP/UDP/IPv6 lookup was shown on the phone for TCP over IPv4 only; the self-check covers IPv4 loopback TCP and UDP.

