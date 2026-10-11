# Contributing

How this repository is run and what a change has to respect.

## Status

This repository is published as source only. No builds, images or update
service are offered, and no device is supported. Issues can be opened where the
host enables them. Pull requests are not accepted yet.

## What this repository is

A localhost-only reference implementation of Guardian: `guardian-core`
(contracts, deterministic detection, typed remediation, audit, undo),
`guardian-reference` (command line scenario) and `guardian-ui-reference`
(Compose Desktop UI). `duress-unlock-core` and `airpods-core` hold the
platform-independent logic of two other features. The code simulates platform
effects for deterministic local testing. It is not Android enforcement, it
contains no GrapheneOS or AOSP source, and it must never claim device-wide
protection. Real Android integration belongs in `sovereignos-platform`.

## Decisions come first

The decision log (`docs/governance/decision-log.md` in `sovereignos-docs`) and
the Guardian contracts and capability boundaries in the same repository are the
reference for behaviour. A new field, action, schema or capability needs an
accepted decision before the code, not after it.

## Invariants

- The remediation catalogue is exactly four typed actions:
  `REVOKE_BACKGROUND_LOCATION`, `BLOCK_BACKGROUND_NETWORK`,
  `REMOVE_OVERLAY_PERMISSION` and `FREEZE_APPLICATION`. Unknown action ids are
  always rejected.
- Every remediation is registered, typed, checked against its preconditions,
  verified, audited and reversible where safe. A failed verification triggers
  the registered rollback and is audited as `VERIFICATION_FAILED`, never
  reported as success.
- Guardian data is minimised technical metadata, recorded per event: no message
  bodies, file contents, camera frames, microphone audio, contacts, clipboard
  content or location coordinates.
- Incident and overview evidence is never exported to AI, cloud, analytics,
  backups or other apps.
- No telemetry, analytics, crash reporting or phone-home behaviour of any kind.
- No partial implementation is presented as done. What cannot be completed or
  verified is stated, with exactly what is missing.

## Licence

Apache-2.0 (see `LICENSE` and `NOTICE`). Every source file carries an
`SPDX-License-Identifier` line. The name SovereignOS and its logo are not
licensed.

## Commits

Plain, functional, English messages in conventional-commits style.
