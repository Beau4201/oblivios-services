# ObliviOS Services

Platform-independent logic and native daemons of ObliviOS, kept apart from the
Android patches in `oblivios-platform`.

| Directory | What it holds |
| --- | --- |
| `duress-unlock-core` | The logic of Duress Unlock (Java, no dependencies). |
| `airpods-core` | The codec, settings and state machine of AirPods support (Java, no dependencies). |
| `exit-daemon` | `exitd`, the exit daemon of Compartments (Rust). |
| `vault-core` | The Rust core of the Passwords app: sealed vault file, key wrappers, checkup, export (Rust). |

The Java modules are tested with:

```sh
./gradlew test
```

The Rust crates are built and checked from their own directories; see the
`README.md` and `SOURCE.md` in each.

## Stage

Source only. No builds, images or update service are offered, and no device is
supported. See `CONTRIBUTING.md` for the invariants a change has to respect.

## Licence

Apache-2.0, see `LICENSE` and `NOTICE`. The name ObliviOS and its logo are
not licensed.
