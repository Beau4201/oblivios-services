// SPDX-License-Identifier: Apache-2.0
//! The binary itself: a refused configuration ends it with status 2 and a fixed message, before anything is opened.

use std::process::Command;

fn exitd(args: &[&str]) -> std::process::Output {
    Command::new(env!("CARGO_BIN_EXE_exitd")).args(args).output().unwrap()
}

/// A file in the temporary directory that is removed when the guard goes.
struct TempFile(std::path::PathBuf);

impl TempFile {
    fn new(tag: &str, bytes: &[u8]) -> Self {
        let p = std::env::temp_dir().join(format!("exitd-cli-{}-{tag}", std::process::id()));
        std::fs::write(&p, bytes).unwrap();
        TempFile(p)
    }

    fn path(&self) -> &str {
        self.0.to_str().unwrap()
    }
}

impl Drop for TempFile {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.0);
    }
}

const LIST: &[u8] = b"exit-mask-list\t1\nads.example.com\t7\tExample Ads\n";

fn daemon_args<'a>(memory_max: &'a str, list: &'a str) -> [&'a str; 12] {
    [
        "--state-dir", "/nonexistent/s", "--cache-dir", "/nonexistent/c", "--memory-max", memory_max, "--control", "/nonexistent/ctl",
        "--peer-uid", "0", "--mask-list", list,
    ]
}

#[test]
fn a_quota_of_one_byte_exits_with_status_2_and_prints_no_destination() {
    let out = exitd(&daemon_args("1", "/nonexistent/list"));
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
fn a_mask_list_that_is_missing_or_malformed_exits_with_status_2_and_names_nothing() {
    let bad = TempFile::new("bad", b"ads.example.com\t7\tExample Ads\n");
    for list in ["/nonexistent/secret-list-name", bad.path()] {
        let out = exitd(&daemon_args("67108864", list));
        assert_eq!(out.status.code(), Some(2), "{list}");
        assert!(out.stdout.is_empty());
        assert_eq!(String::from_utf8_lossy(&out.stderr), "exitd: mask list could not be loaded\n", "no path, no row, in {list}");
    }
}

#[test]
fn an_unreadable_configuration_exits_with_status_1_not_with_defaults() {
    // The directories do not exist, so Arti's configuration cannot be loaded: the daemon stops instead of going on.
    let list = TempFile::new("ok", LIST);
    let out = exitd(&daemon_args("67108864", list.path()));
    assert_eq!(out.status.code(), Some(1));
    assert!(out.stdout.is_empty());
    assert_eq!(String::from_utf8_lossy(&out.stderr), "exitd: configuration could not be applied\n");
}

#[test]
fn an_argument_that_is_not_utf8_exits_with_status_2_and_is_not_echoed() {
    use std::os::unix::ffi::OsStrExt;
    let bad = std::ffi::OsStr::from_bytes(&[0xff, 0xfe]);
    let out = Command::new(env!("CARGO_BIN_EXE_exitd")).arg("--state-dir").arg(bad).output().unwrap();
    assert_eq!(out.status.code(), Some(2));
    assert!(out.stdout.is_empty());
    assert_eq!(String::from_utf8_lossy(&out.stderr), "exitd: argument is not UTF-8\n");
}

#[test]
fn the_route_helper_refuses_every_option_of_the_daemon() {
    for extra in [["--state-dir", "/s"], ["--cache-dir", "/c"], ["--memory-max", "67108864"], ["--mask-list", "/m"], ["--device", "exit0"]] {
        let mut args = vec!["--routes", "--control", "/nonexistent/ctl", "--peer-uid", "0"];
        args.extend(extra);
        let out = exitd(&args);
        assert_eq!(out.status.code(), Some(2), "{extra:?}");
        assert!(out.stdout.is_empty());
        assert_eq!(String::from_utf8_lossy(&out.stderr), "exitd: unknown option or argument\n");
    }
}

/// The helper as a process: it answers `Hello` and `GetApplied` over its socket (reading the rules needs no privilege).
#[test]
fn the_route_helper_serves_its_socket_with_the_real_kernel_behind_it() {
    use exit_daemon::control::DecodeError;
    use exit_daemon::routes::proto::{decode_reply, encode_msg, RouteMsg, RouteReply};
    use std::io::{Read, Write};
    use std::os::unix::fs::MetadataExt;
    use std::os::unix::net::UnixStream;
    use std::time::{Duration, Instant};

    let me = std::fs::metadata("/proc/self").unwrap().uid();
    let dir = std::env::temp_dir().join(format!("exitd-cli-routes-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let sock = dir.join("ctl");
    // Killed when the test ends, also by a failed assertion: a helper left running would hold the test output open for ever.
    struct Helper(std::process::Child);
    impl Drop for Helper {
        fn drop(&mut self) {
            let _ = self.0.kill();
            let _ = self.0.wait();
        }
    }
    let helper = Helper(
        Command::new(env!("CARGO_BIN_EXE_exitd"))
            .args(["--routes", "--control", sock.to_str().unwrap(), "--peer-uid", &me.to_string()])
            .spawn()
            .unwrap(),
    );
    let start = Instant::now();
    let mut conn = loop {
        match UnixStream::connect(&sock) {
            Ok(c) => break c,
            Err(_) if start.elapsed() < Duration::from_secs(10) => std::thread::sleep(Duration::from_millis(50)),
            Err(e) => panic!("the helper never listened: {e}"),
        }
    };
    conn.set_read_timeout(Some(Duration::from_secs(10))).unwrap();
    let mut ask = |m: RouteMsg| {
        conn.write_all(&encode_msg(&m)).unwrap();
        let mut buf = Vec::new();
        let mut b = [0u8; 1];
        loop {
            match decode_reply(&buf) {
                Ok((r, _)) => return r,
                Err(DecodeError::Incomplete) => {
                    conn.read_exact(&mut b).unwrap();
                    buf.push(b[0]);
                }
                Err(e) => panic!("{e:?}"),
            }
        }
    };
    assert_eq!(ask(RouteMsg::Hello { version: 1 }), RouteReply::Ready { version: 1 });
    assert!(matches!(ask(RouteMsg::GetApplied { seq: 3 }), RouteReply::Applied { seq: 3, .. }), "the kernel answered the dump");
    drop(helper);
    std::fs::remove_dir_all(&dir).unwrap();
}
