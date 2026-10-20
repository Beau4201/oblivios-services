// SPDX-License-Identifier: Apache-2.0
//! The platform side of the tunnel (ADR-184). Every flow is attributed to the uid that owns its socket, and a flow that
//! cannot be attributed is refused: the daemon never hands out a shared key. It sets no socket mark and protects no
//! socket: its own uid stays outside every network preference, so its connections to the Tor network leave on the normal
//! default network by the normal routing.

use crate::control::DaemonState;
use crate::sockdiag::Proto;
use futures::Stream;
use onion_tunnel::scaffolding::{FailedConnectionDetails, TunnelCommand};
use onion_tunnel::{CountryCode, TunnelScaffolding};
use smoltcp::wire::{IpAddress, IpEndpoint};
use std::collections::HashMap;
use std::io;
use std::net::{IpAddr, SocketAddr};
use std::os::fd::RawFd;
use std::pin::Pin;
use std::sync::atomic::{AtomicU8, Ordering};
use std::sync::{Arc, Mutex, PoisonError};
use std::task::{Context, Poll};
use tokio::sync::mpsc::{unbounded_channel, UnboundedReceiver, UnboundedSender};

const IPPROTO_TCP: u8 = 6;
const IPPROTO_UDP: u8 = 17;

/// Looks up the owner uid of a flow from its protocol, local end and remote end.
pub type Lookup = dyn Fn(Proto, SocketAddr, SocketAddr) -> io::Result<Option<u32>> + Send + Sync;

/// The scaffolding of the daemon. A clone is a handle to the same state: one clone goes into the tunnel, the others stay
/// with the control server and the entry point.
#[derive(Clone)]
pub struct ExitScaffolding {
    inner: Arc<Inner>,
}

struct Inner {
    lookup: Box<Lookup>,
    /// Failed Tor connections per uid: counts only, never a destination or a name.
    failures: Mutex<HashMap<u32, u32>>,
    commands: UnboundedSender<TunnelCommand>,
    command_rx: Mutex<Option<UnboundedReceiver<TunnelCommand>>>,
    state: AtomicU8,
}

/// The commands for the tunnel as a stream; handed to the tunnel once.
struct Commands(UnboundedReceiver<TunnelCommand>);

impl Stream for Commands {
    type Item = TunnelCommand;

    fn poll_next(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Option<TunnelCommand>> {
        self.get_mut().0.poll_recv(cx)
    }
}

impl ExitScaffolding {
    pub fn new(lookup: Box<Lookup>) -> Self {
        let (commands, rx) = unbounded_channel();
        Self {
            inner: Arc::new(Inner {
                lookup,
                failures: Mutex::new(HashMap::new()),
                commands,
                command_rx: Mutex::new(Some(rx)),
                state: AtomicU8::new(DaemonState::Bootstrapping as u8),
            }),
        }
    }

    /// The failure counts, ordered by uid.
    pub fn failure_counts(&self) -> Vec<(u32, u32)> {
        let map = self.inner.failures.lock().unwrap_or_else(PoisonError::into_inner);
        let mut v: Vec<(u32, u32)> = map.iter().map(|(uid, n)| (*uid, *n)).collect();
        v.sort_unstable();
        v
    }

    /// Counts one failed connection of the uid that `isolation_key` stands for. A key that is not a uid is not ours.
    fn record_failure(&self, isolation_key: u64) {
        if let Ok(uid) = u32::try_from(isolation_key) {
            let mut map = self.inner.failures.lock().unwrap_or_else(PoisonError::into_inner);
            let n = map.entry(uid).or_insert(0);
            *n = n.saturating_add(1);
        }
    }

    /// New circuits for the connections of one app from now on.
    pub fn refresh_app(&self, uid: u32) {
        // The receiver lives as long as the tunnel; once it is gone there is nothing left to refresh.
        let _ = self.inner.commands.send(TunnelCommand::RefreshCircuitsForApp { isolation_key: u64::from(uid) });
    }

    /// New circuits for every connection from now on.
    pub fn refresh_all(&self) {
        let _ = self.inner.commands.send(TunnelCommand::RefreshCircuits);
    }

    pub fn state(&self) -> DaemonState {
        match self.inner.state.load(Ordering::SeqCst) {
            1 => DaemonState::Up,
            2 => DaemonState::Failed,
            _ => DaemonState::Bootstrapping,
        }
    }

    /// Marks the tunnel as failed. Final: a late bootstrap callback does not undo it.
    pub fn set_failed(&self) {
        self.inner.state.store(DaemonState::Failed as u8, Ordering::SeqCst);
    }
}

fn to_socket_addr(ep: IpEndpoint) -> SocketAddr {
    match ep.addr {
        IpAddress::Ipv4(a) => SocketAddr::new(IpAddr::V4(a), ep.port),
        IpAddress::Ipv6(a) => SocketAddr::new(IpAddr::V6(a), ep.port),
    }
}

impl TunnelScaffolding for ExitScaffolding {
    fn protect(&self, _fd: RawFd, _addr: &SocketAddr) -> io::Result<()> {
        Ok(())
    }

