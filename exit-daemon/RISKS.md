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

## Not run

`cargo audit`: the advisory database is a download, which needs the owner's yes. See `README.md`, "Known gaps".

## Availability

A panic, a crash loop or a stuck Tor bootstrap is an outage of every app set to Tor (no network, never Direct, ADR-178) and,
for masked destinations, a pause of the masking: DNS of masked apps goes to the resolver directly while the daemon is down, and
a connection to an old fake address reaches nothing `[measured: MSK-008]`. The exit app shows the pause.
