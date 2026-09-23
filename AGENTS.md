# AGENTS.md — sovereignos-services

This file is self-contained (don't assume it merges with a global AGENTS.md —
override behavior isn't guaranteed, so everything that matters lives here).

## What this repository is

A localhost-only reference implementation of Guardian: `guardian-core`
(contracts, deterministic detection, typed remediation, audit, Undo),
`guardian-reference` (CLI scenario), `guardian-ui-reference` (Compose Desktop
UI). It simulates platform effects for deterministic local testing. It is not
Android enforcement, contains no GrapheneOS/AOSP source, and must never claim
device-wide protection. Real Android integration belongs in `sovereignos-platform`
(ADR-013 in `sovereignos-foundation`).

## Binding governance — read before implementing anything

`sovereignos-foundation` (sibling repo) is the authoritative source of truth.
Before adding or changing any Guardian behavior, contract, or capability:

1. Read `docs/governance/decision-log.md` — every accepted ADR.
2. Read `docs/architecture/guardian-contracts.md` and
   `docs/security/capability-boundaries.md` — the exact schemas, remediation
   catalogue, and denied authority.
3. If the change needs a new field, action, schema, or capability that isn't
   already in an Accepted ADR, do not implement it first and backfill the ADR
   later. Foundation-record updates come before implementation, not after —
   an unmerged branch implementing ahead of its governing ADR is exactly the
   kind of gap this file exists to prevent.

## Hard invariants

- Remediation catalogue is exactly four typed actions:
  `REVOKE_BACKGROUND_LOCATION`, `BLOCK_BACKGROUND_NETWORK`,
  `REMOVE_OVERLAY_PERMISSION`, `FREEZE_APPLICATION`. Unknown action IDs are
  always rejected.
- Every remediation is registered, typed, precondition-checked, verified,
  audited, and reversible where safe. A failed verification always triggers
  the registered rollback and is always truthfully audited as
  `VERIFICATION_FAILED` — never silently reported as success.
- Guardian data is event-driven minimized technical metadata only: no message
  bodies, file contents, camera frames, microphone audio, contacts, clipboard
  content, or location coordinates, ever.
- Owner-visible incident/Overview evidence never exports to AI, cloud,
  analytics, backups, or other apps.
- No telemetry, analytics, crash reporting, or phone-home behavior of any
  kind, ever, without explicit sign-off — this is a hard project invariant
  inherited from `sovereignos-foundation`, not a style preference.
- No partial implementations presented as done. If something can't be fully
  completed or verified, say so explicitly and state exactly what's missing.

## Git discipline

- Claude Code and other AI agents may commit directly in this repository
  without asking for per-commit authorization, provided every rule below is
  followed on every commit. This does not extend to force-push, history
  rewrite, branch deletion, or any other destructive operation — those still
  require explicit owner authorization every time.
- Commit messages: plain, functional, English, conventional-commits style.
- NEVER add `Co-Authored-By:`, AI-attribution footers, session/task IDs, or
  emoji to commits or commit messages.
