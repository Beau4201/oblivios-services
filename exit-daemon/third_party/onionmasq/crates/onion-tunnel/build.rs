// ObliviOS: upstream runs `git log` here and embeds the hash of whatever repository encloses the build directory, which
// makes the binary depend on the checkout it was built in (and name the wrong project). The vendored library is one fixed
// upstream commit, so the value is a constant (exit-daemon/SOURCE.md).
fn main() {
    println!("cargo:rustc-env=ONIONTUNNEL_COMMIT_HASH=oblivios-vendored-849a8d14d165");
}
