#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# Reads the packages of Cargo.lock on the build server (`cargo metadata`, offline) and runs tools/check-licences.py on them.
#   EXIT_BUILD_SSH   user@host of the build server;  EXIT_WORK  its work directory (default /srv/oblivios/exit-daemon-work)
# An argument (`--write`) is passed on. The source is copied first, so that the lock file read is this tree's.
set -o errexit -o nounset -o pipefail
: "${EXIT_BUILD_SSH:?set EXIT_BUILD_SSH=user@host}"
WORK="${EXIT_WORK:-/srv/oblivios/exit-daemon-work}"
HERE="$(cd "$(dirname "$0")/.." && pwd)"
ssh -o BatchMode=yes "$EXIT_BUILD_SSH" "mkdir -p $WORK/meta"
rsync -rlpgoD --checksum --delete --exclude target --exclude .git -e "ssh -o BatchMode=yes" "$HERE"/ "$EXIT_BUILD_SSH:$WORK/meta/"
ssh -o BatchMode=yes "$EXIT_BUILD_SSH" "EXIT_WORK=$WORK bash -c 'source $WORK/meta/tools/env.sh >/dev/null && cd $WORK/meta && cargo metadata --locked --offline --format-version 1'" \
  | python3 "$HERE/tools/check-licences.py" "$@"
