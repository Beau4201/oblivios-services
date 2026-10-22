#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# Runs the host tests of the exit daemon on the build server with the AOSP Rust 1.93.1 (`cargo test --locked
# --offline`, linking through host-clang). Run it on the workstation: it copies the source to the server and runs
# itself there. A run with zero passed tests fails: a green cargo exit with no tests proves nothing.
#   EXIT_BUILD_SSH   user@host of the build server (kept out of the repository on purpose)
#   EXIT_SRC         the source directory to test (default: the exit-daemon/ next to tools/); the mutation runner
#                    points it at a mutated copy
#   arguments        passed to `cargo test`, e.g. a test name filter such as `control::`
# The whole workspace is tested: the vendored crates and their patches are part of what ships. EXIT_ONLY_DAEMON=1 tests only
# the daemon's own crate (the mutation runner uses it for a mutation of the daemon's own sources, where the vendored tests
# cannot be affected).
# One run at a time (flock on the server).
set -o errexit -o nounset -o pipefail
WORK="${EXIT_WORK:-/srv/freeholdos/exit-daemon-work}"

if [[ "${EXIT_REMOTE:-}" != 1 ]]; then
  : "${EXIT_BUILD_SSH:?set EXIT_BUILD_SSH=user@host}"
  # One run at a time from this workstation, taken BEFORE the copy: the copy replaces the whole work directory on the
  # server, so a second run (a mutation run and a test run, say) would otherwise overwrite the source that the first one
  # is compiling, and a mutation could be judged against the wrong code. The lock on the server (below) stays.
  exec 8> "${EXIT_LOCAL_LOCK:-${XDG_RUNTIME_DIR:-/tmp}/exit-daemon-check.lock}"
  flock 8
  SRC="${EXIT_SRC:-$(cd "$(dirname "$0")/.." && pwd)}"
  [[ -f "$SRC/Cargo.toml" ]] || { echo "no Cargo.toml in $SRC" >&2; exit 2; }
  ssh -o BatchMode=yes "$EXIT_BUILD_SSH" "mkdir -p $WORK/src"
  # The vendored library is part of the tree under test; target directories and git data stay out.
  # rsync keeps no modification times (-t is left out on purpose): a file whose content changes gets the time of the copy, so
  # cargo sees it as newer than the last build. With -t an older local file would replace a newer mutated one and cargo would
  # keep using the stale build (found after the first mutation run).
  rsync -rlpgoD --checksum --delete --exclude target --exclude .git -e "ssh -o BatchMode=yes" "$SRC"/ "$EXIT_BUILD_SSH:$WORK/src/"
  printf -v ARGS ' %q' "$@"
  exec ssh -o BatchMode=yes "$EXIT_BUILD_SSH" "EXIT_REMOTE=1 EXIT_WORK=$WORK EXIT_ONLY_DAEMON=${EXIT_ONLY_DAEMON:-0} EXIT_CARGO_HOME=${EXIT_CARGO_HOME:-} EXIT_TARGET=${EXIT_TARGET:-} EXIT_TEST_TIMEOUT=${EXIT_TEST_TIMEOUT:-} bash $WORK/src/tools/check-on-server.sh$ARGS"
fi

exec 9> "$WORK/check.lock"
flock 9
# shellcheck source=env.sh
source "$WORK/src/tools/env.sh"
cd "$WORK/src"
LOG="$WORK/last-check.log"
if [[ ! -d "$CARGO_HOME/registry" ]]; then
  echo "cargo home is empty: fetching the locked dependencies once (network)"
  cargo fetch --locked
fi
set +o errexit
if [[ "${EXIT_ONLY_DAEMON:-0}" == 1 ]]; then scope=(-p exit-daemon); else scope=(--workspace); fi
# No debug information in the test builds: it only makes the two big test binaries slower to link (panic locations do not
# need it).
export CARGO_PROFILE_DEV_DEBUG=0
# EXIT_TEST_TIMEOUT (seconds, the mutation runner sets it): a test run that does not end is killed with its whole process
# group. A mutation that makes a loop endless would otherwise keep every core busy for ever (found 2026-10-23: one spinning
# test binary held the lock for hours and the run behind it timed out mutation after mutation).
limit=()
[[ -n "${EXIT_TEST_TIMEOUT:-}" ]] && limit=(timeout -s KILL "$EXIT_TEST_TIMEOUT")
"${limit[@]}" cargo test --locked --offline "${scope[@]}" --target-dir "${EXIT_TARGET:-$WORK/target-test}" "$@" 2>&1 | tee "$LOG"
rc=${PIPESTATUS[0]}
if [[ $rc -eq 137 && -n "${EXIT_TEST_TIMEOUT:-}" ]]; then
  echo "test run killed by the time-out of $EXIT_TEST_TIMEOUT s (a test that does not end)" >&2
fi
set -o errexit
if [[ $rc -ne 0 ]]; then
  echo "cargo test failed (exit $rc)" >&2
  exit "$rc"
fi
passed=$(sed -n 's/^test result: ok\. \([0-9][0-9]*\) passed.*/\1/p' "$LOG" | awk '{ n += $1 } END { print n + 0 }')
if [[ -z "${passed:-}" || "$passed" -lt 1 ]]; then
  echo "no tests ran: zero passed tests is a failure" >&2
  exit 1
fi
echo "exit-daemon host checks passed: $passed tests"
