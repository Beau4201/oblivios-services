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

`init` creates `/dev/socket/exitd` and `/dev/socket/exitd_route` with mode 0666 (`exit.rc` in `freeholdos-platform`). The exit
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

## Availability

A panic, a crash loop or a stuck Tor bootstrap is an outage of every app set to Tor (no network, never Direct, ADR-178) and,
for masked destinations, a pause of the masking: DNS of masked apps goes to the resolver directly while the daemon is down, and
a connection to an old fake address reaches nothing `[measured: MSK-008]`. The exit app shows the pause.
