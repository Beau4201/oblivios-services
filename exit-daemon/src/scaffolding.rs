// SPDX-License-Identifier: Apache-2.0
//! The platform side of the tunnel (ADR-184). Every flow is attributed to the uid that owns its socket, and a flow that
//! cannot be attributed is refused: the daemon never hands out a shared key. It sets no socket mark and protects no
//! socket: its own uid stays outside every network preference, so its connections to the Tor network leave on the normal
//! default network by the normal routing.

use crate::control::{DaemonState, Kind};
use crate::ladder::{host_of, Due, Ladder, SLOW};
use crate::masklist::MaskList;
use crate::policy::{Policy, SharedPolicy};
use crate::ratelimit::RateLimiter;
use crate::sockdiag::Proto;
use futures::Stream;
use onion_tunnel::scaffolding::{ConnectionDetails, DnsDecision, FailedConnectionDetails, TunnelCommand};
use onion_tunnel::{is_fake_address, CountryCode, TunnelScaffolding};
use smoltcp::wire::{IpAddress, IpEndpoint};
use std::collections::HashMap;
use std::io;
use std::net::{IpAddr, SocketAddr};
use std::os::fd::RawFd;
use std::pin::Pin;
use std::sync::atomic::{AtomicU8, Ordering};
use std::sync::{Arc, Mutex, PoisonError, RwLock};
use std::task::{Context, Poll};
use std::time::Instant;
use tokio::sync::mpsc::{channel, Receiver, Sender};

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
    /// What the exit app told the daemon about the apps, and the names that are masked for the apps of class `Masked`.
    policy: SharedPolicy,
    list: Arc<MaskList>,
    /// Failed Tor connections per uid: counts only, never a destination or a name.
    failures: Mutex<HashMap<u32, u32>>,
    /// The failure ladder: per app and company, what the exit app is told (counts and company ids only).
    ladder: Mutex<Ladder>,
    /// When each relayed flow began, to tell a slow connection from a quick one. Bounded.
    started: Mutex<HashMap<(IpEndpoint, IpEndpoint), Instant>>,
    /// Questions asked per app, for the limit on DNS.
    dns_limit: Mutex<RateLimiter>,
    commands: Sender<TunnelCommand>,
    command_rx: Mutex<Option<Receiver<TunnelCommand>>>,
    state: AtomicU8,
}

/// The commands for the tunnel as a stream; handed to the tunnel once.
struct Commands(Receiver<TunnelCommand>);

/// Commands waiting for the tunnel; past this a refresh is dropped (it is a request for new circuits, and a later one
/// asks for the same).
const COMMAND_QUEUE: usize = 256;
/// Most TCP flows relayed at once; each holds two small buffers of the packet layer outside the Tor quota.
const MAX_FLOWS: usize = 1024;

impl Stream for Commands {
    type Item = TunnelCommand;

    fn poll_next(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Option<TunnelCommand>> {
        self.get_mut().0.poll_recv(cx)
    }
}

impl ExitScaffolding {
    /// A scaffolding with no policy and an empty list: no app is Tor and no name is masked, so every DNS query is passed on.
    pub fn new(lookup: Box<Lookup>) -> Self {
        Self::with_policy(lookup, Arc::new(RwLock::new(Policy::default())), Arc::new(MaskList::default()))
    }

