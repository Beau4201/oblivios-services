// SPDX-License-Identifier: Apache-2.0
//! The command line of `exitd`. Every error names an option and never echoes a value: nothing a caller types ends up in
//! the output of the daemon.
//!
//!     exitd --state-dir <p> --cache-dir <p> --memory-max <bytes> --control <socket-path> --peer-uid <uid>
//!           [--device <ifname>] [--disable-fs-permission-checks]

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
    pub control: PathBuf,
    pub peer_uid: u32,
    pub device: String,
    /// Arti's permission checks of its own directories stay on unless this is given (`--disable-fs-permission-checks`).
    pub disable_fs_permission_checks: bool,
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
const DEVICE: &str = "--device";
/// The one option without a value.
const NO_FS_CHECKS: &str = "--disable-fs-permission-checks";
const OPTIONS: [&str; 6] = [STATE_DIR, CACHE_DIR, MEMORY_MAX, CONTROL, PEER_UID, DEVICE];

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

/// Parses the arguments after the program name.
pub fn parse_args(args: &[String]) -> Result<Args, ArgError> {
    let (mut state_dir, mut cache_dir, mut control) = (None, None, None);
    let (mut memory_max, mut peer_uid, mut device) = (None, None, None);
    let mut no_fs_checks = None;
    let mut i = 0;
    while i < args.len() {
        if args[i] == NO_FS_CHECKS {
            set(&mut no_fs_checks, NO_FS_CHECKS, true)?;
            i += 1;
            continue;
        }
        let name = *OPTIONS.iter().find(|o| **o == args[i]).ok_or(ArgError::Unknown)?;
        let value = args.get(i + 1).ok_or(ArgError::Value(name))?;
        match name {
            STATE_DIR => set(&mut state_dir, name, PathBuf::from(value))?,
            CACHE_DIR => set(&mut cache_dir, name, PathBuf::from(value))?,
            CONTROL => set(&mut control, name, PathBuf::from(value))?,
            MEMORY_MAX => set(&mut memory_max, name, digits(name, value)?)?,
            PEER_UID => {
                let uid = u32::try_from(digits(name, value)?).map_err(|_| ArgError::Value(name))?;
                set(&mut peer_uid, name, uid)?
            }
            _ => {
                if !valid_iface(value.as_bytes()) {
                    return Err(ArgError::Value(name));
                }
                set(&mut device, name, value.clone())?
            }
        }
        i += 2;
    }
    let memory_max = memory_max.ok_or(ArgError::Missing(MEMORY_MAX));
    let state_dir = state_dir.ok_or(ArgError::Missing(STATE_DIR))?;
    let cache_dir = cache_dir.ok_or(ArgError::Missing(CACHE_DIR))?;
    let memory_max = memory_max?;
    let control = control.ok_or(ArgError::Missing(CONTROL))?;
    let peer_uid = peer_uid.ok_or(ArgError::Missing(PEER_UID))?;
    let memory_max = validate_memory_max(memory_max).map_err(|_| ArgError::MemoryMax)?;
    Ok(Args {
        state_dir,
        cache_dir,
        memory_max,
        control,
        peer_uid,
        device: device.unwrap_or_else(|| DEFAULT_DEVICE.into()),
        disable_fs_permission_checks: no_fs_checks.unwrap_or(false),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn v(args: &[&str]) -> Vec<String> {
        args.iter().map(|s| s.to_string()).collect()
    }

    const FULL: [&str; 10] =
        ["--state-dir", "/data/s", "--cache-dir", "/data/c", "--memory-max", "67108864", "--control", "/data/ctl", "--peer-uid", "1000"];

    #[test]
    fn every_option_is_read() {
        let a = parse_args(&v(&FULL)).unwrap();
        assert_eq!(a.state_dir, std::path::PathBuf::from("/data/s"));
        assert_eq!(a.cache_dir, std::path::PathBuf::from("/data/c"));
        assert_eq!(a.memory_max, 67108864);
        assert_eq!(a.control, std::path::PathBuf::from("/data/ctl"));
        assert_eq!(a.peer_uid, 1000);
        assert_eq!(a.device, DEFAULT_DEVICE);
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
        for (i, name) in ["--state-dir", "--cache-dir", "--memory-max", "--control", "--peer-uid"].iter().enumerate() {
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
    fn the_messages_name_an_option_and_never_a_value() {
        assert_eq!(ArgError::Missing("--control").to_string(), "missing option --control");
        assert_eq!(ArgError::Value("--peer-uid").to_string(), "bad value for --peer-uid");
        assert_eq!(ArgError::Duplicate("--device").to_string(), "option --device given twice");
        assert_eq!(ArgError::Unknown.to_string(), "unknown option or argument");
        assert_eq!(ArgError::MemoryMax.to_string(), "memory quota outside the allowed bounds");
    }
}
