// SPDX-License-Identifier: Apache-2.0
//! Tests first: the argument parser itself is added after they have been seen to fail.

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
    fn the_messages_name_an_option_and_never_a_value() {
        assert_eq!(ArgError::Missing("--control").to_string(), "missing option --control");
        assert_eq!(ArgError::Value("--peer-uid").to_string(), "bad value for --peer-uid");
        assert_eq!(ArgError::Duplicate("--device").to_string(), "option --device given twice");
        assert_eq!(ArgError::Unknown.to_string(), "unknown option or argument");
        assert_eq!(ArgError::MemoryMax.to_string(), "memory quota outside the allowed bounds");
    }
}
