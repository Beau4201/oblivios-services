// SPDX-License-Identifier: Apache-2.0
//! The command line of `exitd`. Every error names an option and never echoes a value: nothing a caller types ends up in
//! the output of the daemon.
//!
//!     exitd --state-dir <p> --cache-dir <p> --memory-max <bytes>
//!           ( --control <socket-path> | --control-fd-env <ANDROID_SOCKET_name> )
//!           ( --peer-uid <uid> | --peer-context <selinux-type> )
//!           --mask-list <file> [--device <ifname>] [--disable-fs-permission-checks]
//!     exitd --routes ( --control <socket-path> | --control-fd-env <ANDROID_SOCKET_name> )
//!                    ( --peer-uid <uid> | --peer-context <selinux-type> )
//!
//! The service definition gives the socket (`--control-fd-env`) and names the exit app's SELinux type
//! (`--peer-context`); the path and the uid are for runs on a phone as root and for the tests. `--routes` starts the route
//! helper (ADR-186), which has no Tor state, no list and no tunnel: any option of the daemon next to it is refused.

use crate::control::{valid_iface, validate_memory_max};
use std::fmt;
use std::path::PathBuf;

/// The tun interface the daemon creates when `--device` is not given.
pub const DEFAULT_DEVICE: &str = "exit0";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Args {
    pub state_dir: PathBuf,
    pub cache_dir: PathBuf,
    pub memory_max: u64,
    pub control: ControlSource,
    pub peer: Peer,
    pub device: String,
    /// The list of masked host names (a file of the system image); a daemon without one would mask nothing, so it is required.
    pub mask_list: PathBuf,
    /// Arti's permission checks of its own directories stay on unless this is given (`--disable-fs-permission-checks`).
    pub disable_fs_permission_checks: bool,
}

/// What the route helper needs: where its socket comes from and who may use it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RouteArgs {
    pub control: ControlSource,
    pub peer: Peer,
}

/// The two things `exitd` can be.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Command {
    Daemon(Args),
    Routes(RouteArgs),
}

/// Where the control socket comes from.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ControlSource {
    /// The daemon creates it (mode 0600).
    Path(PathBuf),
    /// `init` created it; the descriptor number is in this environment variable (`ANDROID_SOCKET_<name>`).
    InheritedFd(String),
}

/// Who may use the control socket.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Peer {
    Uid(u32),
    /// An SELinux type, for example `exit_app`.
    Context(String),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ArgError {
    Missing(&'static str),
    Duplicate(&'static str),
    Value(&'static str),
    Unknown,
    MemoryMax,
}

impl fmt::Display for ArgError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            ArgError::Missing(o) => write!(f, "missing option {o}"),
            ArgError::Duplicate(o) => write!(f, "option {o} given twice"),
            ArgError::Value(o) => write!(f, "bad value for {o}"),
            ArgError::Unknown => write!(f, "unknown option or argument"),
            ArgError::MemoryMax => write!(f, "memory quota outside the allowed bounds"),
        }
    }
}

impl std::error::Error for ArgError {}

const STATE_DIR: &str = "--state-dir";
const CACHE_DIR: &str = "--cache-dir";
const MEMORY_MAX: &str = "--memory-max";
const CONTROL: &str = "--control";
const PEER_UID: &str = "--peer-uid";
const CONTROL_FD_ENV: &str = "--control-fd-env";
const PEER_CONTEXT: &str = "--peer-context";
const DEVICE: &str = "--device";
const MASK_LIST: &str = "--mask-list";
/// The mode switch: the first argument, and only there.
const ROUTES: &str = "--routes";
/// The one option without a value.
const NO_FS_CHECKS: &str = "--disable-fs-permission-checks";
const OPTIONS: [&str; 9] = [STATE_DIR, CACHE_DIR, MEMORY_MAX, CONTROL, PEER_UID, CONTROL_FD_ENV, PEER_CONTEXT, DEVICE, MASK_LIST];
/// The options the route helper does not have: it holds no Tor state, no list, no tunnel and no quota.
const DAEMON_ONLY: [&str; 6] = [STATE_DIR, CACHE_DIR, MEMORY_MAX, DEVICE, MASK_LIST, NO_FS_CHECKS];

