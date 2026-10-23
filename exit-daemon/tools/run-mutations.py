#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Mutation check of the exit daemon's host tests.

Copies the crate to a temporary directory, applies ONE mutation at a time (an exact, unique string replaced by another)
and runs tools/check-on-server.sh on the copy. A mutation is killed when the tests fail for it. A mutation that does not
compile is NOT counted as killed: it proves nothing about the tests. A mutation whose text is not found exactly once in
its file is an error of this list, not a surviving mutant.

    EXIT_BUILD_SSH=user@host tools/run-mutations.py [--check-applies] [--shard=I/N] [NAME_FILTER ...]

--check-applies only checks that every mutation applies to the current sources. Left out on purpose: `guard: the window keeps one sample more` (`while p.history.len() > 1` to `> 2` in `Guard::tick`): with two samples left the second is the one just pushed, whose age is 0, so the age test cannot be true there and the extra guard changes nothing; and `dns: no questions are routed` (dropping `|| query.questions.is_empty()` from `route_query`): a query
without questions leaves both flags false, and the `(false, false)` arm refuses it too, so the clause is redundant.

The result line is
"N of N mutations killed"; the exit status is 0 only when all were killed.
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

D = "third_party/onionmasq/crates/onion-tunnel/src/dns.rs"
C, S, V, G, A, F, P, Y, R, N, K, O, H, W, L, LD, RL, GD = (
    "src/control.rs",
    "src/sockdiag.rs",
    "src/server.rs",
    "src/scaffolding.rs",
    "src/args.rs",
    "src/config.rs",
    "src/policy.rs",
    "src/sockdiag/sys.rs",
    "src/routes/spec.rs",
    "src/routes/netlink.rs",
    "src/routes/reconcile.rs",
    "src/routes/proto.rs",
    "src/routes/helper.rs",
    "src/routes/sys.rs",
    "src/masklist.rs",
    "src/ladder.rs",
    "src/ratelimit.rs",
    "src/guard.rs",
)

