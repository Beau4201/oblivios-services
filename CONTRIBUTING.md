# Contributing

How this repository is run and what a change has to respect.

## Status

This repository is published as source only. No builds, images or update
service are offered, and no device is supported. Issues can be opened where the
host enables them. Pull requests are not accepted yet.

## What this repository is

The platform-independent logic and the native daemons behind ObliviOS features:
`duress-unlock-core` and `airpods-core` (Java), `exit-daemon` and `vault-core`
(Rust). Real Android integration belongs in `oblivios-platform`; nothing here
is Android enforcement on its own, and it contains no GrapheneOS or AOSP source.

## Decisions come first

The decision log (`docs/governance/decision-log.md` in `oblivios-docs`) is the
reference for behaviour. A new field, action, schema or capability needs an
accepted decision before the code, not after it.

## Invariants

- No telemetry, analytics, crash reporting or phone-home behaviour of any kind.
- Data is minimised technical metadata: no message bodies, file contents,
  camera frames, microphone audio, contacts, clipboard content or location
  coordinates.
- No partial implementation is presented as done. What cannot be completed or
  verified is stated, with exactly what is missing.

## Licence

Apache-2.0 (see `LICENSE` and `NOTICE`). Every source file carries an
`SPDX-License-Identifier` line. The name ObliviOS and its logo are not
licensed.

## Commits

Plain, functional, English messages in conventional-commits style.
