#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# Re-resolves Cargo.lock offline on the build server after a dependency change in Cargo.toml and copies it back. The
# resolution keeps every locked version that still fits; read `git diff Cargo.lock` before committing it.
#   EXIT_BUILD_SSH   user@host of the build server
set -o errexit -o nounset -o pipefail
: "${EXIT_BUILD_SSH:?set EXIT_BUILD_SSH=user@host}"
WORK="${EXIT_WORK:-/srv/freeholdos/exit-daemon-work}"
SRC="$(cd "$(dirname "$0")/.." && pwd)"
rsync -rlpgoD --checksum --delete --exclude target --exclude .git -e "ssh -o BatchMode=yes" "$SRC"/ "$EXIT_BUILD_SSH:$WORK/src/"
ssh -o BatchMode=yes "$EXIT_BUILD_SSH" "export EXIT_WORK=$WORK; source $WORK/src/tools/env.sh && cd $WORK/src && cargo metadata --offline --format-version 1 >/dev/null"
scp -q "$EXIT_BUILD_SSH:$WORK/src/Cargo.lock" "$SRC/Cargo.lock"
echo "Cargo.lock updated"
