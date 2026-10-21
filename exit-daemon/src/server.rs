// SPDX-License-Identifier: Apache-2.0
//! The control channel of the daemon (ADR-184): a Unix stream socket that speaks the typed protocol of `control`. The
//! peer's uid is read from the kernel before the first byte is, and a peer with any other uid gets nothing. A frame that
//! is too long, unknown or malformed ends the connection; the buffer never grows past one frame plus one read.

use crate::control::{
    decode_to_daemon, encode_from_daemon, validate_memory_max, DecodeError, ErrorCode, FromDaemon, ToDaemon, MAX_FRAME,
    PROTOCOL_VERSION,
};
use crate::masklist::MaskList;
use crate::policy::{Policy, SharedPolicy};
use crate::scaffolding::{ExitScaffolding, Lookup};
use crate::sockdiag::{
    listener_from_fd, peer_context as socket_peer_context, peer_uid as socket_peer_uid,
};
use std::io::{self, Read, Write};
use std::os::fd::AsFd;
use std::os::unix::fs::{FileTypeExt, PermissionsExt};
use std::os::unix::net::{UnixListener, UnixStream};
use std::path::Path;
use std::sync::{Arc, PoisonError, RwLock};
use std::time::{Duration, Instant};

/// A peer that sends nothing for this long is dropped; one that does not take a reply within `WRITE_TIMEOUT` too. A frame
/// that has begun must be complete within `FRAME_DEADLINE`: a byte now and then does not keep a connection for ever.
const IDLE_TIMEOUT: Duration = Duration::from_secs(60);
const FRAME_DEADLINE: Duration = Duration::from_secs(10);
const WRITE_TIMEOUT: Duration = Duration::from_secs(5);
const READ_CHUNK: usize = 512;
/// The most failure reports one `GetState` carries; the rest are sent with the next one.
const MAX_FAILURES_PER_STATE: usize = 16;

/// What the daemon answers to one message: the frames to write, in order, and whether the connection ends after them.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Reply {
    pub msgs: Vec<FromDaemon>,
    pub close: bool,
}

impl Reply {
    fn one(m: FromDaemon) -> Self {
        Self { msgs: vec![m], close: false }
    }

    fn closing(m: FromDaemon) -> Self {
        Self { msgs: vec![m], close: true }
    }

    fn ack(seq: u8) -> Self {
        Self::one(FromDaemon::Ack { seq })
    }

    fn error(seq: u8, code: ErrorCode) -> Self {
        Self::one(FromDaemon::Error { seq, code })
    }

    fn result(seq: u8, r: Result<(), ErrorCode>) -> Self {
        match r {
            Ok(()) => Self::ack(seq),
            Err(code) => Self::error(seq, code),
        }
    }
}

/// The scaffolding and the control server of one daemon, built around ONE policy table: what the exit app writes through
/// the control socket is what the packet path reads. A pair built from two tables would start, answer every message and
/// carry nothing.
pub fn wired(lookup: Box<Lookup>, list: MaskList, iface: String, memory_max: u64) -> (ExitScaffolding, Control) {
    let policy: SharedPolicy = Arc::new(RwLock::new(Policy::default()));
    let scaffolding = ExitScaffolding::with_policy(lookup, policy.clone(), Arc::new(list));
    let control = Control::with_policy(scaffolding.clone(), iface, memory_max, policy);
    (scaffolding, control)
}

/// What the daemon does with each message of the peer.
pub struct Control {
    scaffolding: ExitScaffolding,
    iface: String,
    memory_max: u64,
    shutdown: bool,
    policy: SharedPolicy,
}

impl Control {
    pub fn new(scaffolding: ExitScaffolding, iface: String, memory_max: u64) -> Self {
        Self::with_policy(scaffolding, iface, memory_max, Arc::new(RwLock::new(Policy::default())))
    }

    /// Like [`Control::new`], with the policy table that the packet path reads too.
    pub fn with_policy(scaffolding: ExitScaffolding, iface: String, memory_max: u64, policy: SharedPolicy) -> Self {
        Self { scaffolding, iface, memory_max, shutdown: false, policy }
    }

    /// The memory quota the daemon will use the next time it builds its configuration. Applying a new value to a
    /// running Arti and keeping it across a restart belong to the service definition.
    pub fn memory_max(&self) -> u64 {
        self.memory_max
    }

    pub fn shutdown_requested(&self) -> bool {
        self.shutdown
    }

    pub fn policy(&self) -> SharedPolicy {
        self.policy.clone()
    }

    pub fn handle(&mut self, m: ToDaemon) -> Reply {
        match m {
            ToDaemon::Hello { version } if version == PROTOCOL_VERSION => {
                Reply::one(FromDaemon::Ready { version: PROTOCOL_VERSION, iface: self.iface.clone() })
            }
            // A peer of another version learns that at once and the connection ends, so it cannot go on speaking a
            // protocol this daemon does not understand.
            ToDaemon::Hello { .. } => Reply::closing(FromDaemon::Error { seq: 0, code: ErrorCode::Unsupported }),
            ToDaemon::SetMemoryMax { seq, bytes } => match validate_memory_max(bytes) {
                Ok(valid) => {
                    self.memory_max = valid;
                    Reply::ack(seq)
                }
                Err(_) => Reply::error(seq, ErrorCode::BadValue),
            },
            ToDaemon::RefreshApp { seq, uid } => {
                self.scaffolding.refresh_app(uid);
                Reply::ack(seq)
            }
            ToDaemon::RefreshAll { seq } => {
                self.scaffolding.refresh_all();
                Reply::ack(seq)
            }
            ToDaemon::Shutdown { seq } => {
                self.shutdown = true;
                Reply::closing(FromDaemon::Ack { seq })
            }
            ToDaemon::GetState { .. } => {
                // The state, then the apps and companies whose failures crossed a threshold since the last call.
                let mut msgs = vec![FromDaemon::State { state: self.scaffolding.state() }];
                msgs.extend(
                    self.scaffolding
                        .due_failures(MAX_FAILURES_PER_STATE)
                        .into_iter()
                        .map(|d| FromDaemon::Failures { uid: d.uid, company: d.company, count: d.count }),
                );
                Reply { msgs, close: false }
            }
            ToDaemon::SetPolicy { seq, classes } => {
                let mut p = self.policy.write().unwrap_or_else(PoisonError::into_inner);
                Reply::result(seq, p.set_classes(classes))
            }
            ToDaemon::SetOverrides { seq, uid, companies } => {
                let mut p = self.policy.write().unwrap_or_else(PoisonError::into_inner);
                Reply::result(seq, p.set_overrides(uid, companies))
            }
        }
    }
}

