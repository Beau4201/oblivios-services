#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# Build environment of the exit daemon, SOURCED on the build server (it exports; it changes nothing in the AOSP tree).
# The server has no cc, make or cmake and the prebuilt NDK directory holds no sysroot, so the C code of the Rust
# dependencies (aws-lc-sys, lzma-sys) is compiled with the AOSP clang against a sysroot of symlinks to the artefacts
# of an existing out directory (libc.so, libm.so, libdl.so, liblog.so, crt*.o and the bionic headers). The first
# build of this kind is recorded in the project's research notes, which are not published.
# Host-side build scripts and proc macros link with the same clang against the AOSP glibc 2.17 sysroot.
#   AOSP_TREE  default /srv/oblivios/platform-primary      AOSP_OUT  default out-lynx (must hold a finished build)
#   EXIT_WORK  default /srv/oblivios/exit-daemon-work      (source copy, cargo home, target directories)
#   EXIT_CARGO_HOME  default $EXIT_WORK/cargo-home    (a second work directory can share the downloaded crates)
# -Qunused-arguments: compile-only calls (aws-lc-sys builds with -Werror) would otherwise fail on -fuse-ld.
# Nothing here is deleted: links are replaced in place.
T="${AOSP_TREE:-/srv/oblivios/platform-primary}"
OUTD="$T/${AOSP_OUT:-out-lynx}"
WORK="${EXIT_WORK:-/srv/oblivios/exit-daemon-work}"
I="$OUTD/soong/.intermediates"
V=android_arm64_armv8-2a-dotprod_cortex-a76
CL="$T/prebuilts/clang/host/linux-x86/clang-r596125"
G="$T/prebuilts/gcc/linux-x86/host/x86_64-linux-glibc2.17-4.8"
API=35
SYS="$WORK/toolchain/sysroot"
L="$SYS/usr/lib/aarch64-linux-android/$API"

mkdir -p "$L" "$WORK/toolchain"
ln -sfn "$T/bionic/libc/include" "$SYS/usr/include"
for f in "$I/bionic/libc/libc/${V}_shared/libc.so" "$I/bionic/libm/libm/${V}_shared/libm.so" \
         "$I/bionic/libdl/libdl/${V}_shared/libdl.so" "$I/system/logging/liblog/liblog/${V}_shared/liblog.so" \
         "$I/bionic/libc/crtbegin_dynamic/$V/crtbegin_dynamic.o" "$I/bionic/libc/crtend_android/$V/crtend_android.o" \
         "$I/bionic/libc/crtbegin_so/$V/crtbegin_so.o" "$I/bionic/libc/crtend_so/$V/crtend_so.o"; do
  [ -e "$f" ] || { echo "env.sh: missing $f (is $OUTD a finished build?)" >&2; return 1 2>/dev/null || exit 1; }
  ln -sfn "$f" "$L/$(basename "$f")"
done

cat > "$WORK/toolchain/android-clang" <<EOT
#!/bin/sh
exec $CL/bin/clang --target=aarch64-linux-android$API --sysroot=$SYS -fuse-ld=lld -Qunused-arguments \\
  -isystem $T/bionic/libc/kernel/uapi -isystem $T/bionic/libc/kernel/uapi/asm-arm64 \\
  -isystem $T/bionic/libc/kernel/android/uapi -L$CL/lib/clang/22/lib/linux/aarch64 "\$@"
EOT
cat > "$WORK/toolchain/host-clang" <<EOT
#!/bin/sh
exec $CL/bin/clang --target=x86_64-linux-gnu --sysroot=$G/sysroot --gcc-toolchain=$G -fuse-ld=lld -Qunused-arguments \\
  -B$G/lib/gcc/x86_64-linux/4.8.3 -L$G/lib/gcc/x86_64-linux/4.8.3 -L$G/x86_64-linux/lib64 "\$@"
EOT
chmod +x "$WORK/toolchain/android-clang" "$WORK/toolchain/host-clang"

export PATH="$T/prebuilts/rust-toolchain/linux-x86/1.93.1/bin:$PATH"
export CARGO_HOME="${EXIT_CARGO_HOME:-$WORK/cargo-home}" CARGO_INCREMENTAL=0
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$WORK/toolchain/android-clang"
# rustc links with -nodefaultlibs, so the compiler runtime must be named: the C code of aws-lc-sys is compiled with outline
# atomics and calls __aarch64_cas4_acq_rel and its siblings, which only libclang_rt.builtins provides.
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="-C link-arg=$CL/lib/clang/22/lib/linux/libclang_rt.builtins-aarch64-android.a"
# Reproducible output: no build-machine path may end up in the binary (panic locations, __FILE__ of the C code). The
# three roots are the source copy, the cargo home with the crates and the AOSP tree (toolchain and its standard library).
REMAP="$WORK/src=exit-daemon $CARGO_HOME=cargo-home $T=aosp"
for pair in $REMAP; do
  CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="$CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS --remap-path-prefix=$pair"
  CFLAGS_aarch64_linux_android="${CFLAGS_aarch64_linux_android:-} -ffile-prefix-map=$pair"
done
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS CFLAGS_aarch64_linux_android
export CC_aarch64_linux_android="$WORK/toolchain/android-clang" AR_aarch64_linux_android="$CL/bin/llvm-ar"
export CARGO_TARGET_X86_64_UNKNOWN_LINUX_GNU_LINKER="$WORK/toolchain/host-clang"
export CC_x86_64_unknown_linux_gnu="$WORK/toolchain/host-clang" AR_x86_64_unknown_linux_gnu="$CL/bin/llvm-ar"
export EXIT_LLVM_BIN="$CL/bin"