/// `ANDROID_SOCKET_` and a name of letters, digits and underscores (what `init` writes), at most 64 bytes in all.
fn valid_socket_var(v: &str) -> bool {
    v.strip_prefix("ANDROID_SOCKET_")
        .is_some_and(|n| !n.is_empty() && v.len() <= 64 && n.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_'))
}

/// An SELinux type name: lower case letters, digits and underscores, starting with a letter, at most 64 bytes.
fn valid_selinux_type(v: &str) -> bool {
    let mut b = v.bytes();
    v.len() <= 64 && b.next().is_some_and(|c| c.is_ascii_lowercase()) && b.all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == b'_')
}

fn set<T>(slot: &mut Option<T>, name: &'static str, value: T) -> Result<(), ArgError> {
    if slot.is_some() {
        return Err(ArgError::Duplicate(name));
    }
    *slot = Some(value);
    Ok(())
}

/// Plain decimal digits only: no sign, no space, no prefix.
fn digits(name: &'static str, s: &str) -> Result<u64, ArgError> {
    if s.is_empty() || !s.bytes().all(|b| b.is_ascii_digit()) {
        return Err(ArgError::Value(name));
    }
    s.parse::<u64>().map_err(|_| ArgError::Value(name))
}

/// Every option as it was given, before the mode decides which ones are required.
#[derive(Default)]
struct Scan {
    state_dir: Option<PathBuf>,
    cache_dir: Option<PathBuf>,
    mask_list: Option<PathBuf>,
    memory_max: Option<u64>,
    device: Option<String>,
    no_fs_checks: Option<bool>,
    control: Option<PathBuf>,
    control_fd_env: Option<String>,
    peer_uid: Option<u32>,
    peer_context: Option<String>,
}

/// Reads the options; in route mode (`daemon` false) an option of the daemon is as unknown as any other.
fn scan(args: &[String], daemon: bool) -> Result<Scan, ArgError> {
    let mut s = Scan::default();
    let mut i = 0;
    while i < args.len() {
        if !daemon && DAEMON_ONLY.contains(&args[i].as_str()) {
            return Err(ArgError::Unknown);
        }
        if args[i] == NO_FS_CHECKS {
            set(&mut s.no_fs_checks, NO_FS_CHECKS, true)?;
            i += 1;
            continue;
        }
        let name = *OPTIONS.iter().find(|o| **o == args[i]).ok_or(ArgError::Unknown)?;
        let value = args.get(i + 1).ok_or(ArgError::Value(name))?;
        match name {
            STATE_DIR => set(&mut s.state_dir, name, PathBuf::from(value))?,
            CACHE_DIR => set(&mut s.cache_dir, name, PathBuf::from(value))?,
            MASK_LIST => set(&mut s.mask_list, name, PathBuf::from(value))?,
            CONTROL => set(&mut s.control, name, PathBuf::from(value))?,
            CONTROL_FD_ENV => {
                if !valid_socket_var(value) {
                    return Err(ArgError::Value(name));
                }
                set(&mut s.control_fd_env, name, value.clone())?
            }
            PEER_CONTEXT => {
                if !valid_selinux_type(value) {
                    return Err(ArgError::Value(name));
                }
                set(&mut s.peer_context, name, value.clone())?
            }
            MEMORY_MAX => set(&mut s.memory_max, name, digits(name, value)?)?,
            PEER_UID => {
                let uid = u32::try_from(digits(name, value)?).map_err(|_| ArgError::Value(name))?;
                set(&mut s.peer_uid, name, uid)?
            }
            _ => {
                if !valid_iface(value.as_bytes()) {
                    return Err(ArgError::Value(name));
                }
                set(&mut s.device, name, value.clone())?
            }
        }
        i += 2;
    }
    Ok(s)
}

/// Exactly one source of the socket and exactly one way to name the peer: both would leave it open which one counts.
fn control_and_peer(s: &mut Scan) -> Result<(ControlSource, Peer), ArgError> {
    let control = match (s.control.take(), s.control_fd_env.take()) {
        (Some(path), None) => ControlSource::Path(path),
        (None, Some(var)) => ControlSource::InheritedFd(var),
        (Some(_), Some(_)) => return Err(ArgError::Duplicate(CONTROL)),
        (None, None) => return Err(ArgError::Missing(CONTROL)),
    };
    let peer = match (s.peer_uid.take(), s.peer_context.take()) {
        (Some(uid), None) => Peer::Uid(uid),
        (None, Some(t)) => Peer::Context(t),
        (Some(_), Some(_)) => return Err(ArgError::Duplicate(PEER_UID)),
        (None, None) => return Err(ArgError::Missing(PEER_UID)),
    };
    Ok((control, peer))
}

