// SPDX-License-Identifier: Apache-2.0
//! The control channel of the daemon (ADR-184): a Unix stream socket that speaks the typed protocol of `control`. The
//! peer's uid is read from the kernel before the first byte is, and a peer with any other uid gets nothing. A frame that
//! is too long, unknown or malformed ends the connection; the buffer never grows past one frame plus one read.

use crate::control::{
    decode_to_daemon, encode_from_daemon, validate_memory_max, DecodeError, FromDaemon, ToDaemon, MAX_FRAME,
};
use crate::scaffolding::ExitScaffolding;
use crate::sockdiag::peer_uid as socket_peer_uid;
use std::io::{self, Read, Write};
use std::os::fd::AsFd;
use std::os::unix::fs::{FileTypeExt, PermissionsExt};
use std::os::unix::net::{UnixListener, UnixStream};
use std::path::Path;
use std::time::Duration;

/// The only protocol version this daemon speaks.
pub const PROTOCOL_VERSION: u8 = 1;
/// A peer that sends nothing for this long is dropped; one that does not take a reply within `WRITE_TIMEOUT` too.
const IDLE_TIMEOUT: Duration = Duration::from_secs(60);
const WRITE_TIMEOUT: Duration = Duration::from_secs(5);
const READ_CHUNK: usize = 512;

/// What the daemon does with each message of the peer.
pub struct Control {
    scaffolding: ExitScaffolding,
    iface: String,
    memory_max: u64,
    shutdown: bool,
}

impl Control {
    pub fn new(scaffolding: ExitScaffolding, iface: String, memory_max: u64) -> Self {
        Self { scaffolding, iface, memory_max, shutdown: false }
    }

    /// The memory quota the daemon will use the next time it builds its configuration. Applying a new value to a
    /// running Arti and keeping it across a restart belong to the service definition (milestone 3).
    pub fn memory_max(&self) -> u64 {
        self.memory_max
    }

    pub fn shutdown_requested(&self) -> bool {
        self.shutdown
    }

    fn state(&self) -> Option<FromDaemon> {
        Some(FromDaemon::State { state: self.scaffolding.state() })
    }

    pub fn handle(&mut self, m: ToDaemon) -> Option<FromDaemon> {
        match m {
            ToDaemon::Hello { version } if version == PROTOCOL_VERSION => {
                Some(FromDaemon::Ready { iface: self.iface.clone() })
            }
            // A peer of another version gets no Ready, so it cannot mistake this daemon for one it understands.
            ToDaemon::Hello { .. } => None,
            ToDaemon::SetMemoryMax { bytes } => {
                if let Ok(valid) = validate_memory_max(bytes) {
                    self.memory_max = valid;
                }
                self.state()
            }
            ToDaemon::RefreshApp { uid } => {
                self.scaffolding.refresh_app(uid);
                self.state()
            }
            ToDaemon::RefreshAll => {
                self.scaffolding.refresh_all();
                self.state()
            }
            ToDaemon::Shutdown => {
                self.shutdown = true;
                None
            }
        }
    }
}

