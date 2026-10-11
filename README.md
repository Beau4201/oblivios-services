# SovereignOS Guardian Services

This repository contains the localhost-only SovereignOS Guardian reference
application and reusable Guardian core. It simulates platform effects for
deterministic local testing; `sovereignos-platform` is required for real Android
enforcement.

Run the complete test entry point with:

```sh
./gradlew test
```

## Stage

Source only. No builds, images or update service are offered, and no device is
supported. See `CONTRIBUTING.md` for the invariants a change has to respect.

## Licence

Apache-2.0, see `LICENSE` and `NOTICE`. The name SovereignOS and its logo are
not licensed.