    fn should_protect(&self, _isolation_key: u64) -> bool {
        false
    }

    fn isolate(&self, src: IpEndpoint, dst: IpEndpoint, ip_proto: u8) -> io::Result<u64> {
        // Upstream panics on a protocol it does not know; the daemon refuses it.
        let proto = match ip_proto {
            IPPROTO_TCP => Proto::Tcp,
            IPPROTO_UDP => Proto::Udp,
            _ => return Err(io::Error::new(io::ErrorKind::InvalidInput, "unsupported IP protocol")),
        };
        match (self.inner.lookup)(proto, to_socket_addr(src), to_socket_addr(dst))? {
            Some(uid) => Ok(u64::from(uid)),
            None => Err(io::Error::new(io::ErrorKind::NotFound, "no owner for the flow")),
        }
    }

    fn locate(&self, _src: IpEndpoint, _dst: IpEndpoint, _isolation_key: u64) -> Option<CountryCode> {
        None
    }

    fn on_bootstrapped(&self) {
        // Bootstrapping to Up only: a failed tunnel stays failed.
        let _ = self.inner.state.compare_exchange(
            DaemonState::Bootstrapping as u8,
            DaemonState::Up as u8,
            Ordering::SeqCst,
            Ordering::SeqCst,
        );
    }

    fn command_stream(&self) -> Option<Box<dyn Stream<Item = TunnelCommand> + Send + Sync>> {
        let rx = self.inner.command_rx.lock().unwrap_or_else(PoisonError::into_inner).take()?;
        Some(Box::new(Commands(rx)))
    }

    fn on_arti_failure(&self, details: FailedConnectionDetails) {
        self.record_failure(details.isolation_key);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use onion_tunnel::scaffolding::TunnelCommand;
    use smoltcp::wire::{IpAddress, IpEndpoint};
    use std::io;
    use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, TcpListener};
    use std::sync::{Arc, Mutex};

    fn v4(a: [u8; 4], port: u16) -> IpEndpoint {
        IpEndpoint::new(IpAddress::v4(a[0], a[1], a[2], a[3]), port)
    }

    fn fixed(answer: Option<u32>) -> ExitScaffolding {
        ExitScaffolding::new(Box::new(move |_, _, _| Ok(answer)))
    }

    #[test]
    fn a_flow_with_an_owner_is_isolated_by_its_uid() {
        let s = fixed(Some(10231));
        assert_eq!(s.isolate(v4([10, 0, 0, 2], 4000), v4([93, 184, 216, 34], 443), 6).unwrap(), 10231);
        assert_eq!(s.isolate(v4([10, 0, 0, 2], 4000), v4([93, 184, 216, 34], 443), 17).unwrap(), 10231);
    }

    #[test]
    fn uid_zero_is_a_real_key() {
        assert_eq!(fixed(Some(0)).isolate(v4([10, 0, 0, 2], 1), v4([1, 1, 1, 1], 1), 6).unwrap(), 0);
    }

    #[test]
    fn a_flow_without_an_owner_is_refused() {
        let e = fixed(None).isolate(v4([10, 0, 0, 2], 1), v4([1, 1, 1, 1], 1), 6).unwrap_err();
        assert_eq!(e.kind(), io::ErrorKind::NotFound);
    }

    #[test]
    fn a_failed_lookup_is_refused_with_its_error() {
        let s = ExitScaffolding::new(Box::new(|_, _, _| Err(io::Error::from(io::ErrorKind::PermissionDenied))));
        let e = s.isolate(v4([10, 0, 0, 2], 1), v4([1, 1, 1, 1], 1), 6).unwrap_err();
        assert_eq!(e.kind(), io::ErrorKind::PermissionDenied);
    }

    #[test]
    fn an_unknown_protocol_is_an_error_and_never_reaches_the_lookup() {
        let calls = Arc::new(Mutex::new(0));
        let seen = calls.clone();
        let s = ExitScaffolding::new(Box::new(move |_, _, _| {
            *seen.lock().unwrap() += 1;
            Ok(Some(1))
        }));
        for proto in [0u8, 1, 2, 5, 7, 16, 18, 58, 255] {
            let e = s.isolate(v4([10, 0, 0, 2], 1), v4([1, 1, 1, 1], 1), proto).unwrap_err();
            assert_eq!(e.kind(), io::ErrorKind::InvalidInput, "protocol {proto}");
        }
        assert_eq!(*calls.lock().unwrap(), 0);
    }

