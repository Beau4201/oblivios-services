#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Mutation check of the exit daemon's host tests.

Copies the crate to a temporary directory, applies ONE mutation at a time (an exact, unique string replaced by another)
and runs tools/check-on-server.sh on the copy. A mutation is killed when the tests fail for it. A mutation that does not
compile is NOT counted as killed: it proves nothing about the tests. A mutation whose text is not found exactly once in
its file is an error of this list, not a surviving mutant.

    EXIT_BUILD_SSH=user@host tools/run-mutations.py [--check-applies] [NAME_FILTER ...]

--check-applies only checks that every mutation applies to the current sources. The result line is
"N of N mutations killed"; the exit status is 0 only when all were killed.
"""
import os
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

D = "third_party/onionmasq/crates/onion-tunnel/src/dns.rs"
C, S, V, G, A, F, P, Y, R = (
    "src/control.rs",
    "src/sockdiag.rs",
    "src/server.rs",
    "src/scaffolding.rs",
    "src/args.rs",
    "src/config.rs",
    "src/policy.rs",
    "src/sockdiag/sys.rs",
    "src/routes/spec.rs",
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
    ("control: protocol version", C, "pub const PROTOCOL_VERSION: u8 = 2;", "pub const PROTOCOL_VERSION: u8 = 3;"),
    ("control: first app uid", C, "pub const FIRST_APP_UID: u32 = 10000;", "pub const FIRST_APP_UID: u32 = 1000;"),
    ("control: class limit", C, "pub const MAX_CLASSES: usize = 256;", "pub const MAX_CLASSES: usize = 257;"),
    ("control: override limit", C, "pub const MAX_OVERRIDES: usize = 32;", "pub const MAX_OVERRIDES: usize = 33;"),
    ("control: short list accepted", C, "    if payload.len() < want {\n        return Err(DecodeError::BadLength);", "    if false {\n        return Err(DecodeError::BadLength);"),
    ("control: list trailing bytes accepted", C, "    if payload.len() > want {\n        return Err(DecodeError::Trailing);", "    if false {\n        return Err(DecodeError::Trailing);"),
    ("control: unknown class kind accepted", C, "        _ => Err(DecodeError::BadValue),\n    }\n}", "        _ => Ok(Kind::Exempt),\n    }\n}"),
    ("control: encoder emits more classes than accepted", C, "classes[..classes.len().min(MAX_CLASSES)]", "classes[..classes.len().min(MAX_CLASSES + 100)]"),
    ("control: ack carries no sequence number", C, "FromDaemon::Ack { seq } => frame(TAG_ACK, &[*seq]),", "FromDaemon::Ack { .. } => frame(TAG_ACK, &[0]),"),
    ("control: error code swapped", C, "FromDaemon::Error { seq, code } => frame(TAG_ERROR, &[*seq, *code as u8]),", "FromDaemon::Error { seq, code } => frame(TAG_ERROR, &[*code as u8, *seq]),"),
    ("control: failures company dropped", C, "p[4..6].copy_from_slice(&company.to_be_bytes());", "p[4..6].copy_from_slice(&0u16.to_be_bytes());"),
    # policy.rs
    ("policy: overlap allowed", P, "if c.start < FIRST_APP_UID || c.start > c.end || u64::from(c.start) < next_free {", "if c.start < FIRST_APP_UID || c.start > c.end {"),
    ("policy: ranges below the first app uid allowed", P, "if c.start < FIRST_APP_UID || c.start > c.end || u64::from(c.start) < next_free {", "if c.start > c.end || u64::from(c.start) < next_free {"),
    ("policy: start after end allowed", P, "if c.start < FIRST_APP_UID || c.start > c.end || u64::from(c.start) < next_free {", "if c.start < FIRST_APP_UID || u64::from(c.start) < next_free {"),
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
    ("server: peer check after the first read", V, "    check(&stream)?;\n    stream.set_read_timeout", "    stream.set_read_timeout"),
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
    ("scaffolding: no owner gets key 0", G, "None => Err(io::Error::new(io::ErrorKind::NotFound, \"no owner for the flow\")),", "None => Ok(0),"),
    ("scaffolding: lookup error swallowed", G, "match (self.inner.lookup)(proto, to_socket_addr(src), to_socket_addr(dst))? {", "match (self.inner.lookup)(proto, to_socket_addr(src), to_socket_addr(dst)).unwrap_or(None) {"),
    ("scaffolding: protect fails", G, "fn protect(&self, _fd: RawFd, _addr: &SocketAddr) -> io::Result<()> {\n        Ok(())", "fn protect(&self, _fd: RawFd, _addr: &SocketAddr) -> io::Result<()> {\n        Err(io::Error::from(io::ErrorKind::Other))"),
    ("scaffolding: should_protect true", G, "fn should_protect(&self, _isolation_key: u64) -> bool {\n        false", "fn should_protect(&self, _isolation_key: u64) -> bool {\n        true"),
    ("scaffolding: count wraps", G, "*n = n.saturating_add(1);", "*n = n.wrapping_add(1);"),
    ("scaffolding: key truncated to a uid", G, "if let Ok(uid) = u32::try_from(isolation_key) {", "if let Ok(uid) = u32::try_from(isolation_key % 4294967296) {"),
    ("scaffolding: late bootstrap revives a failed tunnel", G, "DaemonState::Bootstrapping as u8,\n            DaemonState::Up as u8,\n            Ordering::SeqCst,", "DaemonState::Failed as u8,\n            DaemonState::Up as u8,\n            Ordering::SeqCst,"),
    ("scaffolding: ipv4 port dropped", G, "IpAddr::V4(a), ep.port)", "IpAddr::V4(a), 0)"),
    ("scaffolding: refresh all is per app", G, "let _ = self.inner.commands.send(TunnelCommand::RefreshCircuits);", "let _ = self.inner.commands.send(TunnelCommand::RefreshCircuitsForApp { isolation_key: 0 });"),
    # server.rs
    ("server: hello of any version", V, "ToDaemon::Hello { version } if version == PROTOCOL_VERSION =>", "ToDaemon::Hello { version } if version != PROTOCOL_VERSION =>"),
    ("server: memory quota not validated", V, "self.memory_max = valid;", "self.memory_max = valid + 1;"),
    ("server: shutdown forgotten", V, "self.shutdown = true;", "self.shutdown = false;"),
    ("server: shutdown does not end serve", V, "Reply::closing(FromDaemon::Ack { seq })", "Reply::one(FromDaemon::Ack { seq })"),
    ("server: shutdown acknowledged with another sequence number", V, "Reply::closing(FromDaemon::Ack { seq })", "Reply::closing(FromDaemon::Ack { seq: 0 })"),
    ("server: version mismatch keeps the connection", V, "Reply::closing(FromDaemon::Error { seq: 0, code: ErrorCode::Unsupported })", "Reply::one(FromDaemon::Error { seq: 0, code: ErrorCode::Unsupported })"),
    ("server: serve ignores the close flag", V, "if reply.close {", "if false {"),
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
]


def killed(output: str, rc: int) -> str:
    """killed only when the tests ran and failed; anything else (ssh error, no tests, a build error) proves nothing."""
    if rc == 0:
        return "survived"
    if "could not compile" in output or "error[E" in output:
        return "does-not-compile"
    if "test result: FAILED" in output or "panicked at" in output:
        return "killed"
    return "inconclusive"


def main() -> int:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    only_check = "--check-applies" in sys.argv
    # Any number of name filters; a mutation runs when its name contains one of them (all of them with no filter).
    muts = [m for m in MUTATIONS if not args or any(f in m[0] for f in args)]
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
            menv = dict(env, EXIT_ONLY_DAEMON="0" if f == D else "1")
            r = subprocess.run(["bash", check], env=menv, capture_output=True, text=True, timeout=1800)
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
