#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Mutation check of the exit daemon's host tests.

Copies the crate to a temporary directory, applies ONE mutation at a time (an exact, unique string replaced by another)
and runs tools/check-on-server.sh on the copy. A mutation is killed when the tests fail for it. A mutation that does not
compile is NOT counted as killed: it proves nothing about the tests. A mutation whose text is not found exactly once in
its file is an error of this list, not a surviving mutant.

    EXIT_BUILD_SSH=user@host tools/run-mutations.py [--check-applies] [NAME_FILTER]

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
C, S, V, G, A, F = (
    "src/control.rs",
    "src/sockdiag.rs",
    "src/server.rs",
    "src/scaffolding.rs",
    "src/args.rs",
    "src/config.rs",
)

# (name, file, old, new)
MUTATIONS = [
    # control.rs: limits, bounds, tags, the trailing check
    ("control: frame limit off by one (>=)", C, "if len > MAX_FRAME {", "if len >= MAX_FRAME {"),
    ("control: frame limit raised", C, "if len > MAX_FRAME {", "if len > MAX_FRAME + 1 {"),
    ("control: MAX_FRAME changed", C, "pub const MAX_FRAME: usize = 1024;", "pub const MAX_FRAME: usize = 2048;"),
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
    ("control: state value 3 accepted", C, "_ => return Err(DecodeError::BadValue),", "_ => DaemonState::Failed,"),
    ("control: empty frame has a tag", C, "let (tag, payload) = body.split_first().ok_or(DecodeError::BadLength)?;", "let (tag, payload) = body.split_first().unwrap_or((&0x01, &[]));"),
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
    ("server: wrong peer accepted", V, "!= peer_uid {", "== peer_uid {"),
    ("server: hello of any version", V, "ToDaemon::Hello { version } if version == PROTOCOL_VERSION =>", "ToDaemon::Hello { version } if version != PROTOCOL_VERSION =>"),
    ("server: memory quota not validated", V, "self.memory_max = valid;", "self.memory_max = valid + 1;"),
    ("server: shutdown forgotten", V, "self.shutdown = true;", "self.shutdown = false;"),
    ("server: shutdown does not end serve", V, "let shutdown = matches!(msg, ToDaemon::Shutdown);", "let shutdown = false;"),
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
    flt = args[0] if args else ""
    muts = [m for m in MUTATIONS if flt in m[0]]
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
            r = subprocess.run(["bash", check], env=env, capture_output=True, text=True, timeout=1800)
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