    #[test]
    fn the_lookup_gets_the_protocol_and_the_two_ends_of_the_flow() {
        let seen: Arc<Mutex<Vec<(Proto, SocketAddr, SocketAddr)>>> = Arc::default();
        let log = seen.clone();
        let s = ExitScaffolding::new(Box::new(move |p, l, r| {
            log.lock().unwrap().push((p, l, r));
            Ok(Some(7))
        }));
        s.isolate(v4([10, 0, 0, 2], 4000), v4([93, 184, 216, 34], 443), 6).unwrap();
        s.isolate(v4([10, 0, 0, 2], 5000), v4([9, 9, 9, 9], 53), 17).unwrap();
        let seen = seen.lock().unwrap();
        assert_eq!(seen[0], (Proto::Tcp, "10.0.0.2:4000".parse().unwrap(), "93.184.216.34:443".parse().unwrap()));
        assert_eq!(seen[1], (Proto::Udp, "10.0.0.2:5000".parse().unwrap(), "9.9.9.9:53".parse().unwrap()));
    }

    #[test]
    fn endpoints_convert_for_both_families() {
        assert_eq!(to_socket_addr(v4([10, 1, 2, 3], 80)), SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 1, 2, 3)), 80));
        let a = Ipv6Addr::new(0xfd00, 0, 0, 0, 0, 0, 0, 2);
        let ep = IpEndpoint::new(IpAddress::Ipv6(a), 443);
        assert_eq!(to_socket_addr(ep), SocketAddr::new(IpAddr::V6(a), 443));
    }

    #[test]
    fn nothing_is_protected_and_no_mark_is_set() {
        let s = fixed(Some(1));
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        let fd = std::os::fd::AsRawFd::as_raw_fd(&listener);
        assert!(s.protect(fd, &addr).is_ok());
        assert_eq!(so_mark(fd), 0);
        assert!(!s.should_protect(10231));
        assert!(!s.should_protect(0));
    }

    #[test]
    fn a_country_is_never_chosen() {
        let s = fixed(Some(1));
        assert_eq!(s.locate(v4([10, 0, 0, 2], 1), v4([1, 1, 1, 1], 1), 1), None);
    }

    #[test]
    fn failures_are_counted_per_uid_and_nothing_else() {
        let s = fixed(Some(1));
        assert_eq!(s.failure_counts(), vec![]);
        s.record_failure(10231);
        s.record_failure(10231);
        s.record_failure(10007);
        assert_eq!(s.failure_counts(), vec![(10007, 1), (10231, 2)]);
    }

    #[test]
    fn a_key_that_is_not_a_uid_is_not_counted() {
        let s = fixed(Some(1));
        s.record_failure(u64::from(u32::MAX) + 1);
        assert_eq!(s.failure_counts(), vec![]);
    }

    #[test]
    fn a_count_saturates_instead_of_wrapping() {
        let s = fixed(Some(1));
        s.inner.failures.lock().unwrap().insert(5, u32::MAX);
        s.record_failure(5);
        assert_eq!(s.failure_counts(), vec![(5, u32::MAX)]);
    }

    #[test]
    fn a_refresh_for_an_app_reaches_the_tunnel_as_a_command_for_its_key() {
        use futures::StreamExt;
        let s = fixed(Some(1));
        let mut commands = Box::into_pin(s.command_stream().expect("the stream is handed out once"));
        s.refresh_app(10231);
        s.refresh_all();
        match futures::executor::block_on(commands.next()) {
            Some(TunnelCommand::RefreshCircuitsForApp { isolation_key }) => assert_eq!(isolation_key, 10231),
            other => panic!("unexpected command {other:?}"),
        }
        assert!(matches!(futures::executor::block_on(commands.next()), Some(TunnelCommand::RefreshCircuits)));
    }

    #[test]
    fn the_command_stream_is_handed_out_once() {
        let s = fixed(Some(1));
        assert!(s.command_stream().is_some());
        assert!(s.command_stream().is_none());
    }

    #[test]
    fn the_state_follows_the_tunnel() {
        use crate::control::DaemonState;
        let s = fixed(Some(1));
        assert_eq!(s.state(), DaemonState::Bootstrapping);
        s.on_bootstrapped();
        assert_eq!(s.state(), DaemonState::Up);
        s.set_failed();
        assert_eq!(s.state(), DaemonState::Failed);
        // A late bootstrap callback does not revive a failed tunnel.
        s.on_bootstrapped();
        assert_eq!(s.state(), DaemonState::Failed);
    }

    /// Reads SO_MARK back from a socket; `unsafe` is confined to this test helper.
    #[allow(unsafe_code)]
    fn so_mark(fd: i32) -> u32 {
        let mut mark: u32 = 0xdead_beef;
        let mut len = std::mem::size_of::<u32>() as libc::socklen_t;
        // SAFETY: the pointers describe `mark` and `len`, which outlive the call.
        let r = unsafe { libc::getsockopt(fd, libc::SOL_SOCKET, libc::SO_MARK, (&mut mark as *mut u32).cast(), &mut len) };
        assert_eq!(r, 0, "getsockopt(SO_MARK)");
        mark
    }
}
