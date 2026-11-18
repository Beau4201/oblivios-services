#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# Builds `exitd` for aarch64-linux-android on the build server (AOSP Rust 1.93.1, `--locked --offline`, release profile
# with panic = "abort") and then checks THE BINARY, not the exit code: the first build of 2026-10-20 exited 0 with a
# 1.7 MB file that held no Tor code (recorded in the project's research notes, which are not published).
#   EXIT_BUILD_SSH    user@host of the build server
#   EXIT_TARGET_DIR   optional: another cargo target directory (two builds into two directories must give the same hash)
#   EXIT_FETCH_DIR    optional: copy the verified binary into this local directory and check its hash there
# Run on the workstation; it copies the tree to the server and runs itself there.
set -o errexit -o nounset -o pipefail
WORK="${EXIT_WORK:?set EXIT_WORK}"

if [[ "${EXIT_REMOTE:-}" != 1 ]]; then
  : "${EXIT_BUILD_SSH:?set EXIT_BUILD_SSH=user@host}"
  SRC="${EXIT_SRC:-$(cd "$(dirname "$0")/.." && pwd)}"
  ssh -o BatchMode=yes "$EXIT_BUILD_SSH" "mkdir -p $WORK/src"
  # rsync keeps no modification times (-t is left out on purpose): a file whose content changes gets the time of the copy, so
  # cargo sees it as newer than the last build. With -t an older local file would replace a newer mutated one and cargo would
  # keep using the stale build (found after the first mutation run).
  rsync -rlpgoD --checksum --delete --exclude target --exclude .git -e "ssh -o BatchMode=yes" "$SRC"/ "$EXIT_BUILD_SSH:$WORK/src/"
  ssh -o BatchMode=yes "$EXIT_BUILD_SSH" "EXIT_REMOTE=1 EXIT_WORK=$WORK EXIT_TARGET_DIR=${EXIT_TARGET_DIR:-} bash $WORK/src/tools/build-android.sh"
  if [[ -n "${EXIT_FETCH_DIR:-}" ]]; then
    mkdir -p "$EXIT_FETCH_DIR"
    scp -q "$EXIT_BUILD_SSH:$WORK/out/exitd" "$EXIT_FETCH_DIR/exitd"
    echo "fetched: $(sha256sum "$EXIT_FETCH_DIR/exitd")"
  fi
  exit 0
fi

exec 9> "$WORK/build-android.lock"
flock 9
# shellcheck source=env.sh
source "$WORK/src/tools/env.sh"
cd "$WORK/src"
if [[ ! -d "$CARGO_HOME/registry" ]]; then
  cargo fetch --locked --target aarch64-linux-android
fi
TARGET="${EXIT_TARGET_DIR:-}"
[[ -n "$TARGET" ]] || TARGET="$WORK/target-android"
cargo build --release --locked --offline --target aarch64-linux-android --bin exitd --target-dir "$TARGET"
BIN="$TARGET/aarch64-linux-android/release/exitd"
mkdir -p "$WORK/out"
cp -f "$BIN" "$WORK/out/exitd"
BIN="$WORK/out/exitd"

READELF="$EXIT_LLVM_BIN/llvm-readelf"
fail=0
check() { echo "  $1"; }
bad() { echo "FAIL: $1" >&2; fail=1; }

echo "checking the binary:"
header="$("$READELF" -h "$BIN")"
grep -q 'Machine:.*AArch64' <<<"$header" || bad "not an AArch64 ELF"
grep -q 'Class:.*ELF64' <<<"$header" || bad "not ELF64"
check "AArch64 ELF64"
interp="$("$READELF" -l "$BIN" | sed -n 's/.*Requesting program interpreter: \(.*\)\]/\1/p')"
[[ "$interp" == "/system/bin/linker64" ]] || bad "interpreter is '$interp', not /system/bin/linker64"
check "interpreter $interp"
needed="$("$READELF" -d "$BIN" | sed -n 's/.*(NEEDED).*Shared library: \[\(.*\)\]/\1/p' | sort | tr '\n' ' ')"
[[ "$needed" == "libc.so libdl.so libm.so " ]] || bad "NEEDED is '$needed', not libc.so libdl.so libm.so"
check "NEEDED $needed"
for sym in tor-proto smoltcp aws_lc exitd; do
  n="$(grep -a -c "$sym" "$BIN" || true)"
  [[ "$n" -ge 1 ]] || bad "no '$sym' in the binary (a build without the Tor code exits 0 too)"
  check "'$sym' found on $n lines"
done
for root in "$WORK" "$T"; do
  n="$(grep -a -c -F "$root" "$BIN" || true)"
  [[ "$n" -eq 0 ]] || bad "$n lines of the binary hold the build path $root (reproducible builds: no absolute build-machine path)"
done
check "no build-machine path in the binary"
size="$(stat -c %s "$BIN")"
(( size > 5000000 )) || bad "only $size bytes: far too small to hold the Tor client"
check "$size bytes"
[[ $fail -eq 0 ]] || exit 1
echo "built and verified: $(sha256sum "$BIN")"