    /// A scaffolding that decides by `policy` (which the control server writes) and `list`.
    pub fn with_policy(lookup: Box<Lookup>, policy: SharedPolicy, list: Arc<MaskList>) -> Self {
        let (commands, rx) = channel(COMMAND_QUEUE);
        Self {
            inner: Arc::new(Inner {
                lookup,
                policy,
                list,
                failures: Mutex::new(HashMap::new()),
                ladder: Mutex::new(Ladder::default()),
                dns_limit: Mutex::new(RateLimiter::default()),
                started: Mutex::new(HashMap::new()),
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

    /// Remembers when a flow began, and says whether there is room for it: at most [`MAX_FLOWS`] flows are relayed at
    /// once, and the table of start times is the count.
    fn note_start(&self, src: IpEndpoint, dst: IpEndpoint, now: Instant) -> bool {
        let mut m = self.inner.started.lock().unwrap_or_else(PoisonError::into_inner);
        if m.len() >= MAX_FLOWS && !m.contains_key(&(src, dst)) {
            return false;
        }
        m.insert((src, dst), now);
        true
    }

    /// Whether a TCP flow of `uid` to `dst` may be carried through Tor. An app of class Tor may reach anything. An app of
    /// class Masked may reach only the fake addresses that the daemon handed out for its listed names (the steering rules
    /// send nothing else of it into the tunnel, so anything else is a stray flow). An app of class Exempt, an app that has
    /// no class and a uid the policy has never heard of may reach nothing: they would otherwise be relayed by accident.
    pub(crate) fn admit_tcp(&self, uid: u32, dst: &IpEndpoint) -> io::Result<()> {
        let policy = self.inner.policy.read().unwrap_or_else(PoisonError::into_inner);
        match policy.kind_of(uid) {
            Some(Kind::Tor) => Ok(()),
            Some(Kind::Masked) if is_fake_address(&dst.addr) => Ok(()),
            _ => Err(io::Error::new(io::ErrorKind::PermissionDenied, "the flow is not allowed for this app")),
        }
    }

    /// The company of a host name on the list, or 0 for a name that is not listed (an app set to Tor).
    fn company_of(&self, host: &str) -> u16 {
        self.inner.list.classify(host).unwrap_or(0)
    }

    /// A flow of `uid` to `host` failed to connect through Tor.
    pub(crate) fn flow_failed(&self, uid: u32, host: &str, now: Instant) {
        let company = self.company_of(host);
        self.inner.ladder.lock().unwrap_or_else(PoisonError::into_inner).record_failure(uid, company, now);
    }

    /// A flow of `uid` to `host`, which began at `started`, was established at `now`: a slow one counts for the ladder.
    pub(crate) fn flow_established(&self, uid: u32, host: &str, started: Option<Instant>, now: Instant) {
        if started.is_some_and(|s| now.saturating_duration_since(s) >= SLOW) {
            let company = self.company_of(host);
            self.inner.ladder.lock().unwrap_or_else(PoisonError::into_inner).record_slow(uid, company, now);
        }
    }

    /// The apps and companies that crossed a threshold and have not been reported in their window, at most `max`.
    pub fn due_failures(&self, max: usize) -> Vec<Due> {
        self.inner.ladder.lock().unwrap_or_else(PoisonError::into_inner).due(Instant::now(), max)
    }

    /// New circuits for the connections of one app from now on.
    pub fn refresh_app(&self, uid: u32) {
        // The receiver lives as long as the tunnel; once it is gone there is nothing left to refresh.
        let _ = self.inner.commands.try_send(TunnelCommand::RefreshCircuitsForApp { isolation_key: u64::from(uid) });
    }

    /// New circuits for every connection from now on.
    pub fn refresh_all(&self) {
        let _ = self.inner.commands.try_send(TunnelCommand::RefreshCircuits);
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
        let uid = match (self.inner.lookup)(proto, to_socket_addr(src), to_socket_addr(dst))? {
            Some(uid) => uid,
            None => return Err(io::Error::new(io::ErrorKind::NotFound, "no owner for the flow")),
        };
        // A TCP flow is relayed through Tor only for an app that is allowed it (ADR-186): never Direct, so the answer to
        // anything else is a reset. The library asks for UDP only to attribute a DNS query, which every app may make.
        if proto == Proto::Tcp {
            self.admit_tcp(uid, &dst)?;
            if !self.note_start(src, dst, Instant::now()) {
                return Err(io::Error::new(io::ErrorKind::ResourceBusy, "too many flows at once"));
            }
        }
        Ok(u64::from(uid))
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
        if let Ok(uid) = u32::try_from(details.isolation_key) {
            self.flow_failed(uid, host_of(&details.tor_dst.to_string()), Instant::now());
        }
    }

    fn on_established(&self, details: ConnectionDetails<'_>) {
        let started = self.inner.started.lock().unwrap_or_else(PoisonError::into_inner).get(&(details.proxy_src, details.proxy_dst)).copied();
        if let Ok(uid) = u32::try_from(details.isolation_key) {
            self.flow_established(uid, host_of(&details.tor_dst.to_string()), started, Instant::now());
        }
    }

    fn on_socket_close(&self, src: IpEndpoint, dst: IpEndpoint, _err: Option<io::Error>) {
        self.inner.started.lock().unwrap_or_else(PoisonError::into_inner).remove(&(src, dst));
    }

    /// One more DNS question of an app: within its limit or not (see [`RateLimiter`]). A key that is not a uid has no limit.
    fn dns_allow(&self, isolation_key: u64) -> bool {
        let Ok(uid) = u32::try_from(isolation_key) else { return true };
        self.inner.dns_limit.lock().unwrap_or_else(PoisonError::into_inner).allow(uid, Instant::now())
    }

    /// An app of class Tor reaches everything through Tor. An app of class Masked reaches the names on the list through
    /// Tor, unless the user chose to show the real address of that company to that app. Every other app, and every
    /// key that is not a uid, has its queries passed on to its resolver.
    fn dns_decision(&self, isolation_key: u64, name: &str) -> DnsDecision {
        let Ok(uid) = u32::try_from(isolation_key) else { return DnsDecision::Forward };
        let policy = self.inner.policy.read().unwrap_or_else(PoisonError::into_inner);
        match policy.kind_of(uid) {
            Some(Kind::Tor) => DnsDecision::Tor,
            Some(Kind::Masked) => match self.inner.list.classify(name) {
                Some(company) if !policy.overridden(uid, company) => DnsDecision::Tor,
                _ => DnsDecision::Forward,
            },
            Some(Kind::Exempt) | None => DnsDecision::Forward,
        }
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

    /// A scaffolding whose lookup always answers `answer` and whose policy makes every app uid 10000 to 19999 class Tor.
    fn fixed(answer: Option<u32>) -> ExitScaffolding {
        let policy: SharedPolicy = Arc::new(RwLock::new(Policy::default()));
        policy.write().unwrap().set_classes(vec![crate::control::UidClass { start: 10000, end: 19999, kind: Kind::Tor }]).unwrap();
        ExitScaffolding::with_policy(Box::new(move |_, _, _| Ok(answer)), policy, Arc::new(MaskList::default()))
    }

    fn dns_scaffolding(classes: Vec<crate::control::UidClass>) -> (ExitScaffolding, SharedPolicy) {
        let policy: SharedPolicy = Arc::new(RwLock::new(Policy::default()));
        policy.write().unwrap().set_classes(classes).unwrap();
        let list = MaskList::parse(
            "exit-mask-list\t1\nads.example.com\t7\tExample Ads\nexample.net\t8\tExample Net\ncdn.example.net\t0\tprotected\n",
        )
        .unwrap();
        (ExitScaffolding::with_policy(Box::new(|_, _, _| Ok(Some(1))), policy.clone(), Arc::new(list)), policy)
    }

    fn class(start: u32, end: u32, kind: Kind) -> crate::control::UidClass {
        crate::control::UidClass { start, end, kind }
    }

    #[test]
    fn an_app_of_class_tor_resolves_every_name_through_tor() {
        let (s, _) = dns_scaffolding(vec![class(10231, 10231, Kind::Tor)]);
        for name in ["www.example.org.", "ads.example.com.", "cdn.example.net.", "x."] {
            assert_eq!(s.dns_decision(10231, name), DnsDecision::Tor, "{name}");
        }
    }

    #[test]
    fn an_app_of_class_masked_resolves_only_listed_names_through_tor() {
        let (s, _) = dns_scaffolding(vec![class(10000, 19999, Kind::Masked)]);
        assert_eq!(s.dns_decision(10100, "ads.example.com."), DnsDecision::Tor);
        assert_eq!(s.dns_decision(10100, "Sub.Example.NET."), DnsDecision::Tor);
        assert_eq!(s.dns_decision(10100, "www.example.org."), DnsDecision::Forward);
        assert_eq!(s.dns_decision(10100, "cdn.example.net."), DnsDecision::Forward, "a protected row shields");
        assert_eq!(s.dns_decision(10100, "example.com."), DnsDecision::Forward, "the parent of a listed domain");
        assert_eq!(s.dns_decision(10100, "not a name"), DnsDecision::Forward);
    }

    #[test]
    fn a_company_the_user_chose_to_show_the_address_to_is_passed_on_for_that_app_only() {
        let (s, policy) = dns_scaffolding(vec![class(10000, 19999, Kind::Masked)]);
        policy.write().unwrap().set_overrides(10100, vec![7]).unwrap();
        assert_eq!(s.dns_decision(10100, "ads.example.com."), DnsDecision::Forward, "company 7 for app 10100");
        assert_eq!(s.dns_decision(10100, "www.example.net."), DnsDecision::Tor, "another company of the same app");
        assert_eq!(s.dns_decision(10101, "ads.example.com."), DnsDecision::Tor, "the same company for another app");
    }

    #[test]
    fn exempt_unclassified_and_foreign_keys_are_passed_on() {
        let (s, _) = dns_scaffolding(vec![class(10000, 10099, Kind::Exempt), class(10200, 10299, Kind::Masked)]);
        assert_eq!(s.dns_decision(10050, "ads.example.com."), DnsDecision::Forward, "exempt");
        assert_eq!(s.dns_decision(10150, "ads.example.com."), DnsDecision::Forward, "no class");
        assert_eq!(s.dns_decision(1000, "ads.example.com."), DnsDecision::Forward, "a system uid");
        assert_eq!(s.dns_decision(u64::from(u32::MAX) + 1, "ads.example.com."), DnsDecision::Forward, "a key that is no uid");
        assert_eq!(ExitScaffolding::new(Box::new(|_, _, _| Ok(None))).dns_decision(10231, "ads.example.com."), DnsDecision::Forward);
    }

    #[test]
    fn a_new_policy_changes_the_next_decision() {
        let (s, policy) = dns_scaffolding(vec![]);
        assert_eq!(s.dns_decision(10231, "www.example.org."), DnsDecision::Forward);
        policy.write().unwrap().set_classes(vec![class(10231, 10231, Kind::Tor)]).unwrap();
        assert_eq!(s.dns_decision(10231, "www.example.org."), DnsDecision::Tor);
        policy.write().unwrap().set_classes(vec![]).unwrap();
        assert_eq!(s.dns_decision(10231, "www.example.org."), DnsDecision::Forward);
    }

    fn v6(a: [u16; 8], port: u16) -> IpEndpoint {
        IpEndpoint::new(IpAddress::Ipv6(Ipv6Addr::from(a)), port)
    }

    fn tcp_to(s: &ExitScaffolding, dst: IpEndpoint) -> io::Result<u64> {
        s.isolate(v4([169, 254, 42, 127], 4000), dst, 6)
    }

    fn ladder_scaffolding() -> ExitScaffolding {
        let list = MaskList::parse("exit-mask-list\t1\nads.example.com\t7\tExample Ads\n").unwrap();
        ExitScaffolding::with_policy(Box::new(|_, _, _| Ok(Some(10100))), Arc::new(RwLock::new(Policy::default())), Arc::new(list))
    }

    #[test]
    fn three_failed_connections_to_a_listed_host_are_reported_for_its_company() {
        let s = ladder_scaffolding();
        let now = Instant::now();
        s.flow_failed(10100, "ads.example.com", now);
        s.flow_failed(10100, "sub.ads.example.com", now);
        assert!(s.due_failures(16).is_empty());
        s.flow_failed(10100, "ADS.example.com", now);
        assert_eq!(s.due_failures(16), vec![Due { uid: 10100, company: 7, count: 3 }]);
        assert!(s.due_failures(16).is_empty());
    }

    #[test]
    fn failures_of_unlisted_hosts_are_counted_for_company_zero_and_per_app() {
        let s = ladder_scaffolding();
        let now = Instant::now();
        for host in ["a.example.org", "b.example.org", "1.2.3.4"] {
            s.flow_failed(10100, host, now);
        }
        s.flow_failed(10101, "a.example.org", now);
        assert_eq!(s.due_failures(16), vec![Due { uid: 10100, company: 0, count: 3 }]);
    }

    #[test]
    fn a_connection_is_slow_from_five_seconds_and_not_before() {
        let s = ladder_scaffolding();
        let t = Instant::now();
        let ms = std::time::Duration::from_millis;
        s.flow_established(10100, "ads.example.com", Some(t), t + ms(4999));
        assert!(s.due_failures(16).is_empty(), "4.999 s is quick");
        s.flow_established(10100, "ads.example.com", None, t + ms(60_000));
        assert!(s.due_failures(16).is_empty(), "a flow whose start is unknown is not timed");
        s.flow_established(10100, "ads.example.com", Some(t), t + ms(5000));
        assert_eq!(s.due_failures(16), vec![Due { uid: 10100, company: 7, count: 1 }]);
    }

    #[test]
    fn the_start_of_a_flow_is_remembered_until_it_closes_and_the_table_is_bounded() {
        let s = ladder_scaffolding();
        let (a, b) = (v4([10, 0, 0, 2], 1000), v4([198, 18, 0, 1], 443));
        s.note_start(a, b, Instant::now());
        assert!(s.inner.started.lock().unwrap().contains_key(&(a, b)));
        s.on_socket_close(a, b, None);
        assert!(s.inner.started.lock().unwrap().is_empty());
        for port in 0..MAX_FLOWS as u16 {
            assert!(s.note_start(v4([10, 0, 0, 2], port), b, Instant::now()), "flow {port}");
        }
        assert!(!s.note_start(v4([10, 0, 1, 2], 1), b, Instant::now()), "the 1025th flow has no room");
        assert!(s.note_start(v4([10, 0, 0, 2], 7), b, Instant::now()), "a flow that is already counted is not a new one");
        assert_eq!(s.inner.started.lock().unwrap().len(), MAX_FLOWS);
        s.on_socket_close(v4([10, 0, 0, 2], 3), b, None);
        assert!(s.note_start(v4([10, 0, 1, 2], 1), b, Instant::now()), "a closed flow made room");
    }

    #[test]
    fn past_the_flow_limit_a_tcp_flow_is_refused_as_busy_until_one_closes() {
        let (_, policy) = dns_scaffolding(vec![class(10000, 19999, Kind::Tor)]);
        let s = ExitScaffolding::with_policy(Box::new(|_, _, _| Ok(Some(10100))), policy, Arc::new(MaskList::default()));
        let dst = v4([93, 184, 216, 34], 443);
        for port in 0..MAX_FLOWS as u16 {
            assert!(s.isolate(v4([169, 254, 42, 127], port), dst, 6).is_ok(), "flow {port}");
        }
        let e = s.isolate(v4([169, 254, 43, 127], 1), dst, 6).unwrap_err();
        assert_eq!(e.kind(), io::ErrorKind::ResourceBusy);
        // A DNS query is not a flow and is not held back.
        assert!(s.isolate(v4([169, 254, 43, 127], 2), v4([192, 168, 1, 1], 53), 17).is_ok());
        s.on_socket_close(v4([169, 254, 42, 127], 5), dst, None);
        assert!(s.isolate(v4([169, 254, 43, 127], 1), dst, 6).is_ok());
    }

    #[test]
    fn an_app_may_ask_a_hundred_dns_questions_a_second_and_another_app_is_not_held_back() {
        let s = fixed(Some(10100));
        for i in 0..crate::ratelimit::PER_SECOND {
            assert!(s.dns_allow(10100), "question {i}");
        }
        assert!(!s.dns_allow(10100));
        assert!(s.dns_allow(10101));
        assert!(s.dns_allow(u64::from(u32::MAX) + 1), "a key that is no uid has no limit");
    }

    #[test]
    fn refreshes_waiting_for_the_tunnel_are_bounded() {
        use futures::{FutureExt, StreamExt};
        let s = fixed(Some(1));
        let mut stream = Box::into_pin(TunnelScaffolding::command_stream(&s).unwrap());
        for _ in 0..COMMAND_QUEUE + 100 {
            s.refresh_all();
        }
        s.refresh_app(10231);
        let mut n = 0;
        while let Some(Some(_)) = stream.next().now_or_never() {
            n += 1;
            if n > COMMAND_QUEUE + 200 {
                break;
            }
        }
        assert_eq!(n, COMMAND_QUEUE);
    }

    #[test]
    fn a_tcp_flow_that_is_allowed_is_timed_and_one_that_is_refused_is_not() {
        let (_, policy) = dns_scaffolding(vec![class(10000, 19999, Kind::Masked)]);
        let s = ExitScaffolding::with_policy(Box::new(|_, _, _| Ok(Some(10100))), policy, Arc::new(MaskList::default()));
        let ok = (v4([169, 254, 42, 127], 4000), v4([198, 18, 0, 5], 443));
        let refused = (v4([169, 254, 42, 127], 4001), v4([93, 184, 216, 34], 443));
        assert!(s.isolate(ok.0, ok.1, 6).is_ok());
        assert!(s.isolate(refused.0, refused.1, 6).is_err());
        let m = s.inner.started.lock().unwrap();
        assert!(m.contains_key(&ok) && !m.contains_key(&refused));
    }

    #[test]
    fn an_app_of_class_tor_may_reach_anything_through_tor() {
        let (s, _) = dns_scaffolding(vec![class(10231, 10231, Kind::Tor)]);
        let s = ExitScaffolding::with_policy(Box::new(|_, _, _| Ok(Some(10231))), s.inner.policy.clone(), s.inner.list.clone());
        assert_eq!(tcp_to(&s, v4([93, 184, 216, 34], 443)).unwrap(), 10231);
        assert_eq!(tcp_to(&s, v4([198, 18, 4, 4], 443)).unwrap(), 10231);
        assert_eq!(tcp_to(&s, v6([0x2606, 0x4700, 0, 0, 0, 0, 0, 1111], 443)).unwrap(), 10231);
    }

    #[test]
    fn an_app_of_class_masked_may_reach_only_the_fake_addresses() {
        let (_, policy) = dns_scaffolding(vec![class(10000, 19999, Kind::Masked)]);
        let s = ExitScaffolding::with_policy(Box::new(|_, _, _| Ok(Some(10100))), policy, Arc::new(MaskList::default()));
        assert_eq!(tcp_to(&s, v4([198, 18, 0, 1], 443)).unwrap(), 10100);
        assert_eq!(tcp_to(&s, v4([198, 19, 255, 254], 443)).unwrap(), 10100);
        assert_eq!(tcp_to(&s, v6([0xfd66, 0x7265, 0x6568, 0x8000, 1, 2, 3, 4], 443)).unwrap(), 10100);
        for stray in [
            v4([93, 184, 216, 34], 443),
            v4([198, 17, 255, 255], 443),
            v4([198, 20, 0, 0], 443),
            v4([169, 254, 42, 53], 53),
            v4([192, 168, 1, 1], 53),
            v6([0xfd66, 0x7265, 0x6568, 0x7fff, 0, 0, 0, 1], 443),
            v6([0x2606, 0x4700, 0, 0, 0, 0, 0, 1111], 443),
        ] {
            let e = tcp_to(&s, stray).unwrap_err();
            assert_eq!(e.kind(), io::ErrorKind::PermissionDenied, "{stray}");
        }
    }

    #[test]
    fn an_exempt_app_an_app_without_a_class_and_an_unknown_uid_may_reach_nothing() {
        let (_, policy) = dns_scaffolding(vec![class(10000, 10099, Kind::Exempt), class(10200, 10299, Kind::Tor)]);
        for uid in [10050u32, 10150, 1000, 0, 20000] {
            let s = ExitScaffolding::with_policy(Box::new(move |_, _, _| Ok(Some(uid))), policy.clone(), Arc::new(MaskList::default()));
            for dst in [v4([93, 184, 216, 34], 443), v4([198, 18, 0, 1], 443)] {
                assert_eq!(tcp_to(&s, dst).unwrap_err().kind(), io::ErrorKind::PermissionDenied, "uid {uid}");
            }
        }
    }

    #[test]
    fn a_dns_query_of_any_app_is_attributed_whatever_its_class() {
        let (_, policy) = dns_scaffolding(vec![class(10050, 10050, Kind::Exempt)]);
        for uid in [10050u32, 10150, 0] {
            let s = ExitScaffolding::with_policy(Box::new(move |_, _, _| Ok(Some(uid))), policy.clone(), Arc::new(MaskList::default()));
            assert_eq!(s.isolate(v4([169, 254, 42, 127], 5000), v4([192, 168, 1, 1], 53), 17).unwrap(), u64::from(uid));
        }
    }

    #[test]
    fn the_class_of_an_app_is_looked_up_when_the_flow_arrives() {
        let (_, policy) = dns_scaffolding(vec![]);
        let s = ExitScaffolding::with_policy(Box::new(|_, _, _| Ok(Some(10231))), policy.clone(), Arc::new(MaskList::default()));
        assert!(tcp_to(&s, v4([93, 184, 216, 34], 443)).is_err());
        policy.write().unwrap().set_classes(vec![class(10231, 10231, Kind::Tor)]).unwrap();
        assert!(tcp_to(&s, v4([93, 184, 216, 34], 443)).is_ok());
    }

    #[test]
    fn a_flow_with_an_owner_is_isolated_by_its_uid() {
        let s = fixed(Some(10231));
        assert_eq!(s.isolate(v4([10, 0, 0, 2], 4000), v4([93, 184, 216, 34], 443), 6).unwrap(), 10231);
        assert_eq!(s.isolate(v4([10, 0, 0, 2], 4000), v4([93, 184, 216, 34], 443), 17).unwrap(), 10231);
    }

    #[test]
    fn uid_zero_is_an_owner_and_a_tcp_flow_of_it_is_not_allowed() {
        let s = fixed(Some(0));
        // Not "no owner" (NotFound): the owner is root, which no class covers.
        let e = s.isolate(v4([10, 0, 0, 2], 1), v4([1, 1, 1, 1], 1), 6).unwrap_err();
        assert_eq!(e.kind(), io::ErrorKind::PermissionDenied);
        // A DNS query of uid 0 is attributed to it, as for any app.
        assert_eq!(s.isolate(v4([10, 0, 0, 2], 1), v4([1, 1, 1, 1], 53), 17).unwrap(), 0);
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
        let policy: SharedPolicy = Arc::new(RwLock::new(Policy::default()));
        policy.write().unwrap().set_classes(vec![crate::control::UidClass { start: 10000, end: 19999, kind: Kind::Tor }]).unwrap();
        let s = ExitScaffolding::with_policy(
            Box::new(move |p, l, r| {
                log.lock().unwrap().push((p, l, r));
                Ok(Some(10231))
            }),
            policy,
            Arc::new(MaskList::default()),
        );
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
