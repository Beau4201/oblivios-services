#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# Builds `libvault_core.so` for aarch64-linux-android on the build server (AOSP Rust 1.93.1, `--locked --offline`,
# release profile with panic = "abort") and then checks THE LIBRARY, not the exit code: a cdylib whose crates are not
# reachable from an exported symbol links to a near-empty file and still exits 0 (the exit daemon's first build did).
#   VAULT_BUILD_SSH    user@host of the build server
#   VAULT_TARGET_DIR   optional: another cargo target directory (two builds into two directories must give the same hash)
#   VAULT_FETCH_DIR    optional: copy the verified library into this local directory and check its hash there
# Run on the workstation; it copies the tree to the server and runs itself there.
set -o errexit -o nounset -o pipefail
WORK="${VAULT_WORK:-/srv/freeholdos/vault-core-work}"

if [[ "${VAULT_REMOTE:-}" != 1 ]]; then
  : "${VAULT_BUILD_SSH:?set VAULT_BUILD_SSH=user@host}"
  SRC="${VAULT_SRC:-$(cd "$(dirname "$0")/.." && pwd)}"
  ssh -o BatchMode=yes "$VAULT_BUILD_SSH" "mkdir -p $WORK/src"
  # rsync keeps no modification times (-t is left out on purpose): a file whose content changes gets the time of the copy, so
  # cargo sees it as newer than the last build (see exit-daemon/tools/check-on-server.sh).
  rsync -rlpgoD --checksum --delete --exclude target --exclude .git -e "ssh -o BatchMode=yes" "$SRC"/ "$VAULT_BUILD_SSH:$WORK/src/"
  ssh -o BatchMode=yes "$VAULT_BUILD_SSH" "VAULT_REMOTE=1 VAULT_WORK=$WORK VAULT_TARGET_DIR=${VAULT_TARGET_DIR:-} bash $WORK/src/tools/build-android.sh"
  if [[ -n "${VAULT_FETCH_DIR:-}" ]]; then
    mkdir -p "$VAULT_FETCH_DIR"
    scp -q "$VAULT_BUILD_SSH:$WORK/out/libvault_core.so" "$VAULT_FETCH_DIR/libvault_core.so"
    echo "fetched: $(sha256sum "$VAULT_FETCH_DIR/libvault_core.so")"
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
TARGET="${VAULT_TARGET_DIR:-}"
[[ -n "$TARGET" ]] || TARGET="$WORK/target-android"
nice -n 19 cargo build --release --locked --offline --target aarch64-linux-android --lib --target-dir "$TARGET"
BIN="$TARGET/aarch64-linux-android/release/libvault_core.so"
mkdir -p "$WORK/out"
cp -f "$BIN" "$WORK/out/libvault_core.so"
BIN="$WORK/out/libvault_core.so"

READELF="$VAULT_LLVM_BIN/llvm-readelf"
fail=0
check() { echo "  $1"; }
bad() { echo "FAIL: $1" >&2; fail=1; }

echo "checking the library:"
header="$("$READELF" -h "$BIN")"
grep -q 'Machine:.*AArch64' <<<"$header" || bad "not an AArch64 ELF"
grep -q 'Class:.*ELF64' <<<"$header" || bad "not ELF64"
grep -q 'Type:.*DYN' <<<"$header" || bad "not a shared object"
check "AArch64 ELF64 shared object"
needed="$("$READELF" -d "$BIN" | sed -n 's/.*(NEEDED).*Shared library: \[\(.*\)\]/\1/p' | sort | tr '\n' ' ')"
# No libssl, libcrypto, libcurl, libnetd_client or other network library: the core opens no socket (ADR-185).
for lib in $needed; do
  case "$lib" in libc.so|libdl.so|libm.so|liblog.so) ;; *) bad "NEEDED holds $lib" ;; esac
done
check "NEEDED $needed"
# The exports are exactly the JNI surface of org.freeholdos.passwords.core.VaultCore: nothing missing, nothing extra.
EXPECTED="selfTest create unlockWithPassphrase unlockWithVaultKey lock list search get addOrEdit delete markFilled checkup export importEntries indexBuild indexContains generate strength writeFileAtomic readFileBounded"
want="$(for n in $EXPECTED; do echo "Java_org_freeholdos_passwords_core_VaultCore_$n"; done | sort)"
have="$("$READELF" --dyn-syms "$BIN" | awk '$7 != "UND" && $5 == "GLOBAL" {print $NF}' | sed 's/@.*//' | sort)"
[[ "$have" == "$want" ]] || bad "exports differ from the JNI surface: $(diff <(echo "$want") <(echo "$have") | tr '\n' ' ')"
check "exports: the $(wc -w <<<"$EXPECTED") JNI functions and nothing else"
# llvm-readelf prints imports as `name@LIBC`: strip the version, or an anchored pattern matches nothing.
imports() { "$READELF" --dyn-syms "$BIN" | awk '/ UND /{print $NF}' | sed 's/@.*//'; }
if imports | grep -E -q '^(socket|connect|bind|listen|accept|accept4|getaddrinfo|android_getaddrinfofornet|gethostbyname|sendto|recvfrom|sendmsg|recvmsg)$'; then
  bad "the library imports a network function"
fi
check "no network function imported"
for sym in argon2 chacha20poly1305 hkdf; do
  n="$(grep -a -c "$sym" "$BIN" || true)"
  [[ "$n" -ge 1 ]] || bad "no '$sym' in the library (a link that dropped the crypto exits 0 too)"
  check "'$sym' found on $n lines"
done
for root in "$WORK" "$T"; do
  n="$(grep -a -c -F "$root" "$BIN" || true)"
  [[ "$n" -eq 0 ]] || bad "$n lines of the library hold the build path $root (reproducible builds: no absolute build-machine path)"
done
check "no build-machine path in the library"
size="$(stat -c %s "$BIN")"
(( size > 100000 )) || bad "only $size bytes: far too small to hold the crypto and the suffix list"
check "$size bytes"
[[ $fail -eq 0 ]] || exit 1
echo "built and verified: $(sha256sum "$BIN")"
