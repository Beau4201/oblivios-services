#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# Resolves Cargo.lock on the build server after a dependency change in Cargo.toml (the one step that uses the network:
# crates.io index and crate downloads), fetches the locked crates into the server's cargo home and copies the lock back.
# The resolution keeps every locked version that still fits; read `git diff Cargo.lock` before committing it.
#   VAULT_BUILD_SSH   user@host of the build server
set -o errexit -o nounset -o pipefail
: "${VAULT_BUILD_SSH:?set VAULT_BUILD_SSH=user@host}"
WORK="${VAULT_WORK:?set VAULT_WORK}"
SRC="$(cd "$(dirname "$0")/.." && pwd)"
ssh -o BatchMode=yes "$VAULT_BUILD_SSH" "mkdir -p $WORK/src"
rsync -rlpgoD --checksum --delete --exclude target --exclude .git -e "ssh -o BatchMode=yes" "$SRC"/ "$VAULT_BUILD_SSH:$WORK/src/"
ssh -o BatchMode=yes "$VAULT_BUILD_SSH" "export VAULT_WORK=$WORK; source $WORK/src/tools/env.sh && cd $WORK/src && nice -n 19 cargo metadata --format-version 1 >/dev/null && nice -n 19 cargo fetch --locked --target aarch64-linux-android && nice -n 19 cargo fetch --locked --target x86_64-unknown-linux-gnu"
scp -q "$VAULT_BUILD_SSH:$WORK/src/Cargo.lock" "$SRC/Cargo.lock"
echo "Cargo.lock updated"
