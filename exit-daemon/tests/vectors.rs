// SPDX-License-Identifier: Apache-2.0
//! The golden vectors of the two control protocols (`tests/vectors.txt`). The exit app's Java codec checks the same file, so
//! a change to one codec that the other does not follow fails a test on one side.

use exit_daemon::control::{
    decode_from_daemon, decode_to_daemon, encode_from_daemon, encode_to_daemon, DaemonState, ErrorCode, FromDaemon, Kind,
    ToDaemon, UidClass,
};
use exit_daemon::routes::proto::{decode_msg, decode_reply, encode_msg, encode_reply, RouteMsg, RouteReply};
use exit_daemon::routes::spec::{RouteSpec, UidRange};
use std::collections::BTreeMap;

fn vectors() -> BTreeMap<String, Vec<u8>> {
    let mut map = BTreeMap::new();
    for line in include_str!("vectors.txt").lines().filter(|l| !l.starts_with('#') && !l.is_empty()) {
        let (name, hex) = line.split_once('\t').expect("name TAB hex");
        assert!(hex.len() % 2 == 0 && hex.bytes().all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase()), "{name}: lower-case hex");
        let bytes = (0..hex.len()).step_by(2).map(|i| u8::from_str_radix(&hex[i..i + 2], 16).unwrap()).collect();
        assert!(map.insert(name.to_string(), bytes).is_none(), "{name} twice");
    }
    map
}

#[test]
fn every_vector_is_built_by_the_rust_codec_and_read_back() {
    let v = vectors();
    let mut checked = 0;
    macro_rules! both {
        ($name:expr, $msg:expr, $enc:ident, $dec:ident) => {{
            let msg = $msg;
            let bytes = &v[$name];
            assert_eq!(&$enc(&msg), bytes, "{}: encoded", $name);
            assert_eq!($dec(bytes), Ok((msg, bytes.len())), "{}: decoded", $name);
            checked += 1;
        }};
    }
    both!("to_daemon.hello", ToDaemon::Hello { version: 4 }, encode_to_daemon, decode_to_daemon);
    both!("to_daemon.set_memory_max", ToDaemon::SetMemoryMax { seq: 7, bytes: 64 * 1024 * 1024 }, encode_to_daemon, decode_to_daemon);
    both!("to_daemon.refresh_app", ToDaemon::RefreshApp { seq: 3, uid: 10123 }, encode_to_daemon, decode_to_daemon);
    both!("to_daemon.refresh_all", ToDaemon::RefreshAll { seq: 4 }, encode_to_daemon, decode_to_daemon);
    both!("to_daemon.shutdown", ToDaemon::Shutdown { seq: 5 }, encode_to_daemon, decode_to_daemon);
    both!("to_daemon.get_state", ToDaemon::GetState { seq: 9 }, encode_to_daemon, decode_to_daemon);
    both!(
        "to_daemon.set_policy",
        ToDaemon::SetPolicy {
            seq: 1,
            classes: vec![
                UidClass { start: 10000, end: 10000, kind: Kind::Tor },
                UidClass { start: 10001, end: 10230, kind: Kind::Masked },
                UidClass { start: 10231, end: 10231, kind: Kind::Exempt },
            ]
        },
        encode_to_daemon,
        decode_to_daemon
    );
    both!("to_daemon.set_overrides", ToDaemon::SetOverrides { seq: 2, uid: 10123, companies: vec![7, 300] }, encode_to_daemon, decode_to_daemon);
    both!("to_daemon.release", ToDaemon::Release { seq: 11, uid: 10123, company: 300 }, encode_to_daemon, decode_to_daemon);
    both!("to_daemon.set_country", ToDaemon::SetCountry { seq: 12, uid: 10123, country: Some(*b"ES") }, encode_to_daemon, decode_to_daemon);
    both!("to_daemon.clear_country", ToDaemon::SetCountry { seq: 13, uid: 10123, country: None }, encode_to_daemon, decode_to_daemon);
    both!("from_daemon.ready", FromDaemon::Ready { version: 4, iface: "exit0".into() }, encode_from_daemon, decode_from_daemon);
    both!("from_daemon.state_up", FromDaemon::State { state: DaemonState::Up }, encode_from_daemon, decode_from_daemon);
    both!("from_daemon.failures", FromDaemon::Failures { uid: 10123, company: 7, count: 3 }, encode_from_daemon, decode_from_daemon);
    both!("from_daemon.promoted", FromDaemon::Promoted { uid: 10123, company: 7, fresh: true }, encode_from_daemon, decode_from_daemon);
    both!("from_daemon.ack", FromDaemon::Ack { seq: 9 }, encode_from_daemon, decode_from_daemon);
    both!("from_daemon.error", FromDaemon::Error { seq: 2, code: ErrorCode::TooMany }, encode_from_daemon, decode_from_daemon);
    both!("route.hello", RouteMsg::Hello { version: 1 }, encode_msg, decode_msg);
    both!(
        "route.apply",
        RouteMsg::Apply {
            seq: 5,
            spec: RouteSpec {
                iface: "exit0".into(),
                steer: vec![UidRange { start: 10000, end: 10230 }],
                tor_guard: vec![UidRange { start: 10231, end: 10231 }],
                oem_netid: Some(0x0102_0304),
            }
        },
        encode_msg,
        decode_msg
    );
    both!("route.clear", RouteMsg::Clear { seq: 3 }, encode_msg, decode_msg);
    both!("route.get_applied", RouteMsg::GetApplied { seq: 4 }, encode_msg, decode_msg);
    both!("route_reply.ready", RouteReply::Ready { version: 1 }, encode_reply, decode_reply);
    both!("route_reply.applied", RouteReply::Applied { seq: 9, rules: 0x0102 }, encode_reply, decode_reply);
    both!("route_reply.error", RouteReply::Error { seq: 9, code: ErrorCode::Failed }, encode_reply, decode_reply);
    // The addresses of the tunnel: the route helper writes them and the exit app registers them, so they are pinned here.
    let (a4, p4) = exit_daemon::routes::spec::TUN_V4;
    assert_eq!(v["const.tun_v4"], [a4.octets().as_slice(), &[p4]].concat());
    let (a6, p6) = exit_daemon::routes::spec::TUN_V6;
    assert_eq!(v["const.tun_v6"], [a6.octets().as_slice(), &[p6]].concat());
    // The DNS listener's address is a constant of the vendored library; its test pins it, and the exit app's check pins it here.
    assert_eq!(v["const.dns_v4"], vec![169, 254, 42, 53]);
    checked += 3;
    assert_eq!(checked, v.len(), "every line of the file is checked, and every check has a line");
}