/// Creates the control socket at `path` with mode 0600. A socket file left by an earlier run is replaced; anything else at
/// that path (a regular file, a directory, a symlink even to a socket) is an error and is never removed.
pub fn bind_control(path: &Path) -> io::Result<UnixListener> {
    match std::fs::symlink_metadata(path) {
        Ok(m) if m.file_type().is_socket() => std::fs::remove_file(path)?,
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

/// Serves one connection until the peer closes it, sends `Shutdown` or sends something invalid.
pub fn serve(
    mut stream: UnixStream,
    peer_uid: u32,
    mut on_message: impl FnMut(ToDaemon) -> Option<FromDaemon>,
) -> io::Result<()> {
    if socket_peer_uid(stream.as_fd())? != peer_uid {
        return Err(io::Error::new(io::ErrorKind::PermissionDenied, "control peer has the wrong uid"));
    }
    stream.set_read_timeout(Some(IDLE_TIMEOUT))?;
    stream.set_write_timeout(Some(WRITE_TIMEOUT))?;
    let mut buf: Vec<u8> = Vec::with_capacity(4 + MAX_FRAME + READ_CHUNK);
    let mut chunk = [0u8; READ_CHUNK];
    loop {
        loop {
            match decode_to_daemon(&buf) {
                Ok((msg, used)) => {
                    buf.drain(..used);
                    let shutdown = matches!(msg, ToDaemon::Shutdown);
                    if let Some(reply) = on_message(msg) {
                        stream.write_all(&encode_from_daemon(&reply))?;
                    }
                    if shutdown {
                        return Ok(());
                    }
                }
                Err(DecodeError::Incomplete) => break,
                Err(e) => return Err(invalid(e)),
            }
        }
        let n = loop {
            match stream.read(&mut chunk) {
                Ok(n) => break n,
                Err(e) if e.kind() == io::ErrorKind::Interrupted => continue,
                Err(e) => return Err(e),
            }
        };
        if n == 0 {
            return if buf.is_empty() { Ok(()) } else { Err(io::ErrorKind::UnexpectedEof.into()) };
        }
        buf.extend_from_slice(&chunk[..n]);
    }
}


#[cfg(test)]
mod tests {
    use super::*;
    use crate::control::{
        decode_from_daemon, encode_to_daemon, DaemonState, DecodeError, FromDaemon, ToDaemon, MAX_FRAME,
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
        let h = thread::spawn(move || serve(server, peer_uid, |m| c.handle(m)));
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
        assert_eq!(read_reply(&mut client), FromDaemon::Ready { iface: "exit0".into() });
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
        client.write_all(&encode_to_daemon(&ToDaemon::SetMemoryMax { bytes: 1 })[..6]).unwrap();
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
        assert_eq!(read_reply(&mut client), FromDaemon::Ready { iface: "exit0".into() });
        drop(client);
        assert!(h.join().unwrap().is_ok());
    }

    #[test]
    fn two_frames_in_one_write_get_two_replies_in_order() {
        let (mut client, h) = start(my_uid());
        let mut both = encode_to_daemon(&ToDaemon::Hello { version: PROTOCOL_VERSION });
        both.extend(encode_to_daemon(&ToDaemon::RefreshAll));
        client.write_all(&both).unwrap();
        assert_eq!(read_reply(&mut client), FromDaemon::Ready { iface: "exit0".into() });
        assert_eq!(read_reply(&mut client), FromDaemon::State { state: DaemonState::Bootstrapping });
        drop(client);
        assert!(h.join().unwrap().is_ok());
    }

    #[test]
    fn shutdown_ends_serve_and_nothing_after_it_is_read() {
        let (mut client, h) = start(my_uid());
        let mut bytes = encode_to_daemon(&ToDaemon::Shutdown);
        bytes.extend([0xff; 4]); // would be an error if it were read
        client.write_all(&bytes).unwrap();
        assert!(h.join().unwrap().is_ok());
    }

    #[test]
    fn the_peer_uid_of_a_socket_pair_is_the_uid_of_the_process() {
        let (a, _b) = UnixStream::pair().unwrap();
        assert_eq!(crate::sockdiag::peer_uid(std::os::fd::AsFd::as_fd(&a)).unwrap(), my_uid());
    }

    #[test]
    fn hello_with_another_protocol_version_gets_no_ready() {
        let mut c = control();
        assert_eq!(c.handle(ToDaemon::Hello { version: PROTOCOL_VERSION + 1 }), None);
        assert_eq!(c.handle(ToDaemon::Hello { version: 0 }), None);
    }

    #[test]
    fn set_memory_max_below_the_bound_changes_nothing() {
        let mut c = control();
        let before = c.memory_max();
        for bytes in [0, 1, 31 * MIB, 1024 * MIB + 1, u64::MAX] {
            let r = c.handle(ToDaemon::SetMemoryMax { bytes });
            assert_eq!(r, Some(FromDaemon::State { state: DaemonState::Bootstrapping }), "{bytes}");
            assert_eq!(c.memory_max(), before, "{bytes} changed the quota");
        }
        c.handle(ToDaemon::SetMemoryMax { bytes: 128 * MIB });
        assert_eq!(c.memory_max(), 128 * MIB);
    }

    #[test]
    fn a_refresh_reaches_the_tunnel() {
        use futures::StreamExt;
        let s = scaffolding();
        let mut stream = Box::into_pin(onion_tunnel::TunnelScaffolding::command_stream(&s).unwrap());
        let mut c = Control::new(s, "exit0".into(), 64 * MIB);
        c.handle(ToDaemon::RefreshApp { uid: 10231 });
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
        s.set_failed();
        assert_eq!(c.handle(ToDaemon::RefreshAll), Some(FromDaemon::State { state: DaemonState::Failed }));
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
    fn a_missing_directory_is_an_error() {
        let d = sockdir("nodir");
        assert!(bind_control(&d.join("absent").join("ctl")).is_err());
        std::fs::remove_dir_all(&d).unwrap();
    }

    #[test]
    fn shutdown_is_remembered() {
        let mut c = control();
        assert!(!c.shutdown_requested());
        assert_eq!(c.handle(ToDaemon::Shutdown), None);
        assert!(c.shutdown_requested());
    }
}
