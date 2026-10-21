#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# Runs the host tests of the exit daemon on the build server with the AOSP Rust 1.93.1 (`cargo test --locked
# --offline`, linking through host-clang). Run it on the workstation: it copies the source to the server and runs
# itself there. A run with zero passed tests fails: a green cargo exit with no tests proves nothing.
#   EXIT_BUILD_SSH   user@host of the build server (kept out of the repository on purpose)
#   EXIT_SRC         the source directory to test (default: the exit-daemon/ next to tools/); the mutation runner
#                    points it at a mutated copy
#   arguments        passed to `cargo test`, e.g. a test name filter such as `control::`
# The whole workspace is tested: the vendored crates and their patches are part of what ships.
# One run at a time (flock on the server).
set -o errexit -o nounset -o pipefail
WORK="${EXIT_WORK:-/srv/freeholdos/exit-daemon-work}"

if [[ "${EXIT_REMOTE:-}" != 1 ]]; then
  : "${EXIT_BUILD_SSH:?set EXIT_BUILD_SSH=user@host}"
  SRC="${EXIT_SRC:-$(cd "$(dirname "$0")/.." && pwd)}"
  [[ -f "$SRC/Cargo.toml" ]] || { echo "no Cargo.toml in $SRC" >&2; exit 2; }
  ssh -o BatchMode=yes "$EXIT_BUILD_SSH" "mkdir -p $WORK/src"
  # The vendored library is part of the tree under test; target directories and git data stay out.
  # rsync keeps no modification times (-t is left out on purpose): a file whose content changes gets the time of the copy, so
  # cargo sees it as newer than the last build. With -t an older local file would replace a newer mutated one and cargo would
  # keep using the stale build (found after the first mutation run).
  rsync -rlpgoD --checksum --delete --exclude target --exclude .git -e "ssh -o BatchMode=yes" "$SRC"/ "$EXIT_BUILD_SSH:$WORK/src/"
  printf -v ARGS ' %q' "$@"
  exec ssh -o BatchMode=yes "$EXIT_BUILD_SSH" "EXIT_REMOTE=1 EXIT_WORK=$WORK bash $WORK/src/tools/check-on-server.sh$ARGS"
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
cargo test --locked --offline --workspace --target-dir "$WORK/target-test" "$@" 2>&1 | tee "$LOG"
rc=${PIPESTATUS[0]}
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