/// Parses the arguments of the daemon, after the program name.
pub fn parse_args(args: &[String]) -> Result<Args, ArgError> {
    let mut s = scan(args, true)?;
    let memory_max = s.memory_max.ok_or(ArgError::Missing(MEMORY_MAX));
    let state_dir = s.state_dir.take().ok_or(ArgError::Missing(STATE_DIR))?;
    let cache_dir = s.cache_dir.take().ok_or(ArgError::Missing(CACHE_DIR))?;
    let memory_max = memory_max?;
    let mask_list = s.mask_list.take().ok_or(ArgError::Missing(MASK_LIST))?;
    let (control, peer) = control_and_peer(&mut s)?;
    let memory_max = validate_memory_max(memory_max).map_err(|_| ArgError::MemoryMax)?;
    Ok(Args {
        state_dir,
        cache_dir,
        memory_max,
        control,
        peer,
        device: s.device.take().unwrap_or_else(|| DEFAULT_DEVICE.into()),
        mask_list,
        disable_fs_permission_checks: s.no_fs_checks.unwrap_or(false),
    })
}

/// Parses the arguments after `--routes`.
pub fn parse_route_args(args: &[String]) -> Result<RouteArgs, ArgError> {
    let (control, peer) = control_and_peer(&mut scan(args, false)?)?;
    Ok(RouteArgs { control, peer })
}