# (name, file, old, new)
MUTATIONS = [
    # control.rs: limits, bounds, tags, the trailing check
    ("control: frame limit off by one (>=)", C, "if len > MAX_FRAME {", "if len >= MAX_FRAME {"),
    ("control: frame limit raised", C, "if len > MAX_FRAME {", "if len > MAX_FRAME + 1 {"),
    ("control: MAX_FRAME changed", C, "pub const MAX_FRAME: usize = 4096;", "pub const MAX_FRAME: usize = 2048;"),
    ("control: interface name limit (<)", C, "name.len() <= IFNAME_MAX", "name.len() < IFNAME_MAX"),
    ("control: interface name limit raised", C, "name.len() <= IFNAME_MAX", "name.len() <= IFNAME_MAX + 1"),
    ("control: IFNAME_MAX changed", C, "pub const IFNAME_MAX: usize = 15;", "pub const IFNAME_MAX: usize = 16;"),
    ("control: dot name allowed", C, "&& name != b\".\"\n", "\n"),
    ("control: memory lower bound (<=)", C, "if bytes < MEMORY_MIN {", "if bytes <= MEMORY_MIN {"),
    ("control: memory upper bound (>=)", C, "} else if bytes > MEMORY_MAX {", "} else if bytes >= MEMORY_MAX {"),
    ("control: memory lower bound value", C, "const MEMORY_MIN: u64 = 32 * 1024 * 1024;", "const MEMORY_MIN: u64 = 31 * 1024 * 1024;"),
    ("control: memory upper bound value", C, "const MEMORY_MAX: u64 = 1024 * 1024 * 1024;", "const MEMORY_MAX: u64 = 2048 * 1024 * 1024;"),
    ("control: tag hello", C, "const TAG_HELLO: u8 = 0x01;", "const TAG_HELLO: u8 = 0x11;"),
    ("control: tag set memory", C, "const TAG_SET_MEMORY_MAX: u8 = 0x02;", "const TAG_SET_MEMORY_MAX: u8 = 0x12;"),
    ("control: tag refresh app", C, "const TAG_REFRESH_APP: u8 = 0x03;", "const TAG_REFRESH_APP: u8 = 0x13;"),
    ("control: tag refresh all", C, "const TAG_REFRESH_ALL: u8 = 0x04;", "const TAG_REFRESH_ALL: u8 = 0x14;"),
    ("control: tag shutdown", C, "const TAG_SHUTDOWN: u8 = 0x05;", "const TAG_SHUTDOWN: u8 = 0x15;"),
    ("control: tag ready", C, "const TAG_READY: u8 = 0x81;", "const TAG_READY: u8 = 0x91;"),
    ("control: tag state", C, "const TAG_STATE: u8 = 0x82;", "const TAG_STATE: u8 = 0x92;"),
    ("control: tag failures", C, "const TAG_FAILURES: u8 = 0x83;", "const TAG_FAILURES: u8 = 0x93;"),
    ("control: trailing check removed", C, "if payload.len() > N {", "if false {"),
    ("control: trailing check (>=)", C, "if payload.len() > N {", "if payload.len() >= N {"),
    ("control: state failed read as up", C, "2 => DaemonState::Failed,", "2 => DaemonState::Up,"),
    ("control: state value 3 accepted", C, "2 => DaemonState::Failed,\n                _ => return Err(DecodeError::BadValue),", "2 => DaemonState::Failed,\n                _ => DaemonState::Failed,"),
    ("control: error code 0 accepted", C, "5 => ErrorCode::Failed,\n                _ => return Err(DecodeError::BadValue),", "5 => ErrorCode::Failed,\n                _ => ErrorCode::Busy,"),
    ("control: empty frame has a tag", C, "let (tag, payload) = body.split_first().ok_or(DecodeError::BadLength)?;", "let (tag, payload) = body.split_first().unwrap_or((&0x01, &[]));"),
    # control.rs, protocol 2
    ("control: tag get state", C, "const TAG_GET_STATE: u8 = 0x06;", "const TAG_GET_STATE: u8 = 0x16;"),
    ("control: tag set policy", C, "const TAG_SET_POLICY: u8 = 0x07;", "const TAG_SET_POLICY: u8 = 0x17;"),
    ("control: tag set overrides", C, "const TAG_SET_OVERRIDES: u8 = 0x08;", "const TAG_SET_OVERRIDES: u8 = 0x18;"),
    ("control: tag ack", C, "const TAG_ACK: u8 = 0x84;", "const TAG_ACK: u8 = 0x94;"),
    ("control: tag error", C, "const TAG_ERROR: u8 = 0x85;", "const TAG_ERROR: u8 = 0x95;"),
    ("control: protocol version", C, "pub const PROTOCOL_VERSION: u8 = 3;", "pub const PROTOCOL_VERSION: u8 = 2;"),
    ("control: first app uid", C, "pub const FIRST_APP_UID: u32 = 10000;", "pub const FIRST_APP_UID: u32 = 1000;"),
    ("control: class limit", C, "pub const MAX_CLASSES: usize = 255;", "pub const MAX_CLASSES: usize = 256;"),
    ("control: override limit", C, "pub const MAX_OVERRIDES: usize = 32;", "pub const MAX_OVERRIDES: usize = 33;"),
    ("control: short list accepted", C, "    if payload.len() < want {\n        return Err(DecodeError::BadLength);", "    if false {\n        return Err(DecodeError::BadLength);"),
    ("control: list trailing bytes accepted", C, "    if payload.len() > want {\n        return Err(DecodeError::Trailing);", "    if false {\n        return Err(DecodeError::Trailing);"),
    ("control: unknown class kind accepted", C, "        _ => Err(DecodeError::BadValue),\n    }\n}", "        _ => Ok(Kind::Exempt),\n    }\n}"),
    ("control: encoder emits more classes than accepted", C, "classes[..classes.len().min(MAX_CLASSES)]", "classes[..classes.len().min(MAX_CLASSES + 100)]"),
    ("control: ack carries no sequence number", C, "FromDaemon::Ack { seq } => frame(TAG_ACK, &[*seq]),", "FromDaemon::Ack { .. } => frame(TAG_ACK, &[0]),"),
    ("control: error code swapped", C, "FromDaemon::Error { seq, code } => frame(TAG_ERROR, &[*seq, *code as u8]),", "FromDaemon::Error { seq, code } => frame(TAG_ERROR, &[*code as u8, *seq]),"),
    ("control: failures company dropped", C, "let mut p = [0u8; 10];\n            p[..4].copy_from_slice(&uid.to_be_bytes());\n            p[4..6].copy_from_slice(&company.to_be_bytes());", "let mut p = [0u8; 10];\n            p[..4].copy_from_slice(&uid.to_be_bytes());\n            p[4..6].copy_from_slice(&0u16.to_be_bytes());"),
    ("control: promoted company dropped", C, "let mut p = [0u8; 7];\n            p[..4].copy_from_slice(&uid.to_be_bytes());\n            p[4..6].copy_from_slice(&company.to_be_bytes());", "let mut p = [0u8; 7];\n            p[..4].copy_from_slice(&uid.to_be_bytes());\n            p[4..6].copy_from_slice(&0u16.to_be_bytes());"),
    ("control: promoted fresh flag dropped", C, "p[6] = u8::from(*fresh);", "p[6] = 0;"),
    ("control: release company dropped", C, "p.extend_from_slice(&uid.to_be_bytes());\n            p.extend_from_slice(&company.to_be_bytes());\n            frame(TAG_RELEASE, &p)", "p.extend_from_slice(&uid.to_be_bytes());\n            p.extend_from_slice(&0u16.to_be_bytes());\n            frame(TAG_RELEASE, &p)"),
    # policy.rs
    ("policy: overlap allowed", P, "if c.start > c.end || u64::from(c.start) < next_free {", "if c.start > c.end {"),
    ("policy: start after end allowed", P, "if c.start > c.end || u64::from(c.start) < next_free {", "if u64::from(c.start) < next_free {"),
    ("policy: ranges below the first app uid allowed", P, "let mut next_free: u64 = u64::from(FIRST_APP_UID);", "let mut next_free: u64 = 0;"),
    ("policy: too many classes by one", P, "if classes.len() > MAX_CLASSES {", "if classes.len() > MAX_CLASSES + 1 {"),
    ("policy: too many classes at the limit", P, "if classes.len() > MAX_CLASSES {", "if classes.len() >= MAX_CLASSES {"),
    ("policy: adjacent ranges refused", P, "next_free = u64::from(c.end) + 1;", "next_free = u64::from(c.end) + 2;"),
    ("policy: overlap by one allowed", P, "next_free = u64::from(c.end) + 1;", "next_free = u64::from(c.end);"),
    ("policy: end of a range exclusive", P, "(uid <= c.end).then_some(c.kind)", "(uid < c.end).then_some(c.kind)"),
    ("policy: a gap has the class of the range before", P, "(uid <= c.end).then_some(c.kind)", "Some(c.kind)"),
    ("policy: start of a range exclusive", P, "partition_point(|c| c.start <= uid)", "partition_point(|c| c.start < uid)"),
    ("policy: override for a system uid", P, "        if uid < FIRST_APP_UID {\n            return Err(ErrorCode::BadValue);\n        }\n        if companies.is_empty()", "        if companies.is_empty()"),
    ("policy: empty override list is stored", P, "if companies.is_empty() {\n            self.overrides.remove(&uid);", "if false {\n            self.overrides.remove(&uid);"),
    ("policy: override uid table unbounded", P, "self.overrides.len() >= MAX_OVERRIDE_UIDS", "self.overrides.len() > MAX_OVERRIDE_UIDS"),
    ("policy: override list bound", P, "if companies.len() > MAX_OVERRIDES {", "if companies.len() >= MAX_OVERRIDES {"),
    ("policy: override ignores the company", P, "self.overrides.get(&uid).is_some_and(|v| v.contains(&company))", "self.overrides.contains_key(&uid)"),
    # Task 4: the peer check and the socket from init
    ("server: an empty wanted type matches", V, "if t == want && !want.is_empty())", "if t == want)"),
    ("server: any role accepted", V, "(Some(\"u\"), Some(\"r\"), Some(t))", "(Some(\"u\"), Some(_), Some(t))"),
    ("server: any user accepted", V, "(Some(\"u\"), Some(\"r\"), Some(t))", "(Some(_), Some(\"r\"), Some(t))"),
    ("server: a longer type accepted", V, "if t == want && !want.is_empty())", "if t.starts_with(want) && !want.is_empty())"),
    ("server: uid mismatch accepted", V, "Ok(uid) if uid == want => Ok(()),", "Ok(uid) if uid != want => Ok(()),"),
    ("server: unreadable uid accepted", V, "Err(_) => Err(denied(\"control peer uid unknown\")),", "Err(_) => Ok(()),"),
    ("server: unreadable context accepted", V, "Err(_) => Err(denied(\"control peer context unknown\")),", "Err(_) => Ok(()),"),
    ("server: context mismatch accepted", V, "Ok(ctx) if context_has_type(&ctx, want) => Ok(()),", "Ok(ctx) if !context_has_type(&ctx, want) => Ok(()),"),
    ("server: context check skipped", V, "Auth::Context(want) => check_context(socket_peer_context(stream.as_fd()), want),", "Auth::Context(_) => Ok(()),"),
    ("server: control variable not digits only", V, "if value.is_empty() || !value.bytes().all(|b| b.is_ascii_digit()) {", "if value.is_empty() {"),
    ("server: the peer is not checked", V, "    check(&stream)?;\n    stream.set_write_timeout(Some(WRITE_TIMEOUT))?;", "    stream.set_write_timeout(Some(WRITE_TIMEOUT))?;"),
    ("sys: descriptor below 3 allowed", Y, "if raw < 3 {", "if raw < 0 {"),
    ("sys: a socket that does not listen accepted", Y, "if get(libc::SO_ACCEPTCONN)? != 1 {", "if false {"),
    ("sys: a socket of another family accepted", Y, "if get(libc::SO_DOMAIN)? != libc::AF_UNIX {", "if false {"),
    ("sys: the context keeps its NUL", Y, "let got = got.strip_suffix(&[0]).unwrap_or(got);", "let got = got;"),
    ("sys: the context length is not bounded by the buffer", Y, "        .get(..len)\n", "        .get(..len.min(buf.len()))\n"),
    ("args: control and fd variable both allowed", A, "(Some(_), Some(_)) => return Err(ArgError::Duplicate(CONTROL)),", "(Some(path), Some(_)) => ControlSource::Path(path),"),
    ("args: uid and context both allowed", A, "(Some(_), Some(_)) => return Err(ArgError::Duplicate(PEER_UID)),", "(Some(uid), Some(_)) => Peer::Uid(uid),"),
    ("args: socket variable prefix not checked", A, "v.strip_prefix(\"ANDROID_SOCKET_\")", "v.strip_prefix(\"\")"),
    ("args: socket variable length", A, "&& v.len() <= 64 &&", "&& v.len() <= 65 &&"),
    ("args: selinux type may start with a digit", A, "b.next().is_some_and(|c| c.is_ascii_lowercase())", "b.next().is_some()"),
    ("args: selinux type length", A, "v.len() <= 64 && b.next()", "v.len() <= 65 && b.next()"),
    ("args: selinux type allows a dash", A, "c.is_ascii_lowercase() || c.is_ascii_digit() || c == b'_')", "c.is_ascii_lowercase() || c.is_ascii_digit() || c == b'_' || c == b'-')"),
    # Task 5: the closed rule language of the route helper
    ("spec: band start", R, "pub const BAND_FIRST: u32 = 13100;", "pub const BAND_FIRST: u32 = 13000;"),
    ("spec: band end", R, "pub const BAND_LAST: u32 = 13999;", "pub const BAND_LAST: u32 = 14999;"),
    ("spec: table", R, "pub const TABLE: u32 = 4747;", "pub const TABLE: u32 = 4748;"),
    ("spec: dns udp priority", R, "pub const PRIO_DNS_UDP: u32 = 13500;", "pub const PRIO_DNS_UDP: u32 = 15500;"),
    ("spec: dns tcp priority", R, "pub const PRIO_DNS_TCP: u32 = 13501;", "pub const PRIO_DNS_TCP: u32 = 13500;"),
    ("spec: fake priority", R, "pub const PRIO_FAKE: u32 = 13510;", "pub const PRIO_FAKE: u32 = 13511;"),
    ("spec: guard zero priority", R, "pub const PRIO_GUARD_ZERO: u32 = 13520;", "pub const PRIO_GUARD_ZERO: u32 = 13530;"),
    ("spec: guard oem priority", R, "pub const PRIO_GUARD_OEM: u32 = 13521;", "pub const PRIO_GUARD_OEM: u32 = 13531;"),
    ("spec: guard deny priority", R, "pub const PRIO_GUARD_DENY: u32 = 13522;", "pub const PRIO_GUARD_DENY: u32 = 13521;"),
    ("spec: guard anchor priority", R, "pub const PRIO_GUARD_ANCHOR: u32 = 13523;", "pub const PRIO_GUARD_ANCHOR: u32 = 13524;"),
    ("spec: fake v4 prefix", R, "pub const FAKE_V4: (Ipv4Addr, u8) = (Ipv4Addr::new(198, 18, 0, 0), 15);", "pub const FAKE_V4: (Ipv4Addr, u8) = (Ipv4Addr::new(198, 18, 0, 0), 8);"),
    ("spec: fake v4 address", R, "pub const FAKE_V4: (Ipv4Addr, u8) = (Ipv4Addr::new(198, 18, 0, 0), 15);", "pub const FAKE_V4: (Ipv4Addr, u8) = (Ipv4Addr::new(10, 0, 0, 0), 15);"),
    ("spec: fake v6 prefix", R, "0x8000, 0, 0, 0, 0), 49);", "0x8000, 0, 0, 0, 0), 16);"),
    ("spec: first app uid", R, "pub const FIRST_APP_UID: u32 = 10000;", "pub const FIRST_APP_UID: u32 = 1000;"),
    ("spec: last uid", R, "pub const LAST_UID: u32 = u32::MAX - 1;", "pub const LAST_UID: u32 = u32::MAX;"),
    ("spec: range limit", R, "pub const MAX_RANGES: usize = 128;", "pub const MAX_RANGES: usize = 129;"),
    ("spec: netid mask", R, "pub const NETID_MASK: u32 = 0xffff;", "pub const NETID_MASK: u32 = 0x1ffff;"),
    ("spec: dns port", R, "pub const DNS_PORT: u16 = 53;", "pub const DNS_PORT: u16 = 5353;"),
    ("spec: udp number", R, "pub const IPPROTO_UDP: u8 = 17;", "pub const IPPROTO_UDP: u8 = 6;"),
    ("spec: tun address", R, "pub const TUN_V4: (Ipv4Addr, u8) = (Ipv4Addr::new(169, 254, 42, 127), 24);", "pub const TUN_V4: (Ipv4Addr, u8) = (Ipv4Addr::new(169, 254, 42, 127), 16);"),
    ("spec: interface length", R, "name.len() <= 15 &&", "name.len() <= 16 &&"),
    ("spec: interface alphabet", R, "matches!(b, b'a'..=b'z' | b'0'..=b'9' | b'_')", "matches!(b, b'a'..=b'z' | b'0'..=b'9' | b'_' | b'-')"),
    ("spec: interface may be empty", R, "!name.is_empty() && name.len() <= 15", "name.len() <= 15"),
    ("spec: range below the first app uid", R, "if r.start < FIRST_APP_UID || r.start > r.end || r.end > LAST_UID {", "if r.start > r.end || r.end > LAST_UID {"),
    ("spec: range with start after end", R, "if r.start < FIRST_APP_UID || r.start > r.end || r.end > LAST_UID {", "if r.start < FIRST_APP_UID || r.end > LAST_UID {"),
    ("spec: range past the last uid", R, "if r.start < FIRST_APP_UID || r.start > r.end || r.end > LAST_UID {", "if r.start < FIRST_APP_UID || r.start > r.end {"),
    ("spec: overlap by one allowed", R, "if u64::from(r.start) < next_free {", "if u64::from(r.start) + 1 < next_free {"),
    ("spec: adjacent refused", R, "if u64::from(r.start) < next_free {", "if u64::from(r.start) <= next_free {"),
    ("spec: end not remembered", R, "next_free = u64::from(r.end) + 1;", "next_free = u64::from(r.start) + 1;"),
    ("spec: too many ranges at the limit", R, "if list.len() > MAX_RANGES {", "if list.len() >= MAX_RANGES {"),
    ("spec: too many ranges unbounded", R, "if list.len() > MAX_RANGES {", "if list.len() > MAX_RANGES + 1 {"),
    ("spec: lists may share a uid", R, "if !disjoint(&spec.steer, &spec.tor_guard) {", "if false {"),
    ("spec: disjoint test too loose", R, "x.end < y.start || y.end < x.start", "x.end <= y.start || y.end <= x.start"),
    ("spec: disjoint test too strict", R, "x.end < y.start || y.end < x.start", "x.end < y.start && y.end < x.start"),
    ("spec: network id zero allowed", R, "if id == 0 || id > NETID_MASK {", "if id > NETID_MASK {"),
    ("spec: network id above the mask allowed", R, "if id == 0 || id > NETID_MASK {", "if id == 0 {"),
    ("spec: network id at the mask refused", R, "if id == 0 || id > NETID_MASK {", "if id == 0 || id >= NETID_MASK {"),
    ("spec: anchor always added", R, "if !spec.tor_guard.is_empty() {\n        for f in families {\n            ops.push", "if true {\n        for f in families {\n            ops.push"),
    ("spec: dns rules without the port", R, "                    dport: Some(DNS_PORT),\n", "                    dport: None,\n"),
    ("spec: dns tcp rule looks up another table", R, "                    action: Action::Lookup(TABLE),\n                    ..rule(prio, f)", "                    action: Action::Lookup(TABLE + 1),\n                    ..rule(prio, f)"),
    ("spec: steer rules for v4 only", R, "        for f in families {\n            for (prio, proto)", "        for f in [Family::V4] {\n            for (prio, proto)"),
    ("spec: guard zero mark value", R, "fwmark: Some((0, NETID_MASK)),", "fwmark: Some((1, NETID_MASK)),"),
    ("spec: guard zero mark mask", R, "fwmark: Some((0, NETID_MASK)),", "fwmark: Some((0, 0)),"),
    ("spec: guard oem mark value", R, "fwmark: Some((id, NETID_MASK)),", "fwmark: Some((0, NETID_MASK)),"),
    ("spec: guard goto target", R, "action: Action::Goto(PRIO_GUARD_ANCHOR),\n                ..rule(PRIO_GUARD_ZERO, f)", "action: Action::Goto(PRIO_GUARD_DENY),\n                ..rule(PRIO_GUARD_ZERO, f)"),
    ("spec: guard deny does not prohibit", R, "action: Action::Prohibit, ..rule(PRIO_GUARD_DENY, f)", "action: Action::Nop, ..rule(PRIO_GUARD_DENY, f)"),
    ("spec: guard oem rule always added", R, "            if let Some(id) = spec.oem_netid {\n                rules.push(Rule {", "            if let Some(id) = Some(spec.oem_netid.unwrap_or(1)) {\n                rules.push(Rule {"),
    ("spec: order by family first", R, "(r.priority, r.uids.map(|u| u.start), r.family == Family::V6)", "(r.family == Family::V6, r.priority, r.uids.map(|u| u.start))"),
    # Task 6: netlink encoding, the stand-in and the real kernel, reconcile, the helper protocol and the helper
    ("netlink: padding not counted", N, "at = at.checked_add(align4(len)).ok_or(NlError::BadLength)?;", "at = at.checked_add(len).ok_or(NlError::BadLength)?;"),
    ("netlink: attribute padding not added", N, "self.buf.resize(align4(self.buf.len()), 0);", "// no padding"),
    ("netlink: align rounds down", N, "(n + 3) & !3", "n & !3"),
    ("netlink: length of the message not filled in", N, "self.buf[..4].copy_from_slice(&len.to_ne_bytes());", "let _ = len;"),
    ("netlink: error code not negated", N, "Ok(e.checked_neg().unwrap_or(i32::MAX))", "Ok(e)"),
    ("netlink: source prefix ignored", N, "if hdr[2] != 0 || hdr[3] != 0 || flags != 0 {", "if hdr[3] != 0 || flags != 0 {"),
    ("netlink: tos ignored", N, "if hdr[2] != 0 || hdr[3] != 0 || flags != 0 {", "if hdr[2] != 0 || flags != 0 {"),
    ("netlink: header flags ignored", N, "if hdr[2] != 0 || hdr[3] != 0 || flags != 0 {", "if hdr[2] != 0 || hdr[3] != 0 {"),
    ("netlink: unknown attribute ignored", N, "            _ => return Ok(None),\n        }\n    }\n    let Some(priority)", "            _ => {}\n        }\n    }\n    let Some(priority)"),
    ("netlink: nonzero protocol attribute ignored", N, "FRA_PROTOCOL if p == [0] => {}", "FRA_PROTOCOL => {}"),
    ("netlink: suppression ignored", N, "FRA_SUPPRESS_PREFIXLEN | FRA_SUPPRESS_IFGROUP if p == u32::MAX.to_ne_bytes() => {}", "FRA_SUPPRESS_PREFIXLEN | FRA_SUPPRESS_IFGROUP => {}"),
    ("netlink: a port range is any range", N, "                if lo != hi {\n                    return Ok(None);\n                }", "                let _ = hi;"),
    ("netlink: a goto without a target is described", N, "            Some(p) => Action::Goto(p),\n            None => return Ok(None),", "            Some(p) => Action::Goto(p),\n            None => Action::Goto(0),"),
    ("netlink: table taken from the header only", N, "FRA_TABLE => table = u32::from_ne_bytes(fixed::<4>(p)?),", "FRA_TABLE => {}"),
    ("netlink: mark mask dropped", N, "(v, m) => Some((v.unwrap_or(0), m.unwrap_or(0))),", "(v, _) => Some((v.unwrap_or(0), 0)),"),
    ("netlink: mark value dropped", N, "(v, m) => Some((v.unwrap_or(0), m.unwrap_or(0))),", "(_, m) => Some((0, m.unwrap_or(0))),"),
    ("netlink: destination length lost", N, "(Family::V4, Some(b)) => Some(Dst::V4(Ipv4Addr::from(fixed::<4>(b)?), dst_len)),", "(Family::V4, Some(b)) => Some(Dst::V4(Ipv4Addr::from(fixed::<4>(b)?), 32)),"),
    ("netlink: families confused", N, "AF_INET => Family::V4,\n        AF_INET6 => Family::V6,\n        _ => return Ok(None),\n    };\n    let (dst_len", "AF_INET => Family::V6,\n        AF_INET6 => Family::V4,\n        _ => return Ok(None),\n    };\n    let (dst_len"),
    ("netlink: delete uses the add flags", N, "Verb::Del => (RTM_DELRULE, NLM_F_REQUEST | NLM_F_ACK),", "Verb::Del => (RTM_DELRULE, NLM_F_REQUEST | NLM_F_ACK | NLM_F_CREATE),"),
    ("netlink: the port is written once", N, "b[2..].copy_from_slice(&port.to_ne_bytes());", "b[2..].copy_from_slice(&0u16.to_ne_bytes());"),
    ("netlink: the uid range end is the start", N, "b[4..].copy_from_slice(&u.end.to_ne_bytes());", "b[4..].copy_from_slice(&u.start.to_ne_bytes());"),
    ("netlink: v4 route has universe scope", N, "        Family::V4 => RT_SCOPE_LINK,\n        Family::V6 => RT_SCOPE_UNIVERSE,\n    };\n    // struct rtmsg: family, dst_len, src_len, tos, table, protocol, scope, type, flags\n    m.raw(&[family_byte(family), 0, 0, 0, 0, RTPROT_STATIC, scope, RTN_UNICAST]);\n    m.raw(&0u32.to_ne_bytes());\n    m.attr_u32(RTA_TABLE, table);\n    m.attr_u32(RTA_OIF, ifindex);", "        Family::V4 => RT_SCOPE_UNIVERSE,\n        Family::V6 => RT_SCOPE_UNIVERSE,\n    };\n    // struct rtmsg: family, dst_len, src_len, tos, table, protocol, scope, type, flags\n    m.raw(&[family_byte(family), 0, 0, 0, 0, RTPROT_STATIC, scope, RTN_UNICAST]);\n    m.raw(&0u32.to_ne_bytes());\n    m.attr_u32(RTA_TABLE, table);\n    m.attr_u32(RTA_OIF, ifindex);"),
    ("netlink: the address is written once", N, "    m.attr(IFA_LOCAL, octets);\n    m.attr(IFA_ADDRESS, octets);", "    m.attr(IFA_LOCAL, octets);"),
    ("netlink: the link name has no terminator", N, "    n.push(0);\n    m.attr(IFLA_IFNAME, &n);", "    m.attr(IFLA_IFNAME, &n);"),
    ("netlink: link up without the change mask", N, "    m.raw(&IFF_UP.to_ne_bytes());\n    m.raw(&IFF_UP.to_ne_bytes());\n    let mut n", "    m.raw(&IFF_UP.to_ne_bytes());\n    m.raw(&0u32.to_ne_bytes());\n    let mut n"),
    ("reconcile: removal before addition", K, "    for r in &missing {\n        ack(k, &nl::rule_msg(Verb::Add, r, seq.next()), &[EEXIST])?;\n        out.added += 1;\n    }\n    for r in &stale {\n        ack(k, &nl::rule_msg(Verb::Del, r, seq.next()), &[ENOENT])?;\n        out.removed += 1;\n    }", "    for r in &stale {\n        ack(k, &nl::rule_msg(Verb::Del, r, seq.next()), &[ENOENT])?;\n        out.removed += 1;\n    }\n    for r in &missing {\n        ack(k, &nl::rule_msg(Verb::Add, r, seq.next()), &[EEXIST])?;\n        out.added += 1;\n    }"),
    ("reconcile: everything is rewritten", K, "            Some(i) => {\n                unmatched.swap_remove(i);\n            }\n            None => missing.push(*d),", "            Some(i) => {\n                unmatched.swap_remove(i);\n                missing.push(*d);\n            }\n            None => missing.push(*d),"),
    ("reconcile: stale rules are kept", K, "    let stale = unmatched;", "    let stale: Vec<Rule> = Vec::new();"),
    ("reconcile: unreadable rules are kept", K, "    for (family, prio) in &band.foreign {\n        ack(k, &nl::rule_del_priority_msg(*family, *prio, seq.next()), &[ENOENT])?;\n        out.removed += 1;\n    }\n    Ok(out)\n}\n\n/// Removes every rule in the band and", "    Ok(out)\n}\n\n/// Removes every rule in the band and"),
    ("reconcile: the band starts one early", K, "(BAND_FIRST..=BAND_LAST).contains(&priority)", "(BAND_FIRST - 1..=BAND_LAST).contains(&priority)"),
    ("reconcile: the band ends one late", K, "(BAND_FIRST..=BAND_LAST).contains(&priority)", "(BAND_FIRST..=BAND_LAST + 1).contains(&priority)"),
    ("reconcile: the band is open at the end", K, "(BAND_FIRST..=BAND_LAST).contains(&priority)", "(BAND_FIRST..BAND_LAST).contains(&priority)"),
    ("reconcile: a rule outside the band in a plan is written", K, "    if desired.iter().any(|r| !in_band(r.priority)) {", "    if false {"),
    ("reconcile: existing rule is an error", K, "ack(k, &nl::rule_msg(Verb::Add, r, seq.next()), &[EEXIST])?;", "ack(k, &nl::rule_msg(Verb::Add, r, seq.next()), &[])?;"),
    ("reconcile: a missing rule is an error", K, "ack(k, &nl::rule_msg(Verb::Del, r, seq.next()), &[ENOENT])?;\n        out.removed += 1;\n    }\n    for (family, prio) in &band.foreign {\n        ack(k, &nl::rule_del_priority_msg(*family, *prio, seq.next()), &[ENOENT])?;\n        out.removed += 1;\n    }\n    for family in", "ack(k, &nl::rule_msg(Verb::Del, r, seq.next()), &[])?;\n        out.removed += 1;\n    }\n    for (family, prio) in &band.foreign {\n        ack(k, &nl::rule_del_priority_msg(*family, *prio, seq.next()), &[ENOENT])?;\n        out.removed += 1;\n    }\n    for family in"),
    ("reconcile: an existing address is an error", K, "ack(k, &msg, &[EEXIST])?;", "ack(k, &msg, &[])?;"),
    ("reconcile: the acknowledgement is not required", K, "        .ok_or_else(|| bad_reply(\"no acknowledgement in the netlink reply\"))?\n        .map_err(", "        .unwrap_or(Ok(0))\n        .map_err("),
    ("reconcile: any errno is fine", K, "    if code == 0 || tolerated.contains(&code) {", "    if true {"),
    ("reconcile: a dump without an end is accepted", K, "        if !done {\n            return Err(bad_reply(\"the rule dump did not end\"));\n        }", "        let _ = done;"),
    ("reconcile: only the first family is read", K, "    for family in [Family::V4, Family::V6] {\n        let reply = k.request(&nl::dump_rules_msg", "    for family in [Family::V4] {\n        let reply = k.request(&nl::dump_rules_msg"),
    ("reconcile: clear leaves the routes", K, "    for family in [Family::V4, Family::V6] {\n        ack(k, &nl::route_default_del_msg(family, TABLE, seq.next()), &[ESRCH, ENOENT])?;\n    }", ""),
    ("reconcile: clear fails when there is no route", K, "&[ESRCH, ENOENT])?;", "&[])?;"),
    ("reconcile: the rule count is the added ones", K, "let mut out = Applied { rules: desired.len(), ..Applied::default() };", "let mut out = Applied { rules: 0, ..Applied::default() };"),
    ("proto: the network id is not sent", O, "p.extend_from_slice(&spec.oem_netid.unwrap_or(0).to_be_bytes());", "p.extend_from_slice(&0u32.to_be_bytes());"),
    ("proto: zero is a network id", O, "oem_netid: (id != 0).then_some(id)", "oem_netid: Some(id)"),
    ("proto: trailing bytes after an apply", O, "            if !rest.is_empty() {\n                return Err(DecodeError::Trailing);\n            }", "            let _ = rest;"),
    ("proto: the name length is not checked", O, "let name = rest.get(..usize::from(*name_len)).ok_or(DecodeError::BadLength)?;", "let name = rest.get(..usize::from(*name_len).min(rest.len())).ok_or(DecodeError::BadLength)?;"),
    ("proto: a range count that is too large is read", O, "let body = rest.get(..want).ok_or(DecodeError::BadLength)?;", "let body = rest.get(..want.min(rest.len())).ok_or(DecodeError::BadLength)?;"),
    ("proto: the encoder is unbounded", O, "let list = &list[..list.len().min(MAX_RANGES)];", "let list = &list[..list.len().min(MAX_RANGES + 1000)];"),
    ("proto: an error code of 5 is refused", O, "                5 => ErrorCode::Failed,\n", ""),
    ("proto: applied count is the low byte", O, "RouteReply::Applied { seq: p[0], rules: u16::from_be_bytes([p[1], p[2]]) }", "RouteReply::Applied { seq: p[0], rules: u16::from(p[2]) }"),
    ("helper: wrong version accepted", H, "RouteMsg::Hello { version } if version == ROUTE_PROTOCOL_VERSION => {", "RouteMsg::Hello { version } if version != ROUTE_PROTOCOL_VERSION => {"),
    ("helper: a wrong version keeps the connection", H, "RouteMsg::Hello { .. } => {\n                HelperReply { msgs: vec![RouteReply::Error { seq: 0, code: ErrorCode::Unsupported }], close: true }", "RouteMsg::Hello { .. } => {\n                HelperReply { msgs: vec![RouteReply::Error { seq: 0, code: ErrorCode::Unsupported }], close: false }"),
    ("helper: a refused specification is applied anyway", H, "Err(e) => return HelperReply::one(RouteReply::Error { seq, code: spec_error(e) }),", "Err(_) => Vec::new(),"),
    ("helper: too many ranges is a bad value", H, "SpecError::TooManyRanges => ErrorCode::TooMany,", "SpecError::TooManyRanges => ErrorCode::BadValue,"),
    ("helper: a kernel refusal is acknowledged", H, "Err(_) => HelperReply::one(RouteReply::Error { seq, code: ErrorCode::Failed }),\n                }\n            }\n            RouteMsg::Clear", "Err(_) => HelperReply::one(RouteReply::Ack { seq }),\n                }\n            }\n            RouteMsg::Clear"),
    ("helper: the specification is remembered before it is applied", H, "                    Ok(_) => {\n                        self.last = Some(spec);\n                        HelperReply::one(RouteReply::Ack { seq })\n                    }", "                    Ok(_) => HelperReply::one(RouteReply::Ack { seq }),"),
    ("helper: clear keeps the specification", H, "                Ok(_) => {\n                    self.last = None;", "                Ok(_) => {"),
    ("helper: the applied count is the known rules only", H, "let n = band.known.len() + band.foreign.len();", "let n = band.known.len();"),
    ("helper: the applied count wraps", H, "u16::try_from(n).unwrap_or(u16::MAX)", "n as u16"),
    ("helper: the peer is not checked", H, "serve_framed(stream, |s| check_peer(s, auth), decode_msg, encode_reply, |m| {", "serve_framed(stream, |_| Ok(()), decode_msg, encode_reply, |m| {"),
    ("sys: the dump end is not looked for", W, "(dump && m.kind == NLMSG_DONE)", "false"),
    ("sys: replies of other requests are kept", W, "let ours: Vec<_> = msgs.iter().filter(|m| m.seq == seq).collect();", "let ours: Vec<_> = msgs.iter().collect();"),
    ("sys: the reply size is not bounded", W, "if all.len() > REPLY_MAX {", "if false {"),
    ("sys: the ack is not looked for", W, "m.kind == NLMSG_ERROR || (dump", "m.kind == NLMSG_DONE || (dump"),
    # Tasks 7 to 11: the mask list, the DNS split, the admission of flows, the ladder, the limits
    ("masklist: a protected row masks", L, "return (id != 0).then_some(id);", "return Some(id);"),
    ("masklist: only the exact name is looked up", L, "rest = &rest[rest.find('.')? + 1..];", "rest = &rest[rest.len()..];"),
    ("masklist: the trailing dot is kept", L, "let n = name.strip_suffix('.').unwrap_or(name);", "let n = name;"),
    ("masklist: case is not folded", L, "let lower = n.to_ascii_lowercase();", "let lower = n.to_string();"),
    ("masklist: a label of 64 bytes", L, "&& l.len() <= 63", "&& l.len() <= 64"),
    ("masklist: a label may start with a hyphen", L, "&& !l.starts_with('-')", ""),
    ("masklist: a label may end with a hyphen", L, "&& !l.ends_with('-')", ""),
    ("masklist: a domain over 253 bytes", L, "d.len() <= MAX_DOMAIN", "d.len() <= MAX_DOMAIN + 10"),
    ("masklist: the header is not checked", L, "if lines.next() != Some(HEADER) {", "if false {"),
    ("masklist: a duplicate row is taken", L, "if rows.insert(domain.to_owned(), id).is_some() {", "if false {"),
    ("masklist: an empty name is taken", L, "if name.is_empty() || name.len() > MAX_NAME", "if name.len() > MAX_NAME"),
    ("masklist: a long name is taken", L, "if name.is_empty() || name.len() > MAX_NAME", "if name.is_empty()"),
    ("masklist: a control character in a name is taken", L, "|| name.chars().any(|c| c.is_control())", ""),
    ("masklist: the entry limit is one more", L, "if rows.len() >= MAX_ENTRIES {", "if rows.len() > MAX_ENTRIES {"),
    ("masklist: an id with a sign is taken", L, "if id.is_empty() || !id.bytes().all(|b| b.is_ascii_digit()) {", "if id.is_empty() {"),
    ("masklist: a row with a fourth field is taken", L, "let (Some(domain), Some(id), Some(name), None) = (f.next(), f.next(), f.next(), f.next()) else {", "let (Some(domain), Some(id), Some(name), _) = (f.next(), f.next(), f.next(), f.next()) else {"),
    ("ladder: the threshold is four", LD, "e.failures >= FAILURE_THRESHOLD || e.slow", "e.failures > FAILURE_THRESHOLD || e.slow"),
    ("ladder: a slow connection is not enough", LD, "e.failures >= FAILURE_THRESHOLD || e.slow", "e.failures >= FAILURE_THRESHOLD"),
    ("ladder: failures and slowness must both happen", LD, "e.failures >= FAILURE_THRESHOLD || e.slow", "e.failures >= FAILURE_THRESHOLD && e.slow"),
    ("ladder: reported more than once", LD, ".filter(|(_, e)| !e.reported && (", ".filter(|(_, e)| (true) && ("),
    ("ladder: the window is over one tick early", LD, "now.saturating_duration_since(e.first) > WINDOW);\n        if stale", "now.saturating_duration_since(e.first) >= WINDOW);\n        if stale"),
    ("ladder: expired keys are kept", LD, "self.map.retain(|_, e| now.saturating_duration_since(e.first) <= WINDOW);", "self.map.retain(|_, _| true);"),
    ("ladder: the count of a slow connection is zero", LD, "count: e.failures.max(1) })", "count: e.failures })"),
    ("ladder: the table is one too big", LD, "self.map.len() >= MAX_KEYS {", "self.map.len() > MAX_KEYS {"),
    ("ladder: the newest key is evicted", LD, ".min_by_key(|(_, e)| e.first)", ".max_by_key(|(_, e)| e.first)"),
    ("ladder: all due keys are returned", LD, "        keys.truncate(max);\n", ""),
    ("ladder: a v6 host keeps its brackets", LD, "return rest.split(']').next().unwrap_or(\"\");", "return addr;"),
    ("ladder: a port is not stripped", LD, "Some((host, port)) if !host.contains(':') && port.bytes().all(|b| b.is_ascii_digit()) => host,", "Some((_host, _port)) => addr,"),
    ("ladder: a v6 address loses its last group", LD, "Some((host, port)) if !host.contains(':') && port.bytes().all(|b| b.is_ascii_digit()) => host,", "Some((host, _port)) => host,"),
    ("guard: the threshold is five mebibytes", GD, 'pub const HEAVY_BYTES: u64 = 4 * 1024 * 1024;', 'pub const HEAVY_BYTES: u64 = 5 * 1024 * 1024;'),
    ("guard: the threshold is one byte more", GD, 'delta >= HEAVY_BYTES', 'delta > HEAVY_BYTES'),
    ("guard: the window is two minutes", GD, 'pub const WINDOW: Duration = Duration::from_secs(60);', 'pub const WINDOW: Duration = Duration::from_secs(120);'),
    ("guard: the hold is shorter", GD, 'pub const HOLD: Duration = Duration::from_secs(30 * 60);', 'pub const HOLD: Duration = Duration::from_secs(29 * 60);'),
    ("guard: the hold lasts one tick more", GD, '.is_some_and(|until| now < until)', '.is_some_and(|until| now <= until)'),
    ("guard: a pinned pair is promoted", GD, '&& !pinned.contains(key) &&', '&&'),
    ("guard: a release does not pin", GD, 'self.pinned.insert((uid, company));', 'let _ = (uid, company);'),
    ("guard: a release does not end the promotion", GD, 'if let Some(p) = self.pairs.get_mut(&(uid, company)) {\n            p.until = None;\n            p.fresh = false;\n            p.history.clear();\n        }', 'if let Some(p) = self.pairs.get_mut(&(uid, company)) {\n            p.fresh = false;\n        }'),
    ("guard: the pin table is one too big", GD, 'if self.pinned.len() < MAX_PAIRS {', 'if self.pinned.len() <= MAX_PAIRS {'),
    ("guard: the pair table is one too big", GD, 'if self.pairs.len() >= MAX_PAIRS {', 'if self.pairs.len() > MAX_PAIRS {'),
    ("guard: the pair table is not bounded", GD, 'if self.pairs.len() >= MAX_PAIRS {', 'if false {'),
    ("guard: the newest pair is evicted", GD, '.min_by_key(|(_, p)| p.created)\n                .or_else(|| self.pairs.iter().filter', '.max_by_key(|(_, p)| p.created)\n                .or_else(|| self.pairs.iter().filter'),
    ("guard: a pair with a live flow is evicted first", GD, 'p.until.is_none() && Arc::strong_count(&p.counter) == 1)', 'p.until.is_none() && Arc::strong_count(&p.counter) >= 1)'),
    ("guard: a promoted pair is evicted first", GD, '.filter(|(_, p)| p.until.is_none() && Arc::strong_count', '.filter(|(_, p)| p.until.is_some() && Arc::strong_count'),
    ("guard: a pair with a live flow is forgotten", GD, '|| !full || Arc::strong_count(&p.counter) > 1', '|| !full'),
    ("guard: a silent pair is kept for ever", GD, 'p.until.is_some() || delta > 0 || !full ||', 'true ||'),
    ("guard: a fresh promotion is never fresh", GD, 'p.until = Some(now + HOLD);\n                p.fresh = true;', 'p.until = Some(now + HOLD);\n                p.fresh = false;'),
    ("guard: a promotion stays fresh", GD, 'out.push(Promotion { uid, company, fresh: p.fresh });\n                p.fresh = false;', 'out.push(Promotion { uid, company, fresh: p.fresh });'),
    ("guard: a report is not bounded", GD, '        keys.truncate(max);\n', ''),
    ("guard: an ended promotion is still listed", GD, '.filter(|(_, p)| p.until.is_some_and(|u| now < u))', '.filter(|(_, p)| p.until.is_some())'),
    ("control: the release tag moves", C, 'const TAG_RELEASE: u8 = 0x09;', 'const TAG_RELEASE: u8 = 0x0a;'),
    ("control: the promoted tag moves", C, 'const TAG_PROMOTED: u8 = 0x86;', 'const TAG_PROMOTED: u8 = 0x87;'),
    ("control: a fresh flag of two is fresh", C, '                1 => true,\n                _ => return Err(DecodeError::BadValue),', '                1 | 2 => true,\n                _ => return Err(DecodeError::BadValue),'),
    ("control: a promotion loses its company", C, 'FromDaemon::Promoted { uid: be32(&p[0..4])?, company: be16(&p[4..6])?, fresh }', 'FromDaemon::Promoted { uid: be32(&p[0..4])?, company: 0, fresh }'),
    ("control: a release loses its uid", C, 'ToDaemon::Release { seq: p[0], uid: be32(&p[1..5])?, company: be16(&p[5..7])? }', 'ToDaemon::Release { seq: p[0], uid: 0, company: be16(&p[5..7])? }'),
    ("control: a release has trailing bytes", C, 'TAG_RELEASE => {\n            let p = fixed::<7>(payload)?;', 'TAG_RELEASE => {\n            let p = fixed::<8>(payload)?;'),
    ("server: a release of a system uid is accepted", V, 'if uid < FIRST_APP_UID || company == 0 {', 'if company == 0 {'),
    ("server: a release of company zero is accepted", V, 'if uid < FIRST_APP_UID || company == 0 {', 'if uid < FIRST_APP_UID {'),
    ("server: the first app uid is refused", V, 'if uid < FIRST_APP_UID || company == 0 {', 'if uid <= FIRST_APP_UID || company == 0 {'),
    ("server: a release does not reach the guard", V, 'self.scaffolding.release_promotion(uid, company);', 'let _ = (uid, company);'),
    ("server: a release is not acknowledged", V, 'self.scaffolding.release_promotion(uid, company);\n                Reply::ack(seq)', 'self.scaffolding.release_promotion(uid, company);\n                Reply::error(seq, ErrorCode::Failed)'),
    ("server: more promotions are listed", V, 'const MAX_PROMOTIONS_PER_STATE: usize = crate::guard::MAX_REPORT;', 'const MAX_PROMOTIONS_PER_STATE: usize = crate::guard::MAX_REPORT + 1;'),
    ("server: promotions are not listed", V, '.promotions(MAX_PROMOTIONS_PER_STATE)', '.promotions(0)'),
    ("server: a promotion is always fresh", V, 'FromDaemon::Promoted { uid: p.uid, company: p.company, fresh: p.fresh }', 'FromDaemon::Promoted { uid: p.uid, company: p.company, fresh: true }'),
    ("ratelimit: the second is one tick long", RL, "if now.saturating_duration_since(b.second_start) >= Duration::from_secs(1) {", "if now.saturating_duration_since(b.second_start) > Duration::from_secs(1) {"),
    ("ratelimit: the minute is one tick long", RL, "if now.saturating_duration_since(b.minute_start) >= Duration::from_secs(60) {", "if now.saturating_duration_since(b.minute_start) > Duration::from_secs(60) {"),
    ("ratelimit: only one of the limits counts", RL, "b.in_second <= PER_SECOND && b.in_minute <= PER_MINUTE", "b.in_second <= PER_SECOND || b.in_minute <= PER_MINUTE"),
    ("ratelimit: the second limit is one lower", RL, "b.in_second <= PER_SECOND &&", "b.in_second < PER_SECOND &&"),
    ("ratelimit: the minute limit is one lower", RL, "b.in_minute <= PER_MINUTE", "b.in_minute < PER_MINUTE"),
    ("ratelimit: the table is one too big", RL, "self.buckets.len() >= MAX_UIDS {", "self.buckets.len() > MAX_UIDS {"),
    ("ratelimit: the newest uid is evicted", RL, ".min_by_key(|(_, b)| b.last_seen)", ".max_by_key(|(_, b)| b.last_seen)"),
    ("ratelimit: the count wraps", RL, "b.in_second = b.in_second.saturating_add(1);", "b.in_second = b.in_second.wrapping_add(1);"),
    ("scaffolding: a stray flow of a masked app is admitted", G, "Some(Kind::Masked) if is_fake_address(&dst.addr) => Ok(()),", "Some(Kind::Masked) => Ok(()),"),
    ("scaffolding: an app of class tor is refused", G, "Some(Kind::Tor) => Ok(()),\n            Some(Kind::Masked)", "Some(Kind::Masked)"),
    ("scaffolding: an app without a class is admitted", G, "            _ => Err(io::Error::new(io::ErrorKind::PermissionDenied, \"the flow is not allowed for this app\")),", "            _ => Ok(()),"),
    ("scaffolding: tcp flows are not admitted", G, "        if proto == Proto::Tcp {\n            self.admit_tcp(uid, &dst)?;", "        if false {\n            self.admit_tcp(uid, &dst)?;"),
    ("scaffolding: udp flows are admitted like tcp", G, "        if proto == Proto::Tcp {\n            self.admit_tcp(uid, &dst)?;", "        if proto == Proto::Tcp || proto == Proto::Udp {\n            self.admit_tcp(uid, &dst)?;"),
    ("scaffolding: the flow limit is one more", G, "(m.len() >= MAX_FLOWS ||", "(m.len() > MAX_FLOWS ||"),
    ("scaffolding: a known flow counts again", G, "if !m.contains_key(&(src, dst)) && (m.len() >= MAX_FLOWS", "if (m.len() >= MAX_FLOWS"),
    ("scaffolding: the flow table is not bounded", G, "(m.len() >= MAX_FLOWS ||", "(false ||"),
    ("scaffolding: the share of one app is one more", G, "count() >= MAX_FLOWS_PER_UID)", "count() > MAX_FLOWS_PER_UID)"),
    ("scaffolding: one app has no share", G, "|| m.values().filter(|(_, u)| *u == uid).count() >= MAX_FLOWS_PER_UID)", ")"),
    ("scaffolding: the share counts the other apps", G, "filter(|(_, u)| *u == uid)", "filter(|(_, u)| *u != uid)"),
    ("scaffolding: the owner of a flow is not kept", G, "m.insert((src, dst), (now, uid));", "m.insert((src, dst), (now, 0));"),
    ("scaffolding: the start of a flow is read from the wrong field", G, ".map(|(t, _)| *t)", ".map(|(t, _)| *t + std::time::Duration::from_secs(3600))"),
    ("scaffolding: a close leaves the start", G, ".remove(&(src, dst));\n    }", ".len();\n    }"),
    ("scaffolding: slow from six seconds", G, "now.saturating_duration_since(s) >= SLOW", "now.saturating_duration_since(s) > SLOW"),
    ("scaffolding: an unlisted host has a company", G, "self.inner.list.classify(host).unwrap_or(0)", "self.inner.list.classify(host).unwrap_or(1)"),
    ("scaffolding: an app of class tor is passed on", G, "Some(Kind::Tor) => DnsDecision::Tor,", "Some(Kind::Tor) => DnsDecision::Forward,"),
    ("scaffolding: an override is ignored", G, "if !policy.overridden(uid, company)\n", "if true\n"),
    ("scaffolding: an override applies to every app", G, "if !policy.overridden(uid, company)\n", "if !policy.overridden(10100, company)\n"),
    ("scaffolding: the guard is ignored", G, "&& !self.inner.guard.lock().unwrap_or_else(PoisonError::into_inner).heavy(uid, company, Instant::now()) =>", "&& true =>"),
    ("scaffolding: the guard promotes every app", G, ".heavy(uid, company, Instant::now()) =>", ".heavy(10100, company, Instant::now()) =>"),
    ("scaffolding: a promoted company is hidden again", G, "&& !self.inner.guard.lock().unwrap_or_else(PoisonError::into_inner).heavy(uid, company, Instant::now()) =>", "&& self.inner.guard.lock().unwrap_or_else(PoisonError::into_inner).heavy(uid, company, Instant::now()) =>"),
    ("scaffolding: an app of class tor is counted", G, "== Some(Kind::Masked);\n        masked.then", "!= Some(Kind::Exempt);\n        masked.then"),
    ("scaffolding: no app is counted", G, "== Some(Kind::Masked);\n        masked.then", "== Some(Kind::Exempt);\n        masked.then"),
    ("scaffolding: every host is counted for company zero", G, "let company = self.inner.list.classify(host_of(&tor_dst.to_string()))?;", "let company = self.inner.list.classify(host_of(&tor_dst.to_string())).unwrap_or(0);"),
    ("scaffolding: a release does nothing", G, "self.inner.guard.lock().unwrap_or_else(PoisonError::into_inner).release(uid, company);", "let _ = (uid, company);"),
    ("scaffolding: the guard is not read", G, "self.inner.guard.lock().unwrap_or_else(PoisonError::into_inner).tick(now);", "let _ = now;"),
    ("scaffolding: no promotion is reported", G, ".report(Instant::now(), max)", ".report(Instant::now(), 0)"),
    ("scaffolding: an exempt app is resolved through tor", G, "Some(Kind::Exempt) | None => DnsDecision::Forward,", "Some(Kind::Exempt) | None => DnsDecision::Tor,"),
    ("scaffolding: a key that is no uid is resolved through tor", G, "let Ok(uid) = u32::try_from(isolation_key) else { return DnsDecision::Forward };", "let Ok(uid) = u32::try_from(isolation_key) else { return DnsDecision::Tor };"),
    ("scaffolding: a masked app resolves unlisted names through tor", G, "                _ => DnsDecision::Forward,\n            },", "                _ => DnsDecision::Tor,\n            },"),
    ("scaffolding: a key that is no uid is limited", G, "let Ok(uid) = u32::try_from(isolation_key) else { return true };", "let Ok(uid) = u32::try_from(isolation_key) else { return false };"),
    ("scaffolding: the limit is not applied", G, "self.inner.dns_limit.lock().unwrap_or_else(PoisonError::into_inner).allow(uid, Instant::now())", "true"),
    ("server: the ladder is not sent", V, "                msgs.extend(\n                    self.scaffolding\n                        .due_failures(MAX_FAILURES_PER_STATE)", "                msgs.extend(\n                    self.scaffolding\n                        .due_failures(0)"),
    ("server: more failures per state", V, "const MAX_FAILURES_PER_STATE: usize = 16;", "const MAX_FAILURES_PER_STATE: usize = 17;"),
    ("server: the frame deadline is the idle limit", V, "            Some(t) => frame_deadline.saturating_sub(t.elapsed()),", "            Some(_) => idle,"),
    ("server: a rest of a buffer starts no deadline", V, "frame_began = if buf.is_empty() { None } else { Some(Instant::now()) };", "frame_began = None;"),
    ("server: a first byte starts no deadline", V, "        if buf.is_empty() {\n            frame_began = Some(Instant::now());\n        }", "        if false {\n            frame_began = Some(Instant::now());\n        }"),
    ("server: the read time-out is a would-block", V, "return Err(io::Error::new(io::ErrorKind::TimedOut, \"no control frame arrived in time\"));", "return Err(io::Error::from(io::ErrorKind::WouldBlock));"),
    ("server: a stranger may replace the peer", V, "        if check_peer(&stream, auth).is_err() {\n            continue; // dropped: closed without a byte read\n        }\n", ""),
    ("server: the old connection is kept", V, "                let _ = old.shutdown(Shutdown::Both);", "                let _ = old;"),
    ("server: the shutdown hook is not called", V, "            on_shutdown();", "            let _ = &on_shutdown;"),
    ("sys: the context lookup timeout is long", Y, "const TIMEOUT_USEC: libc::suseconds_t = 250_000;", "const TIMEOUT_USEC: libc::suseconds_t = 2_500_000;"),
    ("dns: the response bit is ignored", D, "    if query.flags.qr {\n        return Route::Ignore;\n    }", "    if false {\n        return Route::Ignore;\n    }"),
    ("dns: another opcode is routed", D, "if query.flags.opcode != dns_message_parser::Opcode::Query || query.questions.is_empty() {", "if query.questions.is_empty() {"),
    ("dns: tor and forward are swapped", D, "        (true, false) => Route::Tor,\n        (false, true) => Route::Forward,", "        (true, false) => Route::Forward,\n        (false, true) => Route::Tor,"),
    ("dns: a mixed query is sent to tor", D, "        (false, true) => Route::Forward,\n        _ => Route::Refuse,", "        (false, true) => Route::Forward,\n        _ => Route::Tor,"),
    ("dns: loopback is a resolver", D, "!(a.is_loopback() || a.is_unspecified() || a.is_multicast() || a.is_broadcast() ||", "!(a.is_unspecified() || a.is_multicast() || a.is_broadcast() ||"),
    ("dns: the tunnel's own range is a resolver", D, "a.octets()[..3] == [169, 254, 42])", "false)"),
    ("dns: multicast is a resolver", D, "!(a.is_loopback() || a.is_unspecified() || a.is_multicast() || (a.segments()[0] & 0xffc0) == 0xfe80)", "!(a.is_loopback() || a.is_unspecified() || (a.segments()[0] & 0xffc0) == 0xfe80)"),
    ("dns: link-local v6 is a resolver", D, "|| (a.segments()[0] & 0xffc0) == 0xfe80)", "|| false)"),
    ("dns: a reply with another id is taken", D, "if buf[..n].get(..2) == Some(id) {", "if true {"),
    ("dns: a reply over the limit is taken", D, "if n > MAX_UDP_DNS {", "if n > MAX_UDP_DNS * 2 {"),
    ("dns: a reply at the limit is refused", D, "if n > MAX_UDP_DNS {", "if n >= MAX_UDP_DNS {"),
    ("dns: the second half of the /15 is not fake", D, "o[0] == FAKE_V4_PREFIX[0] && (o[1] & 0xfe) == FAKE_V4_PREFIX[1]", "o[0] == FAKE_V4_PREFIX[0] && o[1] == FAKE_V4_PREFIX[1]"),
    ("dns: the lower half of the v6 /48 is fake", D, "o[..6] == FAKE_V6_PREFIX && o[6] & 0x80 != 0", "o[..6] == FAKE_V6_PREFIX"),
    ("dns: the v6 fake range has no top bit", D, "            octets[6] |= 0x80;\n", ""),
    ("dns: the v4 fake range is a /16", D, "FAKE_V4_PREFIX[1] | (r[0] & 1)", "FAKE_V4_PREFIX[1]"),
    ("dns: a full range is not refused", D, "if self.map_v4.cache_size() >= self.max_v4 {\n            return None;\n        }", ""),
    ("dns: an app over its limit is admitted", D, "        if allow(key) {", "        if true {"),
    ("dns: an app with no owner is admitted", D, "        result.map_err(|_| Self::make_answer(query, RCode::ServFail, vec![]))", "        Ok(result.unwrap_or(0))"),
    # sockdiag.rs
    ("sockdiag: udp ends not swapped", S, "Proto::Udp => (remote, local, IPPROTO_UDP),", "Proto::Udp => (local, remote, IPPROTO_UDP),"),
    ("sockdiag: enoent is an error", S, "if errno == ENOENT {", "if errno != ENOENT {"),
    ("sockdiag: ack accepted", S, "if e >= 0 {", "if e > 0 {"),
    ("sockdiag: short length accepted", S, "if len < NLMSG_HEADER {", "if len < NLMSG_HEADER - 8 {"),
    ("sockdiag: states mask", S, "put(&mut r, 20, &u32::MAX.to_ne_bytes());", "put(&mut r, 20, &1u32.to_ne_bytes());"),
    ("sockdiag: cookie not NOCOOKIE", S, "put(&mut r, 64, &INET_DIAG_NOCOOKIE.to_ne_bytes());", "put(&mut r, 64, &0u32.to_ne_bytes());"),
    ("sockdiag: reply size bound", S, "if body.len() < REPLY_BODY {", "if body.len() <= REPLY_BODY {"),
    ("sockdiag: uid offset", S, "Ok(Reply::Uid(ne_u32(body, 64)?))", "Ok(Reply::Uid(ne_u32(body, 60)?))"),
    ("sockdiag: family", S, "(SocketAddr::V4(_), SocketAddr::V4(_)) => AF_INET,", "(SocketAddr::V4(_), SocketAddr::V4(_)) => AF_INET6,"),
    ("sockdiag: ports swapped", S, "put(&mut r, 24, &src.port().to_be_bytes());", "put(&mut r, 24, &dst.port().to_be_bytes());"),
    ("sockdiag: mixed families allowed", S, "_ => return Err(io::Error::new(io::ErrorKind::InvalidInput, \"mixed address families\")),", "_ => AF_INET,"),
    # scaffolding.rs
    ("scaffolding: tcp isolated as udp", G, "IPPROTO_TCP => Proto::Tcp,", "IPPROTO_TCP => Proto::Udp,"),
    ("scaffolding: unknown protocol accepted", G, "_ => return Err(io::Error::new(io::ErrorKind::InvalidInput, \"unsupported IP protocol\")),", "_ => Proto::Tcp,"),
    ("scaffolding: no owner gets key 0", G, "None => return Err(io::Error::new(io::ErrorKind::NotFound, \"no owner for the flow\")),", "None => return Ok(0),"),
    ("scaffolding: lookup error swallowed", G, "match (self.inner.lookup)(proto, to_socket_addr(src), to_socket_addr(dst))? {", "match (self.inner.lookup)(proto, to_socket_addr(src), to_socket_addr(dst)).unwrap_or(None) {"),
    ("scaffolding: protect fails", G, "fn protect(&self, _fd: RawFd, _addr: &SocketAddr) -> io::Result<()> {\n        Ok(())", "fn protect(&self, _fd: RawFd, _addr: &SocketAddr) -> io::Result<()> {\n        Err(io::Error::from(io::ErrorKind::Other))"),
    ("scaffolding: should_protect true", G, "fn should_protect(&self, _isolation_key: u64) -> bool {\n        false", "fn should_protect(&self, _isolation_key: u64) -> bool {\n        true"),
    ("scaffolding: count wraps", G, "*n = n.saturating_add(1);", "*n = n.wrapping_add(1);"),
    ("scaffolding: key truncated to a uid", G, "if let Ok(uid) = u32::try_from(isolation_key) {", "if let Ok(uid) = u32::try_from(isolation_key % 4294967296) {"),
    ("scaffolding: late bootstrap revives a failed tunnel", G, "DaemonState::Bootstrapping as u8,\n            DaemonState::Up as u8,\n            Ordering::SeqCst,", "DaemonState::Failed as u8,\n            DaemonState::Up as u8,\n            Ordering::SeqCst,"),
    ("scaffolding: ipv4 port dropped", G, "IpAddr::V4(a), ep.port)", "IpAddr::V4(a), 0)"),
    ("scaffolding: refresh all is per app", G, "let _ = self.inner.commands.try_send(TunnelCommand::RefreshCircuits);", "let _ = self.inner.commands.try_send(TunnelCommand::RefreshCircuitsForApp { isolation_key: 0 });"),
    # server.rs
    ("server: hello of any version", V, "ToDaemon::Hello { version } if version == PROTOCOL_VERSION =>", "ToDaemon::Hello { version } if version != PROTOCOL_VERSION =>"),
    ("server: memory quota not validated", V, "self.memory_max = valid;", "self.memory_max = valid + 1;"),
    ("server: shutdown forgotten", V, "self.shutdown = true;", "self.shutdown = false;"),
    ("server: shutdown does not end serve", V, "Reply::closing(FromDaemon::Ack { seq })", "Reply::one(FromDaemon::Ack { seq })"),
    ("server: shutdown acknowledged with another sequence number", V, "Reply::closing(FromDaemon::Ack { seq })", "Reply::closing(FromDaemon::Ack { seq: 0 })"),
    ("server: version mismatch keeps the connection", V, "Reply::closing(FromDaemon::Error { seq: 0, code: ErrorCode::Unsupported })", "Reply::one(FromDaemon::Error { seq: 0, code: ErrorCode::Unsupported })"),
    ("server: serve ignores the close flag", V, "                    if close {\n                        return Ok(());", "                    if false {\n                        return Ok(());"),
    ("server: a refused request is acknowledged", V, "Err(code) => Self::error(seq, code),", "Err(_) => Self::ack(seq),"),
    ("server: a refused quota is acknowledged", V, "Err(_) => Reply::error(seq, ErrorCode::BadValue),", "Err(_) => Reply::ack(seq),"),
    ("server: overrides are not stored", V, "Reply::result(seq, p.set_overrides(uid, companies))", "Reply::ack(seq)"),
    ("server: policy is not stored", V, "Reply::result(seq, p.set_classes(classes))", "Reply::ack(seq)"),
    ("server: end inside a frame is fine", V, "if buf.is_empty() { Ok(()) } else { Err(io::ErrorKind::UnexpectedEof.into()) }", "Ok(())"),
    ("server: refresh app refreshes all", V, "self.scaffolding.refresh_app(uid);", "self.scaffolding.refresh_all();"),
    ("server: bind removes any file", V, "Ok(m) if m.file_type().is_socket() => {", "Ok(_) => {"),
    ("server: bind keeps default mode", V, "from_mode(0o600)", "from_mode(0o666)"),
    ("sockdiag: listener accepted as owner (tcp)", S, "const TCP_FLOW_STATES: [u8; 8] = [1, 2, 4, 5, 7, 8, 9, 11];", "const TCP_FLOW_STATES: [u8; 9] = [1, 2, 4, 5, 7, 8, 9, 10, 11];"),
    ("sockdiag: time wait accepted as owner", S, "const TCP_FLOW_STATES: [u8; 8] = [1, 2, 4, 5, 7, 8, 9, 11];", "const TCP_FLOW_STATES: [u8; 9] = [1, 2, 4, 5, 6, 7, 8, 9, 11];"),
    ("sockdiag: syn sent refused", S, "const TCP_FLOW_STATES: [u8; 8] = [1, 2, 4, 5, 7, 8, 9, 11];", "const TCP_FLOW_STATES: [u8; 7] = [1, 4, 5, 7, 8, 9, 11];"),
    ("sockdiag: listener accepted as owner (udp)", S, "const UDP_FLOW_STATES: [u8; 2] = [1, 7];", "const UDP_FLOW_STATES: [u8; 3] = [1, 7, 10];"),
    ("sockdiag: unconnected udp refused", S, "const UDP_FLOW_STATES: [u8; 2] = [1, 7];", "const UDP_FLOW_STATES: [u8; 1] = [1];"),
    ("sockdiag: tcp ends swapped", S, "Proto::Tcp => (local, remote, IPPROTO_TCP),", "Proto::Tcp => (remote, local, IPPROTO_TCP),"),
    ("sockdiag: self check inverted", S, "if owner_uid(Proto::Tcp, client.local_addr()?, client.peer_addr()?)? != Some(me) {", "if owner_uid(Proto::Tcp, client.local_addr()?, client.peer_addr()?)? == Some(me) {"),
    ("server: live socket taken over", V, "if UnixStream::connect(path).is_ok() {", "if false {"),
    ("config: control characters allowed", F, "if s.chars().any(|c| c.is_control()) {", "if false {"),
    ("dns: refusal is not SERVFAIL", D, "result.map_err(|_| Self::make_answer(query, RCode::ServFail, vec![]))", "result.map_err(|_| Self::make_answer(query, RCode::NoError, vec![]))"),
    ("dns: shared key instead of refusal", D, "result.map_err(|_| Self::make_answer(query, RCode::ServFail, vec![]))", "Ok(result.unwrap_or(0))"),
    # args.rs
    ("args: duplicate allowed", A, "if slot.is_some() {", "if false {"),
    ("args: uid cast instead of checked", A, "let uid = u32::try_from(digits(name, value)?).map_err(|_| ArgError::Value(name))?;", "let uid = digits(name, value)? as u32;"),
    ("args: quota not checked", A, "let memory_max = validate_memory_max(memory_max).map_err(|_| ArgError::MemoryMax)?;", "let memory_max = memory_max;"),
    ("args: device not checked", A, "if !valid_iface(value.as_bytes()) {", "if false {"),
    # config.rs
    ("config: no escaping", F, "if c == '\\\\' || c == '\"' {", "if false {"),
    ("config: expansion characters allowed", F, "if s.contains('$') || s.starts_with('~') {", "if false {"),
    ("config: relative path allowed", F, "if !p.is_absolute() {", "if false {"),
    ("config: file mode", F, ".mode(0o600)", ".mode(0o644)"),
    ("config: quota not validated", F, "validate_memory_max(c.memory_max).map_err(|_| ConfigError::MemoryMax)?;", "();"),
    ("config: loaded quota not read back", F, "if text.contains(&format!(\"max: ByteQty({bytes})\")) {", "if true {"),
    # Task 14: the entry point. The mask list is required, the route helper has none of the daemon's options, the mode is the
    # first argument; main.rs is judged by the process tests of tests/cli.rs.
    ("args: mask list not required", A, "let mask_list = s.mask_list.take().ok_or(ArgError::Missing(MASK_LIST))?;", "let mask_list = s.mask_list.take().unwrap_or_default();"),
    ("args: the route helper takes the daemon's options", A, "if !daemon && DAEMON_ONLY.contains(&args[i].as_str()) {", "if false {"),
    ("args: route mode allows --state-dir", A, "const DAEMON_ONLY: [&str; 6] = [STATE_DIR, CACHE_DIR,", "const DAEMON_ONLY: [&str; 6] = [CACHE_DIR, CACHE_DIR,"),
    ("args: route mode allows --cache-dir", A, "const DAEMON_ONLY: [&str; 6] = [STATE_DIR, CACHE_DIR,", "const DAEMON_ONLY: [&str; 6] = [STATE_DIR, STATE_DIR,"),
    ("args: route mode allows --memory-max", A, "MEMORY_MAX, DEVICE, MASK_LIST, NO_FS_CHECKS];", "STATE_DIR, DEVICE, MASK_LIST, NO_FS_CHECKS];"),
    ("args: route mode allows --device", A, "MEMORY_MAX, DEVICE, MASK_LIST, NO_FS_CHECKS];", "MEMORY_MAX, STATE_DIR, MASK_LIST, NO_FS_CHECKS];"),
    ("args: route mode allows --mask-list", A, "MEMORY_MAX, DEVICE, MASK_LIST, NO_FS_CHECKS];", "MEMORY_MAX, DEVICE, STATE_DIR, NO_FS_CHECKS];"),
    ("args: route mode allows the permission-check switch", A, "MEMORY_MAX, DEVICE, MASK_LIST, NO_FS_CHECKS];", "MEMORY_MAX, DEVICE, MASK_LIST, STATE_DIR];"),
    ("args: the daemon is the route mode by default", A, "_ => parse_args(args).map(Command::Daemon),", "_ => parse_route_args(args).map(Command::Routes),"),
    ("args: --routes starts the daemon", A, "if first == ROUTES => parse_route_args(rest).map(Command::Routes),", "if first == ROUTES => parse_args(rest).map(Command::Daemon),"),
    ("masklist: a file one byte over the limit is read as a list", L, "file.take(MAX_FILE + 1)", "file.take(MAX_FILE)"),
    ("masklist: the size check is gone", L, "if text.len() as u64 > MAX_FILE {", "if false {"),
    ("masklist: the size limit is exclusive", L, "if text.len() as u64 > MAX_FILE {", "if text.len() as u64 >= MAX_FILE {"),
    ("masklist: a missing file is an empty list", L, "let file = std::fs::File::open(path).map_err(|_| ListError::Unreadable)?;", "let Ok(file) = std::fs::File::open(path) else { return Ok(MaskList::default()) };"),
    ("masklist: a file that is not UTF-8 is read lossily", L, "        file.take(MAX_FILE + 1).read_to_string(&mut text).map_err(|_| ListError::Unreadable)?;", "        { let mut raw = Vec::new(); let _ = file.take(MAX_FILE + 1).read_to_end(&mut raw); text = String::from_utf8_lossy(&raw).into_owned(); }"),
    ("server: the control server writes another policy table than the packet path reads", V, "let control = Control::with_policy(scaffolding.clone(), iface, memory_max, policy);", "let control = Control::new(scaffolding.clone(), iface, memory_max);"),
    ("policy: the exempt apps need Tor", P, "self.classes.iter().any(|c| c.kind != Kind::Exempt)", "!self.classes.is_empty()"),
    ("policy: only an app on Tor needs Tor", P, "c.kind != Kind::Exempt", "c.kind == Kind::Tor"),
    ("policy: the daemon does not wait for the first class", P, "while !policy.read().unwrap_or_else(std::sync::PoisonError::into_inner).needs_tor() {", "while false {"),
    ("policy: the wait looks late", P, "Duration::from_millis(250)", "Duration::from_millis(2500)"),
    ("main: a list that does not load exits with status 1", "src/main.rs", "eprintln!(\"exitd: mask list could not be loaded\");\n                return ExitCode::from(2);", "eprintln!(\"exitd: mask list could not be loaded\");\n                return ExitCode::from(1);"),
    ("main: a list that does not load is ignored", "src/main.rs", "let Ok(list) = MaskList::load(&a.mask_list) else {", "let list = MaskList::load(&a.mask_list).unwrap_or_default(); let Ok(list) = Ok::<_, ()>(list) else {"),
    ("main: the route helper is not started", "src/main.rs", "Command::Routes(a) => run_routes(a),", "Command::Routes(_) => Ok(()),"),
    ("main: the route helper lets any uid in", "src/main.rs", "Peer::Uid(uid) => Auth::Uid(*uid),", "Peer::Uid(_) => Auth::Uid(0),"),
    ("main: the route helper serves nobody", "src/main.rs", "let _ = serve_route(stream, &who, &mut helper.lock().unwrap_or_else(PoisonError::into_inner));", "let _ = (stream, &who, &helper);"),
]


