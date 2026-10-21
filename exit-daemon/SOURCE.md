# Source record: vendored Onionmasq (exit-daemon)

`third_party/onionmasq/` is a copy of three crates of Onionmasq, the Rust tunnel library of the Tor Project that Tor VPN
for Android is built on. The daemon uses it as a library (`onion-tunnel`) and implements its own `TunnelScaffolding`.

| Item | Value |
| --- | --- |
| Upstream | `tpo/core/onionmasq` (Tor Project GitLab) |
| Commit | `849a8d14d16501d530f03b10792d160aecdc8dfd` (2026-09-09) |
| Taken from | `git archive` of that commit in the existing clone on the build server (no new download); only the files below |
| Licence | `MIT OR Apache-2.0`: `third_party/onionmasq/LICENSE-MIT` (Tor Project, 2019-2022) and `LICENSE-APACHE` are copied unchanged; the dual licence is stated in the "License" section of the upstream `README.md`. No crate `Cargo.toml` upstream has a `license` field |
| Crates | `crates/onion-tunnel`, `crates/onionmasq-device-testing`, `crates/simple-proc-net` (all of `onion-tunnel`'s path dependencies that the daemon needs) |
| Not vendored | `onionmasq` (the binary: the daemon has its own `main`), `onionmasq-mobile` and `onionmasq-apple` (JNI and Apple layers), `experimental-turn-tcp-client` (the TURN UDP path, which the daemon never enables) |
| Lock file | `Cargo.lock` of the daemon is the upstream lock reduced by `cargo metadata --offline` to the daemon's tree: 552 packages, no version differs from upstream, 62 upstream packages dropped, `exit-daemon` added. Builds use `--locked` |

The upstream workspace `Cargo.toml` is not kept: the daemon's `Cargo.toml` is the workspace root and names the three
crates as members (two workspace roots in one tree are refused by cargo). The upstream `[profile.release]`
(`codegen-units = 1`, `lto`, `opt-level = "z"`) is carried into the daemon's, with `panic = "abort"` added.

## Patches

Applied in order with `patch -p1` from `third_party/onionmasq/`. Each is kept as small as possible so that a rebase onto a
new upstream commit stays cheap.

| Patch | Why it diverges from upstream |
| --- | --- |
| `third_party/patches/0001-onion-tunnel-drop-the-optional-turn-client-dependency.patch` | `onion-tunnel` names the TURN client crate as an optional path dependency; cargo needs the path to exist even when the feature is off. The daemon carries no UDP (ADR-184), so the dependency is removed and `experimental-udp-tunnel` becomes an empty feature: enabling it fails to compile instead of building a UDP path. |
| `third_party/patches/0002-onion-tunnel-answer-servfail-when-the-owner-lookup-fails.patch` | Upstream gives a DNS query whose owner lookup fails the shared isolation key, which weakens the per-app DNS isolation (its own comment says so). The daemon answers SERVFAIL instead (ADR-184, finding F03): `DnsManager::attribute` turns a failed lookup into the SERVFAIL answer and `DnsManager::handle` writes it; the function is tested, the `match` that calls it is not (the mutation list names the function only). |
| `third_party/patches/0003-onion-tunnel-embed-a-constant-instead-of-the-enclosing-git-hash.patch` | Upstream's `build.rs` runs `git log` and embeds the hash of whatever repository encloses the build directory, so the binary depends on the checkout it was built in and names the wrong project. The vendored library is one fixed upstream commit, so the value is a constant. |
| `third_party/patches/0004-onion-tunnel-split-dns-by-policy-and-use-private-fake-ranges.patch` | Upstream answers every DNS question itself with a fake address from `10.0.0.0/8` and `fec0::/10` (which collide with LANs) and listens at one fixed in-tunnel address. The daemon has to answer only the names it masks, pass every other query unchanged to the resolver the app asked, and do so for any destination address (ADR-186). The patch adds `TunnelScaffolding::dns_decision` (`Tor` by default, upstream behaviour), moves the fake addresses to `198.18.0.0/15` and the upper half of `fd66:7265:6568::/48`, replaces the two fixed-address listeners by one that takes port 53 for any destination and answers from the address the query was sent to, forwards a `Forward` query from a socket of the daemon with a 2 s time-out, a 4096-byte reply limit and at most 64 in flight, and refuses a query that mixes the two decisions. The decision (`route_query`), the address ranges and the forward socket are unit-tested; the glue that moves datagrams between the interface and these functions is exercised on a phone (`MSK-003`, `MSK-007`). It also turns the `assert!` on a full address range into a refusal. |

A patch to the `cfg(target_os = "linux")` gates of `scaffolding.rs` and `onionmasq/src/main.rs` was not needed: it only
enabled `scaffolding::linux::LinuxScaffolding`, which the daemon does not use (that scaffolding sets a socket mark, which
ADR-184 forbids). The first compile for Android of `onion-tunnel` without it is the check (`tools/build-android.sh`).