/// The mode is the first argument: `--routes` starts the route helper, anything else is the daemon's command line.
pub fn parse_command(args: &[String]) -> Result<Command, ArgError> {
    match args.split_first() {
        Some((first, rest)) if first == ROUTES => parse_route_args(rest).map(Command::Routes),
        _ => parse_args(args).map(Command::Daemon),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn v(args: &[&str]) -> Vec<String> {
        args.iter().map(|s| s.to_string()).collect()
    }

    const FULL: [&str; 12] = [
        "--state-dir", "/data/s", "--cache-dir", "/data/c", "--memory-max", "67108864", "--control", "/data/ctl", "--peer-uid", "1000",
        "--mask-list", "/data/list.tsv",
    ];

    #[test]
    fn every_option_is_read() {
        let a = parse_args(&v(&FULL)).unwrap();
        assert_eq!(a.state_dir, std::path::PathBuf::from("/data/s"));
        assert_eq!(a.cache_dir, std::path::PathBuf::from("/data/c"));
        assert_eq!(a.memory_max, 67108864);
        assert_eq!(a.control, ControlSource::Path("/data/ctl".into()));
        assert_eq!(a.peer, Peer::Uid(1000));
        assert_eq!(a.device, DEFAULT_DEVICE);
    }

    #[test]
    fn the_service_form_names_a_socket_variable_and_an_selinux_type() {
        let svc = |var: &str, ty: &str| {
            v(&[
                "--state-dir", "/s", "--cache-dir", "/c", "--memory-max", "67108864", "--mask-list", "/m", "--control-fd-env", var,
                "--peer-context", ty,
            ])
        };
        let a = parse_args(&svc("ANDROID_SOCKET_exitd", "exit_app")).unwrap();
        assert_eq!(a.control, ControlSource::InheritedFd("ANDROID_SOCKET_exitd".into()));
        assert_eq!(a.peer, Peer::Context("exit_app".into()));
        for bad in ["", "exitd", "ANDROID_SOCKET_", "ANDROID_SOCKET_a b", "ANDROID_SOCKET_a/b", "android_socket_exitd", "ANDROID_SOCKET_é"] {
            assert_eq!(parse_args(&svc(bad, "exit_app")), Err(ArgError::Value("--control-fd-env")), "{bad:?}");
        }
        let long = format!("ANDROID_SOCKET_{}", "a".repeat(50));
        assert_eq!(parse_args(&svc(&long, "exit_app")), Err(ArgError::Value("--control-fd-env")), "65 bytes");
        assert!(parse_args(&svc(&format!("ANDROID_SOCKET_{}", "a".repeat(49)), "exit_app")).is_ok(), "64 bytes");
        for bad in ["", "Exit_app", "1app", "exit-app", "exit app", "exit_app:s0", "u:r:exit_app", &"a".repeat(65)] {
            assert_eq!(parse_args(&svc("ANDROID_SOCKET_exitd", bad)), Err(ArgError::Value("--peer-context")), "{bad:?}");
        }
        assert!(parse_args(&svc("ANDROID_SOCKET_exitd", &"a".repeat(64))).is_ok());
    }

    #[test]
    fn the_socket_and_the_peer_each_come_from_exactly_one_option() {
        let base = ["--state-dir", "/s", "--cache-dir", "/c", "--memory-max", "67108864", "--mask-list", "/m"];
        let with = |more: &[&str]| {
            let mut a = v(&base);
            a.extend(v(more));
            parse_args(&a)
        };
        assert_eq!(with(&["--peer-uid", "1"]), Err(ArgError::Missing("--control")));
        assert_eq!(with(&["--control", "/x"]), Err(ArgError::Missing("--peer-uid")));
        assert_eq!(
            with(&["--control", "/x", "--control-fd-env", "ANDROID_SOCKET_a", "--peer-uid", "1"]),
            Err(ArgError::Duplicate("--control"))
        );
        assert_eq!(
            with(&["--control", "/x", "--peer-uid", "1", "--peer-context", "exit_app"]),
            Err(ArgError::Duplicate("--peer-uid"))
        );
        assert!(with(&["--control", "/x", "--peer-context", "exit_app"]).is_ok());
        assert!(with(&["--control-fd-env", "ANDROID_SOCKET_a", "--peer-uid", "1"]).is_ok());
    }

    #[test]
    fn the_device_is_optional_and_checked() {
        let mut args = v(&FULL);
        args.extend(v(&["--device", "tun7"]));
        assert_eq!(parse_args(&args).unwrap().device, "tun7");
        for bad in ["", "a/b", "sixteen_chars_xxx", "ü"] {
            let mut args = v(&FULL);
            args.extend(v(&["--device", bad]));
            assert_eq!(parse_args(&args), Err(ArgError::Value("--device")), "{bad:?}");
        }
    }

    #[test]
    fn a_missing_option_is_named() {
        for (i, name) in ["--state-dir", "--cache-dir", "--memory-max", "--control", "--peer-uid", "--mask-list"].iter().enumerate() {
            let mut args = v(&FULL);
            args.drain(i * 2..i * 2 + 2);
            assert_eq!(parse_args(&args), Err(ArgError::Missing(name)));
        }
        assert_eq!(parse_args(&[]), Err(ArgError::Missing("--state-dir")));
    }

    #[test]
    fn an_option_given_twice_or_unknown_or_without_value_is_refused() {
        let mut args = v(&FULL);
        args.extend(v(&["--peer-uid", "1"]));
        assert_eq!(parse_args(&args), Err(ArgError::Duplicate("--peer-uid")));
        let mut args = v(&FULL);
        args.push("--debug".into());
        assert_eq!(parse_args(&args), Err(ArgError::Unknown));
        let mut args = v(&FULL);
        args.push("--device".into());
        assert_eq!(parse_args(&args), Err(ArgError::Value("--device")));
        assert_eq!(parse_args(&v(&["positional"])), Err(ArgError::Unknown));
    }

    #[test]
    fn numbers_must_be_plain_digits() {
        for bad in ["", "-1", "+1", "1.5", "0x10", " 1", "99999999999999999999999"] {
            let mut args = v(&FULL);
            args[9] = bad.into();
            assert_eq!(parse_args(&args), Err(ArgError::Value("--peer-uid")), "{bad:?}");
            let mut args = v(&FULL);
            args[5] = bad.into();
            assert!(matches!(parse_args(&args), Err(ArgError::Value("--memory-max"))), "{bad:?}");
        }
        let mut args = v(&FULL);
        args[9] = "4294967296".into(); // one past u32::MAX
        assert_eq!(parse_args(&args), Err(ArgError::Value("--peer-uid")));
    }

    #[test]
    fn the_memory_quota_is_checked_against_the_bounds() {
        let mut args = v(&FULL);
        args[5] = "1".into();
        assert_eq!(parse_args(&args), Err(ArgError::MemoryMax));
        args[5] = (1024u64 * 1024 * 1024 + 1).to_string();
        assert_eq!(parse_args(&args), Err(ArgError::MemoryMax));
        args[5] = (32u64 * 1024 * 1024).to_string();
        assert_eq!(parse_args(&args).unwrap().memory_max, 32 * 1024 * 1024);
    }

    #[test]
    fn the_permission_checks_stay_on_unless_asked_otherwise() {
        assert!(!parse_args(&v(&FULL)).unwrap().disable_fs_permission_checks);
        let mut args = v(&FULL);
        args.push("--disable-fs-permission-checks".into());
        assert!(parse_args(&args).unwrap().disable_fs_permission_checks);
        // The flag takes no value and can stand anywhere between the options.
        let mut args = v(&["--disable-fs-permission-checks"]);
        args.extend(v(&FULL));
        assert!(parse_args(&args).unwrap().disable_fs_permission_checks);
        let mut args = v(&FULL);
        args.insert(4, "--disable-fs-permission-checks".into());
        let a = parse_args(&args).unwrap();
        assert!(a.disable_fs_permission_checks);
        assert_eq!(a.memory_max, 67108864);
        // Given twice is refused like any other option.
        let mut args = v(&FULL);
        args.extend(v(&["--disable-fs-permission-checks", "--disable-fs-permission-checks"]));
        assert_eq!(parse_args(&args), Err(ArgError::Duplicate("--disable-fs-permission-checks")));
    }

    #[test]
    fn the_mask_list_is_required_and_given_once() {
        assert_eq!(parse_args(&v(&FULL)).unwrap().mask_list, std::path::PathBuf::from("/data/list.tsv"));
        let mut args = v(&FULL);
        args.extend(v(&["--mask-list", "/other"]));
        assert_eq!(parse_args(&args), Err(ArgError::Duplicate("--mask-list")));
        let mut args = v(&FULL);
        args.truncate(10);
        args.push("--mask-list".into());
        assert_eq!(parse_args(&args), Err(ArgError::Value("--mask-list")));
    }

    fn route(args: &[&str]) -> Result<Command, ArgError> {
        let mut a = v(&["--routes"]);
        a.extend(v(args));
        parse_command(&a)
    }

    #[test]
    fn the_route_helper_takes_a_socket_and_a_peer_and_nothing_else() {
        assert_eq!(
            route(&["--control-fd-env", "ANDROID_SOCKET_exitd_route", "--peer-context", "exit_app"]),
            Ok(Command::Routes(RouteArgs {
                control: ControlSource::InheritedFd("ANDROID_SOCKET_exitd_route".into()),
                peer: Peer::Context("exit_app".into())
            }))
        );
        assert!(matches!(route(&["--control", "/x", "--peer-uid", "1"]), Ok(Command::Routes(_))));
        // It has no Tor state, no list, no tunnel and no quota: each of those is refused, so that a service definition that
        // starts the wrong mode with the daemon's options fails at once.
        for opt in [
            ["--state-dir", "/s"],
            ["--cache-dir", "/c"],
            ["--memory-max", "67108864"],
            ["--device", "exit0"],
            ["--mask-list", "/m"],
        ] {
            let mut args = vec!["--control", "/x", "--peer-uid", "1"];
            args.extend(opt);
            assert_eq!(route(&args), Err(ArgError::Unknown), "{opt:?}");
        }
        // The one switch without a value is refused too.
        assert_eq!(route(&["--control", "/x", "--peer-uid", "1", "--disable-fs-permission-checks"]), Err(ArgError::Unknown));
        assert_eq!(route(&["--peer-uid", "1"]), Err(ArgError::Missing("--control")));
        assert_eq!(route(&["--control", "/x"]), Err(ArgError::Missing("--peer-uid")));
        assert_eq!(route(&["--control", "/x", "--peer-uid", "1", "--peer-uid", "2"]), Err(ArgError::Duplicate("--peer-uid")));
    }

    #[test]
    fn the_mode_switch_counts_only_as_the_first_argument() {
        assert!(matches!(parse_command(&v(&FULL)), Ok(Command::Daemon(_))));
        let mut args = v(&FULL);
        args.push("--routes".into());
        assert_eq!(parse_command(&args), Err(ArgError::Unknown));
        assert_eq!(parse_command(&v(&["--routes"])), Err(ArgError::Missing("--control")));
    }

    #[test]
    fn the_messages_name_an_option_and_never_a_value() {
        assert_eq!(ArgError::Missing("--control").to_string(), "missing option --control");
        assert_eq!(ArgError::Value("--peer-uid").to_string(), "bad value for --peer-uid");
        assert_eq!(ArgError::Duplicate("--device").to_string(), "option --device given twice");
        assert_eq!(ArgError::Unknown.to_string(), "unknown option or argument");
        assert_eq!(ArgError::MemoryMax.to_string(), "memory quota outside the allowed bounds");
    }
}