_CONST = re.compile(r"^(?P<head>(?:pub(?:\([a-z]+\))? )?const (?P<name>[A-Z0-9_]+): (?P<ty>u8|u16|u32|u64|i32|usize) = )(?P<val>0x[0-9a-fA-F]+|[0-9]+)(?P<tail>;.*)$", re.M)


def constant_mutations(files, label):
    """One mutation per plain numeric constant: its value plus one. A constant that no test pins survives."""
    out = []
    for f in files:
        text = open(os.path.join(ROOT, f)).read()
        for m in _CONST.finditer(text):
            old = m.group(0)
            if text.count(old) != 1:
                continue
            val = int(m.group("val"), 0)
            new = m.group("head") + str(val + 1) + m.group("tail")
            out.append((f"{label}: {f.split('/')[-1]} {m.group('name')} + 1", f, old, new))
    return out


MUTATIONS += constant_mutations(
    ["src/routes/netlink.rs", "src/routes/proto.rs", "src/routes/reconcile.rs", "src/masklist.rs", "src/ladder.rs", "src/ratelimit.rs"],
    "const",
)


def killed(output: str, rc: int) -> str:
    """killed only when the tests ran and failed; anything else (ssh error, no tests, a build error) proves nothing."""
    if rc == 0:
        return "survived"
    if "could not compile" in output or "error[E" in output:
        return "does-not-compile"
    # A test binary that crashed (an abort on a descriptor closed twice, say) was stopped by the mutation as much as one
    # that failed an assertion.
    # A mutation that makes a loop endless stops the test run too: CI would end it by its time-out, and so does the script.
    if "test run killed by the time-out" in output:
        return "killed"
    if "test result: FAILED" in output or "panicked at" in output or "fatal runtime error" in output or "(signal:" in output:
        return "killed"
    return "inconclusive"


