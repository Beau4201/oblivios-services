#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# Runs clippy over the library on the build server. `api.rs`, `jni.rs` and `container.rs` carry
# `#![deny(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::panic)]`: code reachable from the
# JNI surface may not panic (the release profile aborts the app process on a panic). A deny hit is a compile error, so
# the script fails on it; other clippy warnings are printed and counted. A run that checked nothing fails too.
#   VAULT_BUILD_SSH   user@host of the build server
set -o errexit -o nounset -o pipefail
WORK="${VAULT_WORK:?set VAULT_WORK}"
if [[ "${VAULT_REMOTE:-}" != 1 ]]; then
  : "${VAULT_BUILD_SSH:?set VAULT_BUILD_SSH=user@host}"
  SRC="${VAULT_SRC:-$(cd "$(dirname "$0")/.." && pwd)}"
  ssh -o BatchMode=yes "$VAULT_BUILD_SSH" "mkdir -p $WORK/src"
  rsync -rlpgoD --checksum --delete --exclude target --exclude .git -e "ssh -o BatchMode=yes" "$SRC"/ "$VAULT_BUILD_SSH:$WORK/src/"
  exec ssh -o BatchMode=yes "$VAULT_BUILD_SSH" "VAULT_REMOTE=1 VAULT_WORK=$WORK bash $WORK/src/tools/clippy-on-server.sh"
fi
exec 9> "$WORK/check.lock"
flock 9
# shellcheck source=env.sh
source "$WORK/src/tools/env.sh"
cd "$WORK/src"
LOG="$WORK/last-clippy.log"
set +o errexit
nice -n 19 cargo clippy --locked --offline --lib --target-dir "$WORK/target-clippy" 2>&1 | tee "$LOG"
rc=${PIPESTATUS[0]}
set -o errexit
[[ $rc -eq 0 ]] || { echo "clippy failed (exit $rc)" >&2; exit "$rc"; }
grep -q 'Finished' "$LOG" || { echo "clippy did not finish" >&2; exit 1; }
# Prove that the deny lists are live: the guarded files must not have been skipped.
for f in src/api.rs src/jni.rs src/container.rs src/csvimport.rs; do
  grep -q 'deny(clippy::unwrap_used' "$f" || { echo "$f lost its deny list" >&2; exit 1; }
done
echo "clippy passed; warnings: $(grep -c '^warning' "$LOG" || true)"