/// Creates the control socket at `path` with mode 0600. A socket file left by an earlier run (nobody listens on it) is replaced; anything else at
/// that path (a regular file, a directory, a symlink even to a socket) is an error and is never removed.
pub fn bind_control(path: &Path) -> io::Result<UnixListener> {
    match std::fs::symlink_metadata(path) {
        Ok(m) if m.file_type().is_socket() => {
            // A daemon that still listens there is not ours to replace.
            if UnixStream::connect(path).is_ok() {
                return Err(io::Error::new(io::ErrorKind::AddrInUse, "another daemon listens on the control socket"));
            }
            std::fs::remove_file(path)?
        }
        Ok(_) => return Err(io::Error::new(io::ErrorKind::AlreadyExists, "control path exists and is not a socket")),
        Err(e) if e.kind() == io::ErrorKind::NotFound => {}
        Err(e) => return Err(e),
    }
    let listener = UnixListener::bind(path)?;
    std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600))?;
    Ok(listener)
}

fn invalid(e: DecodeError) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, format!("control frame: {e:?}"))
}

/// Who may talk to the daemon. Production uses the SELinux type of the exit app (`Context`), because a platform app gets
/// its uid at install time; a uid is for the runs on a phone as root and for the tests.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Auth {
    Uid(u32),
    /// The SELinux type (for example `exit_app`) that the peer's context must have.
    Context(String),
}

/// True when `ctx` is a context `u:r:<want>:...` of the given type. The level and categories after the type are not
/// compared; the user and role must be the ones of Android's single-user policy. A type that only starts with `want`,
/// an empty field or a missing field does not match.
pub fn context_has_type(ctx: &str, want: &str) -> bool {
    let mut fields = ctx.split(':');
    matches!((fields.next(), fields.next(), fields.next()), (Some("u"), Some("r"), Some(t)) if t == want && !want.is_empty())
}

fn denied(why: &'static str) -> io::Error {
    io::Error::new(io::ErrorKind::PermissionDenied, why)
}

/// The uid decision: the peer is accepted only when its uid was read and is the wanted one.
fn check_uid(got: io::Result<u32>, want: u32) -> io::Result<()> {
    match got {
        Ok(uid) if uid == want => Ok(()),
        Ok(_) => Err(denied("control peer has the wrong uid")),
        Err(_) => Err(denied("control peer uid unknown")),
    }
}

/// The context decision: accepted only when the context was read and has the wanted type.
fn check_context(got: io::Result<String>, want: &str) -> io::Result<()> {
    match got {
        Ok(ctx) if context_has_type(&ctx, want) => Ok(()),
        Ok(_) => Err(denied("control peer has the wrong SELinux context")),
        Err(_) => Err(denied("control peer context unknown")),
    }
}

/// The check `serve` makes before it reads a byte. Every failure to learn who the peer is counts as a refusal.
pub(crate) fn check_peer(stream: &UnixStream, auth: &Auth) -> io::Result<()> {
    match auth {
        Auth::Uid(want) => check_uid(socket_peer_uid(stream.as_fd()), *want),
        Auth::Context(want) => check_context(socket_peer_context(stream.as_fd()), want),
    }
}

/// The listening socket that `init` created for this service: the descriptor number is in the environment variable
/// `var` (`ANDROID_SOCKET_<name>`). Nothing is created or removed on disk.
pub fn inherited_control(var: &str) -> io::Result<UnixListener> {
    let value = std::env::var(var).map_err(|_| io::Error::new(io::ErrorKind::NotFound, "control socket variable not set"))?;
    // Plain decimal digits only, as for the command line.
    if value.is_empty() || !value.bytes().all(|b| b.is_ascii_digit()) {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "control socket variable is not a number"));
    }
    let raw: i32 = value.parse().map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "control socket number out of range"))?;
    listener_from_fd(raw)
}

/// Accepts connections on the control socket and serves each in its own thread, one at a time in effect: a new connection
/// of the exit app replaces the old one (the old one is shut down), because an exit app that restarted must not wait for
/// the dead connection to time out. The peer is checked before it can replace anything, so a stranger cannot cut off the
/// exit app. Returns when the listener fails; `on_shutdown` is called after a peer asked the daemon to shut down.
pub fn serve_connections(
    listener: UnixListener,
    auth: Auth,
    control: Arc<std::sync::Mutex<Control>>,
    on_shutdown: impl Fn() + Send + Sync + 'static,
) {
    let who = auth.clone();
    accept_replacing(listener, &auth, move |stream| {
        let _ = serve(stream, &who, |m| control.lock().unwrap_or_else(PoisonError::into_inner).handle(m));
        if control.lock().unwrap_or_else(PoisonError::into_inner).shutdown_requested() {
            on_shutdown();
        }
    });
}

/// The accept loop of both control sockets (the daemon's and the route helper's): the peer is checked before anything is
/// read, a connection of the right peer replaces the one before it (which is shut down) and `handler` serves it on its own
/// thread. Returns when the listener fails.
pub fn accept_replacing(listener: UnixListener, auth: &Auth, handler: impl Fn(UnixStream) + Send + Sync + 'static) {
    use std::net::Shutdown;
    let current: Arc<std::sync::Mutex<Option<UnixStream>>> = Arc::new(std::sync::Mutex::new(None));
    let handler = Arc::new(handler);
    for stream in listener.incoming() {
        let Ok(stream) = stream else {
            // A persistent accept error (no descriptors left) must not become a busy loop.
            std::thread::sleep(Duration::from_millis(100));
            continue;
        };
        if check_peer(&stream, auth).is_err() {
            continue; // dropped: closed without a byte read
        }
        if let Ok(handle) = stream.try_clone() {
            let mut cur = current.lock().unwrap_or_else(PoisonError::into_inner);
            if let Some(old) = cur.replace(handle) {
                let _ = old.shutdown(Shutdown::Both);
            }
        }
        let handler = handler.clone();
        std::thread::spawn(move || handler(stream));
    }
}

/// Serves one connection until the peer closes it, sends `Shutdown` or sends something invalid.
pub fn serve(stream: UnixStream, auth: &Auth, on_message: impl FnMut(ToDaemon) -> Reply) -> io::Result<()> {
    serve_checked(stream, |s| check_peer(s, auth), on_message)
}

