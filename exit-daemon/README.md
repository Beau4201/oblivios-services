# exit-daemon

`exitd`, the exit daemon of Compartments (ADR-184, ADR-178, ADR-186): a Rust process that reads the packets of the apps that
the platform steers into its tun interface, answers the DNS of masked apps, and carries the connections of apps set to Tor and
of listed hosts on each app's own Tor circuit family. `exitd --routes` (the route helper, same binary) writes the rules that
steer packets into the tun and nothing else. The design is
`docs/superpowers/specs/2026-10-18-network-exit-design.md` and
`docs/superpowers/specs/2026-10-19-compartments-masked-destinations-design.md` in `freeholdos-foundation`, the plan
`docs/superpowers/plans/2026-10-21-compartments-close-out.md`.

| Part | Role |
| --- | --- |
| `src/control.rs` | the typed control protocol, version 3 (frames, sequence numbers, `Ack` and `Error`, uid classes, per-company overrides, `Failures`, `Promoted`, `Release`) |
| `src/policy.rs` | the class of each app uid (Tor, masked, exempt) and the overrides, as the exit app sent them |
| `src/masklist.rs` | the mask list: names matched by the longest domain suffix, protected rows (company 0) shield what is below them |
| `src/scaffolding.rs` | the `TunnelScaffolding` of the daemon: attributes every flow to a uid or refuses it, admits a TCP flow only for the class that may have it, decides per DNS question (Tor or pass on), limits DNS per app, bounds flows and commands, feeds the ladder |
| `src/guard.rs` | the speed guard (ADR-188): what each masked company sends to each app over Tor; 4 MiB within a minute passes the company's names on to that app for 30 minutes, and "Hide again" pins it hidden until the daemon restarts (counts and company ids only, bounded) |
| `src/ladder.rs`, `src/ratelimit.rs` | failure counts per app and company (10 minute window, 3 failures or one slow connection), and the DNS limit per app (100 a second, 2000 a minute) |
| `src/sockdiag.rs`, `src/sockdiag/sys.rs` | owner uid of a flow through `sock_diag` (only an established-flow state counts), `SO_PEERCRED`, `SO_PEERSEC`, the socket handed down by `init`; `sys.rs` holds the only `unsafe` of the daemon |
| `src/server.rs` | the control server: peer check before the first byte (by SELinux type and a uid of user 0, or by exact uid), a new connection of the peer replaces the old one, a frame must be complete within ten seconds |
| `src/routes/` | the route helper: `spec` (the closed rule language), `netlink` (messages and the parser of the kernel's rule dump), `reconcile` (adds before it removes, sends nothing when the plan is in place), `proto` and `helper` (its control protocol), `sys` (the only `unsafe` of the helper) |
| `src/config.rs`, `src/args.rs`, `src/main.rs` | the Arti configuration with an explicit memory quota, the command line (`exitd` is the daemon and `exitd --routes` the route helper; the mask list is required and a daemon whose list does not load exits with status 2), the entry point (one policy table shared by the control server and the packet path; the Tor client is created at start and bootstrapped only when the table first holds a Tor or masked class) |
| `third_party/onionmasq/` | the vendored library, see `SOURCE.md` (four patches; the fourth splits DNS by policy) |

## Build and test

The toolchain is the AOSP Rust 1.93.1 on the build server; nothing is built on the workstation.

    EXIT_BUILD_SSH=<user@build-server> tools/check-on-server.sh [cargo test arguments]

copies the tree to the server, runs `cargo test --locked --offline` through `tools/env.sh` and fails when no test ran. The
script takes a lock on the workstation before it copies (a second run would otherwise overwrite the source of the first), and
`EXIT_WORK`, `EXIT_CARGO_HOME`, `EXIT_LOCAL_LOCK` give a run its own work directory on the server. Some tests need
`CAP_NET_ADMIN`; they start themselves again under `unshare --user --map-root-user --net` and fail when that is not possible.
`tools/build-android.sh` builds `exitd` for `aarch64-linux-android` and checks the binary itself, not the exit code.
`tools/run-mutations.py` applies single-line mutations and counts one as killed only when the tests ran and failed (`--shard=I/N`
splits the list over several work directories; a test that hangs because of its mutation is killed on the server, which counts as failed).
`tests/vectors.txt` holds golden frames of both control protocols; the Java codec of the exit app checks the same file. `tools/audit-panics.py` compares the places where a panic can start with
`PANIC-SITES.tsv`; `tools/check-licences.sh` checks the licence of every package of `Cargo.lock` and keeps `NOTICE` current.

`Cargo.lock` is committed and every build uses `--locked`. The release profile sets `panic = "abort"`: the Android standard
library of the AOSP tree has no unwind strategy, so any panic ends the process and the init service restarts it.

## Panics

A panic is an outage of every exit app. `PANIC-SITES.tsv` lists the 36 places of the production code where one can start, each
with the reason that bytes of an app cannot reach it (poisoned locks need an earlier panic, start-up constants, invariants);
the audit script fails on a new or a removed site. Indexing is covered by the length check before each index and by the fuzz
tests: `src/fuzz.rs` (control frames, route frames, netlink messages, the mask list), and the tests of the vendored library for
the packet parser and the DNS path (`no_packet_makes_the_parser_panic`, `no_dns_message_makes_the_dns_path_panic`), each with
four seeds and 20 000 to 30 000 inputs under the unwinding test profile. They found no panic.

## Memory

Arti's memory quota (`system.memory.max`) is set explicitly from the configuration and the daemon refuses to start when
that setting does not load; the library default is several GiB. The quota is approximate by design (`tor-memquota`): a
hard limit is a job of the service definition, not of this crate (decided: quota 64 MiB, hard limit 160 MiB, ADR-186).

The key is `system.memory.max` with the quota as an integer number of bytes. After the file is written it is loaded the way
the tunnel will load it and the quota is read back from the loaded configuration; Arti ignores an unknown key without an
error, so this read-back is what keeps the default from staying in force. `tor_memquota::Config` has no public accessor, so the
read-back matches the `Debug` text `max: ByteQty(<bytes>)`; a changed text fails closed.

## Known gaps before an image carries the daemon

- The entry point itself (the tun creation, the wait for the first Tor class, the signal handling) is run only on a phone:
  the host tests reach the argument handling, the list loading and the helper mode through the binary (`tests/cli.rs`), and
  the sharing of the policy through `server::wired`. `NET-009` is the device row.
- TCP DNS (a truncated UDP answer retried over TCP) is refused for a masked app, because only UDP DNS is passed on.
- Where the resolver uses DNS-over-TLS (Private DNS that works on the network) the daemon never sees the queries; the exit
  app reports the tier as limited there (`MSK-008`).
- The 4096-byte TCP buffers of the packet layer limit throughput on a slow circuit; not measured yet (`MSK-002c`).
- The speed guard acts from the next DNS answer, not on a flow that is open, and a hold of 30 minutes ends: a video that runs longer goes through Tor for 4 MiB again before it is passed on again (`MSK-011`).
- `cargo audit` ran on 2026-10-22 (`RISKS.md`, "Advisories"): the fixable advisories are fixed, three remain and are assessed there. The licence
  texts of the third-party dependencies are not bundled, only their SPDX expressions (`NOTICE`, `RISKS.md`).
- A TCP/UDP/IPv6 lookup by `sock_diag` was shown on a phone for TCP over IPv4 only.
