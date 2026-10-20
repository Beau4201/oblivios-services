// SPDX-License-Identifier: Apache-2.0
//! The binary itself: a refused configuration ends it with status 2 and a fixed message, before anything is opened.

use std::process::Command;

fn exitd(args: &[&str]) -> std::process::Output {
    Command::new(env!("CARGO_BIN_EXE_exitd")).args(args).output().unwrap()
}

#[test]
fn a_quota_of_one_byte_exits_with_status_2_and_prints_no_destination() {
    let out = exitd(&[
        "--state-dir", "/nonexistent/s", "--cache-dir", "/nonexistent/c", "--memory-max", "1", "--control",
        "/nonexistent/ctl", "--peer-uid", "0",
    ]);
    assert_eq!(out.status.code(), Some(2));
    assert!(out.stdout.is_empty());
    assert_eq!(String::from_utf8_lossy(&out.stderr), "exitd: memory quota outside the allowed bounds\n");
}

#[test]
fn no_arguments_exit_with_status_2() {
    let out = exitd(&[]);
    assert_eq!(out.status.code(), Some(2));
    assert!(out.stdout.is_empty());
    assert_eq!(String::from_utf8_lossy(&out.stderr), "exitd: missing option --state-dir\n");
}

#[test]
fn an_unreadable_configuration_exits_with_status_1_not_with_defaults() {
    // The directories do not exist, so Arti's configuration cannot be loaded: the daemon stops instead of going on.
    let out = exitd(&[
        "--state-dir", "/nonexistent/s", "--cache-dir", "/nonexistent/c", "--memory-max", "67108864", "--control",
        "/nonexistent/ctl", "--peer-uid", "0",
    ]);
    assert_eq!(out.status.code(), Some(1));
    assert!(out.stdout.is_empty());
}