/// [`serve`] with the peer check passed in, so that a test can stand in for a peer whose SELinux context it cannot set.
pub(crate) fn serve_checked(
    stream: UnixStream,
    check: impl FnOnce(&UnixStream) -> io::Result<()>,
    mut on_message: impl FnMut(ToDaemon) -> Reply,
) -> io::Result<()> {
    serve_framed(stream, check, decode_to_daemon, encode_from_daemon, |m| {
        let r = on_message(m);
        (r.msgs, r.close)
    })
}

/// The connection loop that both control sockets use (the daemon's and the route helper's): check the peer before a byte
/// is read, read frames into a buffer that never grows past one frame plus one read, answer each frame in order, and end
/// on a frame the handler marks as the last, on the peer's close, or on a frame that does not decode.
pub(crate) fn serve_framed<M, R>(
    stream: UnixStream,
    check: impl FnOnce(&UnixStream) -> io::Result<()>,
    decode: fn(&[u8]) -> Result<(M, usize), DecodeError>,
    encode: fn(&R) -> Vec<u8>,
    on_message: impl FnMut(M) -> (Vec<R>, bool),
) -> io::Result<()> {
    serve_framed_with(stream, check, decode, encode, on_message, IDLE_TIMEOUT, FRAME_DEADLINE)
}