def main() -> int:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    # --shard=I/N runs every Nth mutation starting at I, so that several runs (each in its own work directory on the
    # server) share a long list. The shards together run every mutation exactly once.
    shard = next((a for a in sys.argv[1:] if a.startswith("--shard=")), None)
    only_check = "--check-applies" in sys.argv
    # Any number of name filters; a mutation runs when its name contains one of them (all of them with no filter).
    muts = [m for m in MUTATIONS if not args or any(f in m[0] for f in args)]
    if shard:
        i, n = (int(x) for x in shard.split("=", 1)[1].split("/"))
        muts = [m for k, m in enumerate(muts) if k % n == i]
    work = tempfile.mkdtemp(prefix="exit-mutations-")
    copy = os.path.join(work, "exit-daemon")
    shutil.copytree(ROOT, copy, ignore=shutil.ignore_patterns("target", ".git", "__pycache__"))
    bad = [n for n, f, old, new in muts if open(os.path.join(copy, f)).read().count(old) != 1]
    for n in bad:
        print(f"DOES NOT APPLY exactly once: {n}")
    if bad:
        return 3
    if only_check:
        print(f"all {len(muts)} mutations apply")
        return 0
    env = dict(os.environ, EXIT_SRC=copy)
    check = os.path.join(ROOT, "tools", "check-on-server.sh")
    base = subprocess.run(["bash", check], env=env, capture_output=True, text=True)
    if base.returncode != 0:
        print("the unmutated tests do not pass; nothing to measure")
        print(base.stdout[-2000:], base.stderr[-2000:])
        return 4
    results = []
    for name, f, old, new in muts:
        path = os.path.join(copy, f)
        orig = open(path).read()
        open(path, "w").write(orig.replace(old, new))
        try:
            # A mutation of the daemon's own sources cannot change a vendored test: only the daemon's crate is tested.
            menv = dict(env, EXIT_ONLY_DAEMON="0" if f == D else "1", EXIT_TEST_TIMEOUT="600")
            # Only the library's tests link the daemon's modules; the binary's own tests matter for main.rs only.
            targs = ["--lib"] if f.startswith("src/") and f != "src/main.rs" else []
            r = subprocess.run(["bash", check, *targs], env=menv, capture_output=True, text=True, timeout=1800)
            verdict = killed(r.stdout + r.stderr, r.returncode)
        except subprocess.TimeoutExpired:
            verdict = "timeout"
        finally:
            open(path, "w").write(orig)
        results.append((name, verdict))
        print(f"{verdict:17} {name}", flush=True)
    n_killed = sum(1 for _, v in results if v == "killed")
    print(f"{n_killed} of {len(results)} mutations killed")
    shutil.rmtree(work, ignore_errors=True)
    return 0 if n_killed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
