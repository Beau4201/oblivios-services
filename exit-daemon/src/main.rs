// SPDX-License-Identifier: Apache-2.0
//! `exitd`: the entry point is added by the entry-point task; until then the binary only reports its version.
fn main() {
    println!("exitd {}", env!("CARGO_PKG_VERSION"));
}