/// [`serve_framed`] with the two time limits passed in, so that a test need not wait for the real ones.
pub(crate) fn serve_framed_with<M, R>(
    mut stream: UnixStream,
    check: impl FnOnce(&UnixStream) -> io::Result<()>,
    decode: fn(&[u8]) -> Result<(M, usize), DecodeError>,
    encode: fn(&R) -> Vec<u8>,
    mut on_message: impl FnMut(M) -> (Vec<R>, bool),
    idle: Duration,
    frame_deadline: Duration,
) -> io::Result<()> {
    check(&stream)?;
    stream.set_write_timeout(Some(WRITE_TIMEOUT))?;
    let mut buf: Vec<u8> = Vec::with_capacity(4 + MAX_FRAME + READ_CHUNK);
    let mut chunk = [0u8; READ_CHUNK];
    // When the bytes now in `buf` began: a partial frame must be complete by `frame_deadline` after this.
    let mut frame_began: Option<Instant> = None;
    loop {
        loop {
            match decode(&buf) {
                Ok((msg, used)) => {
                    buf.drain(..used);
                    frame_began = if buf.is_empty() { None } else { Some(Instant::now()) };
                    let (replies, close) = on_message(msg);
                    for m in &replies {
                        stream.write_all(&encode(m))?;
                    }
                    if close {
                        return Ok(());
                    }
                }
                Err(DecodeError::Incomplete) => break,
                Err(e) => return Err(invalid(e)),
            }
        }
        // How long to wait for more: the idle limit between frames, the rest of the deadline inside one.
        let wait = match frame_began {
            Some(t) => frame_deadline.saturating_sub(t.elapsed()),
            None => idle,
        };
        if wait.is_zero() {
            return Err(io::Error::new(io::ErrorKind::TimedOut, "a control frame did not arrive in time"));
        }
        stream.set_read_timeout(Some(wait))?;
        let n = loop {
            match stream.read(&mut chunk) {
                Ok(n) => break n,
                Err(e) if e.kind() == io::ErrorKind::Interrupted => continue,
                // The read time-out of a socket is reported as WouldBlock: say what it is.
                Err(e) if e.kind() == io::ErrorKind::WouldBlock => {
                    return Err(io::Error::new(io::ErrorKind::TimedOut, "no control frame arrived in time"));
                }
                Err(e) => return Err(e),
            }
        };
        if n == 0 {
            return if buf.is_empty() { Ok(()) } else { Err(io::ErrorKind::UnexpectedEof.into()) };
        }
        if buf.is_empty() {
            frame_began = Some(Instant::now());
        }
        buf.extend_from_slice(&chunk[..n]);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::control::{
        decode_from_daemon, encode_to_daemon, DaemonState, DecodeError, ErrorCode, FromDaemon, Kind, ToDaemon, UidClass,
        MAX_FRAME,
    };
    use crate::scaffolding::ExitScaffolding;
    use std::io::{ErrorKind, Read, Write};
    use std::os::unix::fs::MetadataExt;
    use std::os::unix::net::UnixStream;
    use std::thread;
    use std::time::Duration;

    const MIB: u64 = 1024 * 1024;

    fn my_uid() -> u32 {
        std::fs::metadata("/proc/self").unwrap().uid()
    }

    fn scaffolding() -> ExitScaffolding {
        ExitScaffolding::new(Box::new(|_, _, _| Ok(Some(1))))
    }

    fn control() -> Control {
        Control::new(scaffolding(), "exit0".into(), 64 * MIB)
    }

    /// Runs `serve` on one end of a socket pair in a thread; the other end is the peer.
    fn start(peer_uid: u32) -> (UnixStream, thread::JoinHandle<std::io::Result<()>>) {
        let (server, client) = UnixStream::pair().unwrap();
        client.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
        let mut c = control();
        let h = thread::spawn(move || serve(server, &Auth::Uid(peer_uid), |m| c.handle(m)));
        (client, h)
    }

    fn read_reply(client: &mut UnixStream) -> FromDaemon {
        let mut buf = Vec::new();
        let mut byte = [0u8; 1];
        loop {
            match decode_from_daemon(&buf) {
                Ok((m, used)) => {
                    assert_eq!(used, buf.len());
                    return m;
                }
                Err(DecodeError::Incomplete) => {
                    client.read_exact(&mut byte).unwrap();
                    buf.push(byte[0]);
                }
                Err(e) => panic!("bad reply: {e:?}"),
            }
        }
    }

    /// True when the peer sees the end of the stream: a clean EOF, or a reset when it still had unread data.
    fn sees_eof(client: &mut UnixStream) -> bool {
        let mut b = [0u8; 16];
        match client.read(&mut b) {
            Ok(0) => true,
            Ok(_) => false,
            Err(e) => e.kind() == ErrorKind::ConnectionReset,
        }
    }

    #[test]
    fn a_peer_with_the_expected_uid_gets_ready_for_hello() {
        let (mut client, h) = start(my_uid());
        client.write_all(&encode_to_daemon(&ToDaemon::Hello { version: PROTOCOL_VERSION })).unwrap();
        assert_eq!(read_reply(&mut client), FromDaemon::Ready { version: PROTOCOL_VERSION, iface: "exit0".into() });
        drop(client);
        assert!(h.join().unwrap().is_ok(), "an orderly close of the peer ends serve without error");
    }

    #[test]
    fn a_peer_with_another_uid_is_refused_before_a_byte_is_read() {
        let (mut client, h) = start(my_uid().wrapping_add(1));
        let e = h.join().unwrap().unwrap_err();
        assert_eq!(e.kind(), ErrorKind::PermissionDenied);
        assert!(sees_eof(&mut client), "no reply of any kind");
    }

    #[test]
    fn a_context_is_matched_on_user_role_and_type_only() {
        assert!(context_has_type("u:r:exit_app:s0", "exit_app"));
        assert!(context_has_type("u:r:exit_app:s0:c512,c768", "exit_app"));
        assert!(context_has_type("u:r:exit_app", "exit_app"), "the level is not compared");
        assert!(!context_has_type("u:r:exit_app_x:s0", "exit_app"), "a longer type is another type");
        assert!(!context_has_type("u:r:exit:s0", "exit_app"));
        assert!(!context_has_type("u:r:platform_app:s0", "exit_app"));
        assert!(!context_has_type("u:object_r:exit_app:s0", "exit_app"), "a file type is not a process domain");
        assert!(!context_has_type("x:r:exit_app:s0", "exit_app"));
        assert!(!context_has_type("u:exit_app:s0", "exit_app"), "a missing role");
        assert!(!context_has_type("u:r:", "exit_app"));
        assert!(!context_has_type("u:r", "exit_app"));
        assert!(!context_has_type("", "exit_app"));
        assert!(!context_has_type("exit_app", "exit_app"));
        assert!(!context_has_type("u:r:exit_app\0:s0", "exit_app"), "a NUL inside the type");
        assert!(!context_has_type(" u:r:exit_app:s0", "exit_app"));
        assert!(!context_has_type("u:r::s0", ""), "an empty wanted type never matches");
        assert!(!context_has_type("", ""));
    }

    #[test]
    fn the_uid_decision_accepts_only_a_uid_that_was_read_and_matches() {
        assert!(check_uid(Ok(1000), 1000).is_ok());
        assert_eq!(check_uid(Ok(1001), 1000).unwrap_err().kind(), ErrorKind::PermissionDenied);
        assert_eq!(check_uid(Ok(0), 1000).unwrap_err().kind(), ErrorKind::PermissionDenied);
        assert_eq!(check_uid(Err(ErrorKind::Other.into()), 1000).unwrap_err().kind(), ErrorKind::PermissionDenied);
    }

    #[test]
    fn the_context_decision_accepts_only_a_context_that_was_read_and_has_the_type() {
        assert!(check_context(Ok("u:r:exit_app:s0:c1,c2".into()), "exit_app").is_ok());
        for bad in ["u:r:platform_app:s0", "u:r:exit_app_x:s0", "", "u:r:system_app:s0"] {
            assert_eq!(check_context(Ok(bad.into()), "exit_app").unwrap_err().kind(), ErrorKind::PermissionDenied, "{bad:?}");
        }
        let unreadable = io::Error::from_raw_os_error(libc::ENOPROTOOPT);
        assert_eq!(check_context(Err(unreadable), "exit_app").unwrap_err().kind(), ErrorKind::PermissionDenied);
    }

    #[test]
    fn a_peer_the_check_refuses_gets_nothing_and_nothing_is_read() {
        let (server, mut client) = UnixStream::pair().unwrap();
        client.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
        // A frame that would be an error if it were read.
        client.write_all(&[0xff; 4]).unwrap();
        let h = thread::spawn(move || {
            serve_checked(server, |_| Err(io::Error::new(ErrorKind::PermissionDenied, "no")), |_| unreachable!("a refused peer"))
        });
        assert_eq!(h.join().unwrap().unwrap_err().kind(), ErrorKind::PermissionDenied);
        assert!(sees_eof(&mut client));
    }

    #[test]
    fn the_context_check_fails_closed_when_the_context_cannot_be_read() {
        // A socket pair of this process: on a kernel without SELinux the read fails, on one with it the context is not
        // the exit app's. Either way the peer is refused before a byte is read.
        let (server, mut client) = UnixStream::pair().unwrap();
        client.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
        client.write_all(&[0xff; 4]).unwrap();
        let h = thread::spawn(move || serve(server, &Auth::Context("exit_app".into()), |_| unreachable!("a refused peer")));
        assert_eq!(h.join().unwrap().unwrap_err().kind(), ErrorKind::PermissionDenied);
        assert!(sees_eof(&mut client));
    }

    #[test]
    fn the_uid_check_names_the_refusal_as_permission_denied() {
        let (server, _client) = UnixStream::pair().unwrap();
        let e = serve(server, &Auth::Uid(my_uid().wrapping_add(1)), |_| unreachable!("a refused peer")).unwrap_err();
        assert_eq!(e.kind(), ErrorKind::PermissionDenied);
    }

    #[test]
    fn a_listening_socket_from_init_is_taken_over() {
        use std::os::fd::IntoRawFd;
        let d = sockdir("inherit");
        let l = std::os::unix::net::UnixListener::bind(d.join("ctl")).unwrap();
        let var = "EXITD_TEST_FD_OK";
        std::env::set_var(var, l.into_raw_fd().to_string());
        let inherited = inherited_control(var).unwrap();
        let _client = UnixStream::connect(d.join("ctl")).unwrap();
        assert!(inherited.accept().is_ok());
        std::fs::remove_dir_all(&d).unwrap();
    }

    #[test]
    fn a_descriptor_that_is_not_a_listening_unix_socket_is_refused() {
        use std::os::fd::AsRawFd;
        let d = sockdir("notlisten");
        let check = |var: &str, raw: i32| {
            std::env::set_var(var, raw.to_string());
            inherited_control(var).unwrap_err().kind()
        };
        let file = std::fs::File::open("/dev/null").unwrap();
        check("EXITD_TEST_FD_FILE", file.as_raw_fd()); // a regular file: not a socket, whatever the error kind
        let (a, _b) = UnixStream::pair().unwrap();
        assert_eq!(check("EXITD_TEST_FD_STREAM", a.as_raw_fd()), ErrorKind::InvalidInput, "connected, not listening");
        let tcp = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        assert_eq!(check("EXITD_TEST_FD_TCP", tcp.as_raw_fd()), ErrorKind::InvalidInput, "not a Unix socket");
        let dgram = std::os::unix::net::UnixDatagram::bind(d.join("dg")).unwrap();
        assert_eq!(check("EXITD_TEST_FD_DGRAM", dgram.as_raw_fd()), ErrorKind::InvalidInput, "not a stream socket");
        assert_eq!(check("EXITD_TEST_FD_LOW", 2), ErrorKind::InvalidInput, "standard streams are never taken");
        check("EXITD_TEST_FD_CLOSED", 1_000_000); // not open
        std::fs::remove_dir_all(&d).unwrap();
    }

    #[test]
    fn the_control_socket_variable_must_be_a_plain_number() {
        assert_eq!(inherited_control("EXITD_TEST_FD_UNSET").unwrap_err().kind(), ErrorKind::NotFound);
        // "+999999" parses as a number and names no open descriptor: it must be refused for its form (InvalidInput), not for
        // the descriptor (a different error).
        for (i, bad) in ["", "abc", "-4", "+4", "+999999", " 4", "4 ", "0x4", "4.0", "99999999999999999999"].iter().enumerate() {
            let var = format!("EXITD_TEST_FD_BAD{i}");
            std::env::set_var(&var, bad);
            assert_eq!(inherited_control(&var).unwrap_err().kind(), ErrorKind::InvalidInput, "{bad:?}");
        }
    }

    #[test]
    fn a_frame_of_four_gib_closes_the_stream_without_a_reply() {
        let (mut client, h) = start(my_uid());
        client.write_all(&[0xff, 0xff, 0xff, 0xff]).unwrap();
        assert_eq!(h.join().unwrap().unwrap_err().kind(), ErrorKind::InvalidData);
        assert!(sees_eof(&mut client));
    }

    #[test]
    fn a_frame_one_byte_over_the_limit_is_refused() {
        let (mut client, h) = start(my_uid());
        client.write_all(&((MAX_FRAME + 1) as u32).to_be_bytes()).unwrap();
        assert_eq!(h.join().unwrap().unwrap_err().kind(), ErrorKind::InvalidData);
    }

    #[test]
    fn an_unknown_tag_closes_the_stream() {
        let (mut client, h) = start(my_uid());
        client.write_all(&[0, 0, 0, 1, 0x7f]).unwrap();
        assert_eq!(h.join().unwrap().unwrap_err().kind(), ErrorKind::InvalidData);
        assert!(sees_eof(&mut client));
    }

    #[test]
    fn a_stream_that_ends_inside_a_frame_is_an_error() {
        let (mut client, h) = start(my_uid());
        client.write_all(&encode_to_daemon(&ToDaemon::SetMemoryMax { seq: 1, bytes: 1 })[..6]).unwrap();
        drop(client);
        assert_eq!(h.join().unwrap().unwrap_err().kind(), ErrorKind::UnexpectedEof);
    }

    #[test]
    fn a_frame_that_arrives_one_byte_at_a_time_is_read() {
        let (mut client, h) = start(my_uid());
        for b in encode_to_daemon(&ToDaemon::Hello { version: PROTOCOL_VERSION }) {
            client.write_all(&[b]).unwrap();
            thread::sleep(Duration::from_millis(2));
        }
        assert_eq!(read_reply(&mut client), FromDaemon::Ready { version: PROTOCOL_VERSION, iface: "exit0".into() });
        drop(client);
        assert!(h.join().unwrap().is_ok());
    }

    #[test]
    fn two_frames_in_one_write_get_two_replies_in_order() {
        let (mut client, h) = start(my_uid());
        let mut both = encode_to_daemon(&ToDaemon::Hello { version: PROTOCOL_VERSION });
        both.extend(encode_to_daemon(&ToDaemon::RefreshAll { seq: 11 }));
        client.write_all(&both).unwrap();
        assert_eq!(read_reply(&mut client), FromDaemon::Ready { version: PROTOCOL_VERSION, iface: "exit0".into() });
        assert_eq!(read_reply(&mut client), FromDaemon::Ack { seq: 11 });
        drop(client);
        assert!(h.join().unwrap().is_ok());
    }

    #[test]
    fn shutdown_ends_serve_and_nothing_after_it_is_read() {
        let (mut client, h) = start(my_uid());
        let mut bytes = encode_to_daemon(&ToDaemon::Shutdown { seq: 3 });
        bytes.extend([0xff; 4]); // would be an error if it were read
        client.write_all(&bytes).unwrap();
        assert!(h.join().unwrap().is_ok());
        assert_eq!(read_reply(&mut client), FromDaemon::Ack { seq: 3 }, "the shutdown is acknowledged before the end");
    }

    #[test]
    fn the_peer_uid_of_a_socket_pair_is_the_uid_of_the_process() {
        let (a, _b) = UnixStream::pair().unwrap();
        assert_eq!(crate::sockdiag::peer_uid(std::os::fd::AsFd::as_fd(&a)).unwrap(), my_uid());
    }

    #[test]
    fn hello_with_another_protocol_version_gets_an_error_and_the_connection_ends() {
        let mut c = control();
        let refusal = Reply { msgs: vec![FromDaemon::Error { seq: 0, code: ErrorCode::Unsupported }], close: true };
        assert_eq!(c.handle(ToDaemon::Hello { version: PROTOCOL_VERSION + 1 }), refusal);
        assert_eq!(c.handle(ToDaemon::Hello { version: 0 }), refusal);
        assert_eq!(c.handle(ToDaemon::Hello { version: 1 }), refusal, "the old protocol is not spoken");
        // Through the socket: the error arrives, then the stream ends and serve returns without an error.
        let (mut client, h) = start(my_uid());
        client.write_all(&encode_to_daemon(&ToDaemon::Hello { version: 1 })).unwrap();
        assert_eq!(read_reply(&mut client), FromDaemon::Error { seq: 0, code: ErrorCode::Unsupported });
        assert!(sees_eof(&mut client));
        assert!(h.join().unwrap().is_ok());
    }

    #[test]
    fn hello_of_the_current_version_gets_ready_and_keeps_the_connection() {
        let mut c = control();
        let r = c.handle(ToDaemon::Hello { version: PROTOCOL_VERSION });
        assert_eq!(r.msgs, vec![FromDaemon::Ready { version: PROTOCOL_VERSION, iface: "exit0".into() }]);
        assert!(!r.close);
    }

    #[test]
    fn set_memory_max_below_the_bound_changes_nothing() {
        let mut c = control();
        let before = c.memory_max();
        for bytes in [0, 1, 31 * MIB, 1024 * MIB + 1, u64::MAX] {
            let r = c.handle(ToDaemon::SetMemoryMax { seq: 5, bytes });
            assert_eq!(r.msgs, vec![FromDaemon::Error { seq: 5, code: ErrorCode::BadValue }], "{bytes}");
            assert_eq!(c.memory_max(), before, "{bytes} changed the quota");
        }
        let r = c.handle(ToDaemon::SetMemoryMax { seq: 6, bytes: 128 * MIB });
        assert_eq!(r.msgs, vec![FromDaemon::Ack { seq: 6 }]);
        assert_eq!(c.memory_max(), 128 * MIB);
    }

    #[test]
    fn a_refresh_reaches_the_tunnel() {
        use futures::StreamExt;
        let s = scaffolding();
        let mut stream = Box::into_pin(onion_tunnel::TunnelScaffolding::command_stream(&s).unwrap());
        let mut c = Control::new(s, "exit0".into(), 64 * MIB);
        assert_eq!(c.handle(ToDaemon::RefreshApp { seq: 8, uid: 10231 }).msgs, vec![FromDaemon::Ack { seq: 8 }]);
        match futures::executor::block_on(stream.next()) {
            Some(onion_tunnel::scaffolding::TunnelCommand::RefreshCircuitsForApp { isolation_key }) => {
                assert_eq!(isolation_key, 10231)
            }
            other => panic!("unexpected {other:?}"),
        }
    }

    #[test]
    fn the_state_reply_follows_the_tunnel() {
        let s = scaffolding();
        let mut c = Control::new(s.clone(), "exit0".into(), 64 * MIB);
        assert_eq!(
            c.handle(ToDaemon::GetState { seq: 1 }).msgs,
            vec![FromDaemon::State { state: DaemonState::Bootstrapping }]
        );
        s.set_failed();
        assert_eq!(c.handle(ToDaemon::GetState { seq: 2 }).msgs, vec![FromDaemon::State { state: DaemonState::Failed }]);
    }

    /// A fresh 0700 directory for socket files.
    fn sockdir(tag: &str) -> std::path::PathBuf {
        use std::os::unix::fs::DirBuilderExt;
        let nanos = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos();
        let d = std::env::temp_dir().join(format!("exit-daemon-sock-{tag}-{}-{nanos}", std::process::id()));
        std::fs::DirBuilder::new().mode(0o700).create(&d).unwrap();
        d
    }

    #[test]
    fn the_control_socket_is_private_and_connectable() {
        use std::os::unix::fs::PermissionsExt;
        let d = sockdir("bind");
        let path = d.join("ctl");
        let listener = bind_control(&path).unwrap();
        assert_eq!(std::fs::metadata(&path).unwrap().permissions().mode() & 0o777, 0o600);
        let _client = UnixStream::connect(&path).unwrap();
        drop(listener);
        std::fs::remove_dir_all(&d).unwrap();
    }

    #[test]
    fn a_stale_socket_file_is_replaced() {
        let d = sockdir("stale");
        let path = d.join("ctl");
        drop(bind_control(&path).unwrap()); // leaves the socket file behind
        assert!(path.exists());
        let listener = bind_control(&path).unwrap();
        assert!(UnixStream::connect(&path).is_ok());
        drop(listener);
        std::fs::remove_dir_all(&d).unwrap();
    }

    #[test]
    fn a_path_that_is_not_a_socket_is_never_removed() {
        let d = sockdir("notsock");
        let file = d.join("precious");
        std::fs::write(&file, b"data").unwrap();
        assert_eq!(bind_control(&file).unwrap_err().kind(), ErrorKind::AlreadyExists);
        assert_eq!(std::fs::read(&file).unwrap(), b"data");
        // A symlink is refused as well, also one that points to a socket, and its target is left alone.
        let real = d.join("real");
        let _l = bind_control(&real).unwrap();
        let link = d.join("link");
        std::os::unix::fs::symlink(&real, &link).unwrap();
        assert_eq!(bind_control(&link).unwrap_err().kind(), ErrorKind::AlreadyExists);
        assert!(UnixStream::connect(&real).is_ok());
        // A directory is refused too.
        let sub = d.join("sub");
        std::fs::create_dir(&sub).unwrap();
        assert_eq!(bind_control(&sub).unwrap_err().kind(), ErrorKind::AlreadyExists);
        std::fs::remove_dir_all(&d).unwrap();
    }

    #[test]
    fn a_socket_with_a_live_daemon_behind_it_is_not_taken_over() {
        let d = sockdir("live");
        let path = d.join("ctl");
        let first = bind_control(&path).unwrap();
        assert_eq!(bind_control(&path).unwrap_err().kind(), ErrorKind::AddrInUse);
        // The first daemon is untouched.
        assert!(UnixStream::connect(&path).is_ok());
        drop(first);
        std::fs::remove_dir_all(&d).unwrap();
    }

    #[test]
    fn a_missing_directory_is_an_error() {
        let d = sockdir("nodir");
        assert!(bind_control(&d.join("absent").join("ctl")).is_err());
        std::fs::remove_dir_all(&d).unwrap();
    }

    #[test]
    fn shutdown_is_remembered() {
        let mut c = control();
        assert!(!c.shutdown_requested());
        let r = c.handle(ToDaemon::Shutdown { seq: 4 });
        assert_eq!(r, Reply { msgs: vec![FromDaemon::Ack { seq: 4 }], close: true });
        assert!(c.shutdown_requested());
    }

    fn flow_failures(s: &ExitScaffolding, uid: u32, host: &str, n: u32) {
        for _ in 0..n {
            s.flow_failed(uid, host, std::time::Instant::now());
        }
    }

    #[test]
    fn get_state_carries_the_failures_that_are_due_and_each_only_once() {
        let policy: SharedPolicy = Arc::new(RwLock::new(Policy::default()));
        let list = crate::masklist::MaskList::parse("exit-mask-list\t1\nads.example.com\t7\tExample Ads\n").unwrap();
        let s = ExitScaffolding::with_policy(Box::new(|_, _, _| Ok(Some(1))), policy, Arc::new(list));
        let mut c = Control::new(s.clone(), "exit0".into(), 64 * MIB);
        flow_failures(&s, 10100, "ads.example.com", 3);
        flow_failures(&s, 10101, "www.example.org", 2);
        let first = c.handle(ToDaemon::GetState { seq: 1 }).msgs;
        assert_eq!(
            first,
            vec![
                FromDaemon::State { state: DaemonState::Bootstrapping },
                FromDaemon::Failures { uid: 10100, company: 7, count: 3 }
            ]
        );
        assert_eq!(c.handle(ToDaemon::GetState { seq: 2 }).msgs.len(), 1, "reported once");
        flow_failures(&s, 10101, "www.example.org", 1);
        assert_eq!(
            c.handle(ToDaemon::GetState { seq: 3 }).msgs[1],
            FromDaemon::Failures { uid: 10101, company: 0, count: 3 },
            "an unlisted host is company 0"
        );
    }

    #[test]
    fn get_state_carries_at_most_sixteen_failures_and_leaves_the_rest_for_the_next_call() {
        let s = scaffolding();
        let mut c = Control::new(s.clone(), "exit0".into(), 64 * MIB);
        for uid in 10000..10020u32 {
            flow_failures(&s, uid, "x.example", 3);
        }
        assert_eq!(c.handle(ToDaemon::GetState { seq: 1 }).msgs.len(), 1 + 16);
        assert_eq!(c.handle(ToDaemon::GetState { seq: 2 }).msgs.len(), 1 + 4);
        assert_eq!(c.handle(ToDaemon::GetState { seq: 3 }).msgs.len(), 1);
    }

    fn dummy_decode(buf: &[u8]) -> Result<(u8, usize), DecodeError> {
        // Frames of the daemon's protocol, decoded to a small number: enough for the timing tests.
        crate::control::decode_to_daemon(buf).map(|(m, n)| (if matches!(m, ToDaemon::Hello { .. }) { 1 } else { 2 }, n))
    }

    fn dummy_encode(_: &u8) -> Vec<u8> {
        vec![0, 0, 0, 2, 0x84, 0]
    }

    #[test]
    fn a_frame_that_trickles_in_is_dropped_at_its_deadline_however_often_a_byte_arrives() {
        let (server, mut client) = UnixStream::pair().unwrap();
        let started = Instant::now();
        let h = thread::spawn(move || {
            serve_framed_with(server, |_| Ok(()), dummy_decode, dummy_encode, |_| (vec![], false), Duration::from_secs(30), Duration::from_millis(400))
        });
        // The header of a frame that claims 1000 bytes; then one byte every 60 ms, which never completes it.
        client.write_all(&1000u32.to_be_bytes()).unwrap();
        for _ in 0..40 {
            if client.write_all(&[0x41]).is_err() {
                break;
            }
            thread::sleep(Duration::from_millis(60));
        }
        let e = h.join().unwrap().unwrap_err();
        assert_eq!(e.kind(), ErrorKind::TimedOut);
        assert!(started.elapsed() < Duration::from_secs(2), "{:?}", started.elapsed());
    }

    #[test]
    fn the_start_of_a_frame_that_follows_a_complete_one_in_the_same_read_has_its_deadline_too() {
        let (server, mut client) = UnixStream::pair().unwrap();
        let started = Instant::now();
        let h = thread::spawn(move || {
            serve_framed_with(server, |_| Ok(()), dummy_decode, dummy_encode, |_| (vec![], false), Duration::from_secs(30), Duration::from_millis(300))
        });
        // A whole Hello and the first two bytes of the next frame in one write, then nothing.
        let mut bytes = encode_to_daemon(&ToDaemon::Hello { version: 2 });
        bytes.extend_from_slice(&[0, 0]);
        client.write_all(&bytes).unwrap();
        let e = h.join().unwrap().unwrap_err();
        assert_eq!(e.kind(), ErrorKind::TimedOut);
        assert!(started.elapsed() < Duration::from_secs(5), "dropped at the frame deadline and not at the idle limit: {:?}", started.elapsed());
    }

    #[test]
    fn a_peer_that_says_nothing_is_dropped_at_the_idle_limit() {
        let (server, _client) = UnixStream::pair().unwrap();
        let started = Instant::now();
        let e = serve_framed_with(server, |_| Ok(()), dummy_decode, dummy_encode, |_| (vec![], false), Duration::from_millis(300), Duration::from_secs(30))
            .unwrap_err();
        assert_eq!(e.kind(), ErrorKind::TimedOut);
        assert!(started.elapsed() >= Duration::from_millis(250) && started.elapsed() < Duration::from_secs(2));
    }

    #[test]
    fn complete_frames_keep_a_connection_alive_past_the_frame_deadline() {
        let (server, mut client) = UnixStream::pair().unwrap();
        let h = thread::spawn(move || {
            serve_framed_with(server, |_| Ok(()), dummy_decode, dummy_encode, |_| (vec![], false), Duration::from_secs(30), Duration::from_millis(300))
        });
        for _ in 0..12 {
            client.write_all(&encode_to_daemon(&ToDaemon::Hello { version: 2 })).unwrap();
            thread::sleep(Duration::from_millis(100));
        }
        drop(client);
        assert!(h.join().unwrap().is_ok(), "four times the deadline in all, but every frame was complete in time");
    }

    #[test]
    fn a_new_connection_of_the_peer_replaces_the_old_one() {
        let d = sockdir("replace");
        let path = d.join("ctl");
        let listener = bind_control(&path).unwrap();
        let ctl = Arc::new(std::sync::Mutex::new(control()));
        let c2 = ctl.clone();
        thread::spawn(move || serve_connections(listener, Auth::Uid(my_uid()), c2, || {}));
        let mut first = UnixStream::connect(&path).unwrap();
        first.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
        first.write_all(&encode_to_daemon(&ToDaemon::Hello { version: PROTOCOL_VERSION })).unwrap();
        assert!(matches!(read_reply(&mut first), FromDaemon::Ready { .. }));
        // The exit app restarted: its new connection is served at once, and the old one ends.
        let mut second = UnixStream::connect(&path).unwrap();
        second.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
        second.write_all(&encode_to_daemon(&ToDaemon::GetState { seq: 9 })).unwrap();
        assert!(matches!(read_reply(&mut second), FromDaemon::State { .. }));
        assert!(sees_eof(&mut first), "the old connection was shut down");
        std::fs::remove_dir_all(&d).unwrap();
    }

    #[test]
    fn a_stranger_is_dropped_without_a_byte_read() {
        let d = sockdir("stranger");
        let path = d.join("ctl");
        let listener = bind_control(&path).unwrap();
        let ctl = Arc::new(std::sync::Mutex::new(control()));
        // Nobody of this process's uid is the peer: every connection is a stranger.
        let c2 = ctl.clone();
        thread::spawn(move || serve_connections(listener, Auth::Uid(my_uid().wrapping_add(1)), c2, || {}));
        let mut s = UnixStream::connect(&path).unwrap();
        s.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
        let _ = s.write_all(&encode_to_daemon(&ToDaemon::Hello { version: PROTOCOL_VERSION }));
        assert!(sees_eof(&mut s), "no reply of any kind");
        std::fs::remove_dir_all(&d).unwrap();
    }

    #[test]
    fn a_shutdown_over_the_socket_calls_the_shutdown_hook() {
        let d = sockdir("shutdown");
        let path = d.join("ctl");
        let listener = bind_control(&path).unwrap();
        let ctl = Arc::new(std::sync::Mutex::new(control()));
        let (tx, rx) = std::sync::mpsc::channel();
        let c2 = ctl.clone();
        thread::spawn(move || {
            serve_connections(listener, Auth::Uid(my_uid()), c2, move || {
                let _ = tx.send(());
            })
        });
        let mut c = UnixStream::connect(&path).unwrap();
        c.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
        c.write_all(&encode_to_daemon(&ToDaemon::Shutdown { seq: 1 })).unwrap();
        assert_eq!(read_reply(&mut c), FromDaemon::Ack { seq: 1 });
        assert!(rx.recv_timeout(Duration::from_secs(5)).is_ok());
        std::fs::remove_dir_all(&d).unwrap();
    }

    #[test]
    fn the_control_server_and_the_packet_path_share_one_policy_table() {
        use smoltcp::wire::{IpAddress, IpEndpoint};
        let (scaffolding, mut ctl) = wired(Box::new(|_, _, _| Ok(Some(1))), MaskList::default(), "exit0".into(), 64 * MIB);
        let dst = IpEndpoint::new(IpAddress::v4(93, 184, 216, 34), 443);
        assert!(scaffolding.admit_tcp(10100, &dst).is_err(), "nobody is Tor before the exit app says so");
        let classes = vec![UidClass { start: 10100, end: 10100, kind: Kind::Tor }];
        assert_eq!(ctl.handle(ToDaemon::SetPolicy { seq: 1, classes }).msgs, vec![FromDaemon::Ack { seq: 1 }]);
        assert!(scaffolding.admit_tcp(10100, &dst).is_ok(), "what the control server wrote is what the packet path reads");
        assert!(scaffolding.admit_tcp(10101, &dst).is_err());
    }

    #[test]
    fn refresh_all_is_acknowledged_with_its_own_sequence_number() {
        let mut c = control();
        assert_eq!(c.handle(ToDaemon::RefreshAll { seq: 77 }).msgs, vec![FromDaemon::Ack { seq: 77 }]);
    }

    fn cls(start: u32, end: u32, kind: Kind) -> UidClass {
        UidClass { start, end, kind }
    }

    #[test]
    fn set_policy_is_stored_and_visible_to_the_packet_path() {
        let mut c = control();
        let policy = c.policy();
        let r = c.handle(ToDaemon::SetPolicy {
            seq: 3,
            classes: vec![cls(10000, 10230, Kind::Masked), cls(10231, 10231, Kind::Tor)],
        });
        assert_eq!(r.msgs, vec![FromDaemon::Ack { seq: 3 }]);
        assert!(!r.close);
        let p = policy.read().unwrap();
        assert_eq!(p.kind_of(10100), Some(Kind::Masked));
        assert_eq!(p.kind_of(10231), Some(Kind::Tor));
        assert_eq!(p.kind_of(10232), None);
    }

    #[test]
    fn a_refused_policy_changes_nothing_and_is_answered_with_the_code() {
        let mut c = control();
        c.handle(ToDaemon::SetPolicy { seq: 1, classes: vec![cls(10000, 10010, Kind::Tor)] });
        let bad = [
            (vec![cls(9999, 10005, Kind::Tor)], ErrorCode::BadValue),
            (vec![cls(10010, 10009, Kind::Tor)], ErrorCode::BadValue),
            (vec![cls(10000, 10010, Kind::Tor), cls(10010, 10020, Kind::Masked)], ErrorCode::BadValue),
            ((0..257u32).map(|i| cls(10000 + 2 * i, 10000 + 2 * i, Kind::Tor)).collect(), ErrorCode::TooMany),
        ];
        for (classes, code) in bad {
            let r = c.handle(ToDaemon::SetPolicy { seq: 9, classes });
            assert_eq!(r.msgs, vec![FromDaemon::Error { seq: 9, code }]);
            assert!(!r.close, "a refused request does not end the connection");
        }
        assert_eq!(c.policy().read().unwrap().kind_of(10005), Some(Kind::Tor));
        assert_eq!(c.policy().read().unwrap().class_count(), 1);
    }

    #[test]
    fn set_overrides_is_stored_and_a_refused_one_is_answered_with_the_code() {
        let mut c = control();
        assert_eq!(
            c.handle(ToDaemon::SetOverrides { seq: 2, uid: 10231, companies: vec![4, 5] }).msgs,
            vec![FromDaemon::Ack { seq: 2 }]
        );
        assert!(c.policy().read().unwrap().overridden(10231, 5));
        assert_eq!(
            c.handle(ToDaemon::SetOverrides { seq: 3, uid: 5, companies: vec![1] }).msgs,
            vec![FromDaemon::Error { seq: 3, code: ErrorCode::BadValue }]
        );
        assert_eq!(
            c.handle(ToDaemon::SetOverrides { seq: 4, uid: 10231, companies: (0..33).collect() }).msgs,
            vec![FromDaemon::Error { seq: 4, code: ErrorCode::TooMany }]
        );
        assert!(c.policy().read().unwrap().overridden(10231, 5), "the refused list left the old one");
    }

    #[test]
    fn a_policy_over_the_socket_is_acknowledged() {
        let (mut client, h) = start(my_uid());
        client
            .write_all(&encode_to_daemon(&ToDaemon::SetPolicy { seq: 21, classes: vec![cls(10000, 19999, Kind::Masked)] }))
            .unwrap();
        assert_eq!(read_reply(&mut client), FromDaemon::Ack { seq: 21 });
        client.write_all(&encode_to_daemon(&ToDaemon::GetState { seq: 22 })).unwrap();
        assert_eq!(read_reply(&mut client), FromDaemon::State { state: DaemonState::Bootstrapping });
        drop(client);
        assert!(h.join().unwrap().is_ok());
    }
}
