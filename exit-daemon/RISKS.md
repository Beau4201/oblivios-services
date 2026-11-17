# Known risks of the exit daemon

The risks that the owner accepted or that are open, in one place (ADR-184, ADR-186). Evidence labels: `[verified-in-source]`,
`[measured]`, `[assumption]`.

## C code in the dependency tree (D-L, accepted 2026-10-21)

The daemon is Rust, but three crates compile C that ends up in the Android binary `[measured: the build directory of
tools/build-android.sh holds archives for exactly these]`: `aws-lc-sys` 0.45.0 (the crypto provider of `rustls`, derived from
BoringSSL), `lzma-sys` (liblzma, static) and `libsqlite3-sys` (SQLite, bundled, used by Arti's state). The memory-safety
argument for Rust therefore does not cover them. Mitigation: the daemon runs in its own SELinux domain with `CAP_NET_ADMIN`
only, no framework right and (ADR-186) no routing right; a fault there is an outage and, at worst, control of the daemon's
Tor traffic, not of routing or of the system. No crypto provider without C code was evaluated; it would diverge from the
Onionmasq and Arti dependency sets.

## Licences

`NOTICE` lists the 552 packages (the daemon, the three vendored crates and 548 others) of `Cargo.lock` with their SPDX expressions, and `tools/check-licences.sh` fails on an
expression that cannot be met with MIT, Apache-2.0, BSD-1/2/3-Clause, ISC, Unicode-3.0, Zlib, 0BSD, CC0-1.0, MIT-0, Unlicense
or MPL-2.0. Three packages are MPL-2.0 (`colored`, `option-ext`, and `priority-queue` by choice over LGPL-3.0-or-later); they
are used unmodified, which is what the MPL asks for. The licence texts of the packages are not bundled with the daemon: a
binary that ships to users needs them in the platform's notices (open, platform `NOTICE`).

## The control sockets are world-connectable

`init` creates `/dev/socket/exitd` and `/dev/socket/exitd_route` with mode 0666 (`exit.rc` in `oblivios-platform`). The exit
app has its own uid, which is in no group of the services, so a group-only mode would refuse it. What keeps every other process
out is the SELinux policy (only `exit_app` may `connectto` either socket, `[measured]` with `sesearch`) and the services' own
check of the peer's SELinux context before they read a byte. That is one layer fewer than a mode that names the peer; it is
accepted because the policy is the layer the rest of the platform relies on.

## Advisories (`cargo audit`, advisory database of 2026-10-22, run after the owner's yes to the download)

The first run found `rustls` 0.23.44 (five advisories) and `rustls-webpki` 0.103.15 (RUSTSEC-2026-0382); both are updated to
0.23.46 and 0.103.16 in `Cargo.lock`, and the second run lists them no more. Three remain:

- `rsa` 0.9.10, RUSTSEC-2023-0071 (Marvin timing attack, no fixed version). `tor-llcrypto` uses RSA to verify the identity of
  relays, which is public-key work; a client holds no RSA private key, so the attack has no target `[verified-in-source:
  review of 2026-10-22, tor-llcrypto]`.
- `bincode` 2.0.1 (RUSTSEC-2025-0141) and `paste` 1.0.15 (RUSTSEC-2024-0436): unmaintained, no vulnerability.

Arti uses `rustls` for the TLS links to relays with its own certificate verifier, which does not walk a chain, so the path
verification of `rustls-webpki` is not used `[verified-in-source: tor-rtcompat 0.46.0 src/impls/rustls.rs]`. The run is repeated
at every update of `Cargo.lock`.

### Advisories of OSV on 2026-11-16 (five), assessed 2026-11-17

The first three are fixed by a lock update that moves only those packages (`tokio` 1.53.1 to 1.53.3, `rustix` 1.1.4 to
1.1.6, `ctutils` 0.4.2 to 0.4.4; `cargo metadata --locked` accepts the edit, 344 host tests pass, the build is
reproducible, see `SOURCE.md`). The other two are not fixed and are assessed here.

- `ctutils` 0.4.2, RUSTSEC-2026-0385 (a mask generation that may branch on a secret; fixed in 0.4.4). It is linked: `polyval`
  of `tor-proto` uses it `[measured: cargo tree -i ctutils, target aarch64-linux-android]`. Updated.
- `rustix` 1.1.4, RUSTSEC-2026-0486 (the xattr functions mark unwritten bytes as initialised; fixed in 1.1.6). It is linked
  through `tempfile`. No crate of the tree calls an xattr function `[verified-in-source: grep of tempfile, tor-*, arti-client, the
  daemon and the vendored crates for getxattr, lgetxattr, fgetxattr, listxattr, llistxattr, flistxattr: no match]`. Updated.
- `tokio` 1.53.1, RUSTSEC-2026-0386 (`AsyncFd::new` and its three siblings are safe functions that accept any file
  descriptor; fixed in 1.53.3). 1.53.3 deprecates those constructors in favour of unsafe ones; it does not change what they do,
  so the weakness of the API stays in the updated lock. The calls in the tree are in the vendored `onion-tunnel`
  (`tuntap.rs`, on a descriptor that code has just opened itself), in `inotify` (through `notify` and `tor-config`) and in
  `tokio` itself `[verified-in-source: grep for the four constructors in the tree and the crates]`. The use-after-free needs
  the descriptor to be duplicated and closed while the `AsyncFd` lives and its number to be reused; nothing in these
  call sites does that and none was found exploitable, which is a reading, not a proof. Updated for the version, residual risk
  accepted.
- `crypto-bigint` 0.5.5, RUSTSEC-2026-0387 (timing of constant-time operations; fixed only from 0.7.6, another minor line). The
  package is in `Cargo.lock` and in no dependency graph of any target: `cargo tree --target all -i crypto-bigint` prints
  nothing `[measured: 2026-11-17]`, so it is not compiled into `exitd`. Not changed. `Cargo.lock` still lists it, so the
  SBOM lists it too and says it is not linked.
- `libsqlite3-sys` 0.37.0, RUSTSEC-2026-0459 (SQLite before 3.54.0 can return uninitialised memory when a database is opened
  with a `-wal` file of another page size; fixed in `libsqlite3-sys` 0.39.0, which comes with `rusqlite` 0.41). It is linked:
  `rusqlite` 0.39 of `tor-dirmgr` pins it `[measured: cargo tree -i libsqlite3-sys]`, so the fix needs an Arti release
  that uses `rusqlite` 0.41; a lock edit alone cannot do it. The flaw needs a process to open a database and `-wal` file
  that someone else wrote. The daemon names the Arti state and cache directories itself (`arti_toml` in `config.rs`); who can
  write there is set by the file modes and the SELinux labels that the exit app and the policy give, which this
  assessment did not re-read. Not fixed, accepted until an Arti release that uses `rusqlite` 0.41 is taken in; reopen then,
  or when someone other than the daemon can write those directories.

The OSV query that followed the update (2026-11-17) no longer lists `tokio`, `rustix` or `ctutils`, and lists two new
notices of the same kind as `bincode` and `paste`: `chrono` 0.4.45 (RUSTSEC-2026-0543) and `humantime` 2.4.0
(RUSTSEC-2026-0542), both "unmaintained" with no vulnerability and no patched version. `humantime` is linked (through
`arti-client` and `humantime-serde`); `chrono` is in no dependency graph of the Android target `[measured: cargo tree -i]`.
Nothing to change; both wait for an Arti release that drops them.

## Availability

A panic, a crash loop or a stuck Tor bootstrap is an outage of every app set to Tor (no network, never Direct, ADR-178) and,
for masked destinations, a pause of the masking: DNS of masked apps goes to the resolver directly while the daemon is down, and
a connection to an old fake address reaches nothing `[measured: MSK-008]`. The exit app shows the pause.
