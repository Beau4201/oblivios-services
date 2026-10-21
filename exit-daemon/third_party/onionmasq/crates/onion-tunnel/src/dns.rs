//! Implementing a DNS resolver for proxied Tor traffic.
//!
//! TODO: This entire module reached a level of complexity where it is no longer
//! really pleasent to work with ...  Functions returning [`Option`] where they
//! should return a [`Result`] instead, methods that do not need a reference
//! to `self`, a function named `decode` that not just decodes a query but also
//! builds a reply already, ...  At some point, a larger refactoring is
//! definitely required.

use std::collections::HashMap;
#[cfg(feature = "experimental-udp-tunnel")]
use std::net::IpAddr;
use std::net::{Ipv4Addr, Ipv6Addr, SocketAddr};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use crate::errors::TunnelResult;
use crate::runtime::OnionTunnelArtiRuntime;
#[cfg(feature = "experimental-udp-tunnel")]
use crate::udp_tunnel::UdpPrebuildRequest;
use arti_client::TorClient;
#[cfg(feature = "experimental-udp-tunnel")]
use arti_client::{IsolationToken, StreamPrefs};
use bytes::Bytes;
use cached::{Cached, TimedCache};
use dns_message_parser::question::QType;
use dns_message_parser::rr::RR;
use dns_message_parser::RCode;
use futures::stream::StreamExt;
use rand::Rng;
use safelog::sensitive;
use smoltcp::socket::udp;
use smoltcp::socket::udp::UdpMetadata;
use smoltcp::wire::IpEndpoint;
use smoltcp::{
    socket::udp::PacketBuffer as UdpSocketBuffer, socket::udp::PacketMetadata as UdpPacketMetadata,
    wire::IpAddress, wire::Ipv4Address, wire::Ipv6Address,
};
use tokio::sync::{mpsc, Semaphore};
use tokio::time::timeout;
use tracing::{info, trace, warn};

use crate::scaffolding::DnsDecision;
use crate::socket::UdpSocket;
use crate::{OnionTunnel, TunnelScaffolding};

pub(crate) type LockedDnsCookies = Arc<Mutex<DnsCookies>>;

#[cfg(feature = "experimental-udp-tunnel")]
type ResolverResult = Result<Vec<IpAddr>, arti_client::Error>;

/// A mapping between dispensed fake IP addresses and hostnames.
///
/// This mapping is specific to a given isolation key.
/// Real IPs are optional, and obtained asynchronously.
/// For UDP tunneling we must convert between real and fake IPs.
///
struct IsolatedCookies {
    #[cfg(feature = "experimental-udp-tunnel")]
    isolation_token: IsolationToken,
    map_v4: TimedCache<Ipv4Address, String>,
    map_v6: TimedCache<Ipv6Address, String>,
    /// How many fake IPv4 addresses one isolation key may hold at once.
    max_v4: usize,
    #[cfg(feature = "experimental-udp-tunnel")]
    resolver_map: HashMap<String, ResolverResult>,
    #[cfg(feature = "experimental-udp-tunnel")]
    resolver_send: mpsc::Sender<(String, ResolverResult)>,
    #[cfg(feature = "experimental-udp-tunnel")]
    resolver_recv: mpsc::Receiver<(String, ResolverResult)>,
}

/// FreeholdOS patch 0004: the fake addresses. `198.18.0.0/15` (the benchmark range of RFC 2544, never routed on the
/// internet and not used by a LAN) and the upper half of the unique-local `fd66:7265:6568::/48`. One steering rule per
/// family sends exactly these into the tunnel; the exit daemon checks its rule constants against these.
pub const FAKE_V4_PREFIX: [u8; 2] = [198, 18];
pub const FAKE_V6_PREFIX: [u8; 6] = [0xfd, 0x66, 0x72, 0x65, 0x65, 0x68];
/// A little under the 2^17 addresses of the /15, so that the search for a free one ends quickly.
const MAX_FAKE_V4: usize = 131_000;
/// The most forwarded queries in flight, and the room for their replies on the way back.
const MAX_FORWARDS: usize = 64;
const FORWARD_QUEUE: usize = MAX_FORWARDS;
/// How long a forwarded query waits for the resolver, and the largest reply it takes (EDNS0 allows 4096).
const FORWARD_WAIT: Duration = Duration::from_secs(2);
const MAX_UDP_DNS: usize = 4096;
/// Datagrams the DNS listener holds while the tunnel is busy.
const UDP_QUEUE: usize = 16;

/// True for an address in one of the fake ranges.
pub fn is_fake_address(addr: &IpAddress) -> bool {
    match addr {
        IpAddress::Ipv4(a) => {
            let o = a.octets();
            o[0] == FAKE_V4_PREFIX[0] && (o[1] & 0xfe) == FAKE_V4_PREFIX[1]
        }
        IpAddress::Ipv6(a) => {
            let o = a.octets();
            o[..6] == FAKE_V6_PREFIX && o[6] & 0x80 != 0
        }
    }
}

/// What to do with a query once the owner is known (see [`route_query`]).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Route {
    /// Answer with fake addresses and carry the connections through Tor.
    Tor,
    /// Pass the query on to the resolver the app asked.
    Forward,
    /// Answer with REFUSED: a query the daemon will neither resolve through Tor nor forward.
    Refuse,
    /// No answer: a response, or a datagram that is not a query.
    Ignore,
}

/// Decides what to do with a query. Only a query whose questions all get the same decision is acted on: a query that mixes
/// a name for Tor with a name for the resolver is refused rather than sent one way, because either way would take one of
/// the names where it must not go. A message with the response bit set is dropped (answering it could start a loop).
pub(crate) fn route_query(query: &dns_message_parser::Dns, decide: impl Fn(&str) -> DnsDecision) -> Route {
    if query.flags.qr {
        return Route::Ignore;
    }
    if query.flags.opcode != dns_message_parser::Opcode::Query || query.questions.is_empty() {
        return Route::Refuse;
    }
    let (mut tor, mut forward) = (false, false);
    for q in &query.questions {
        match decide(&q.domain_name.to_string()) {
            DnsDecision::Tor => tor = true,
            DnsDecision::Forward => forward = true,
        }
    }
    match (tor, forward) {
        (true, false) => Route::Tor,
        (false, true) => Route::Forward,
        _ => Route::Refuse,
    }
}

/// The socket address a forwarded query goes to, or `None` for an address that is not a resolver of the network: loopback,
/// unspecified, multicast, broadcast, the link-local IPv6 range (it needs an interface that the daemon does not know) and
/// the tunnel's own `169.254.42.0/24` (the daemon's DNS address among them, which would be a query to itself).
fn forward_target(addr: IpAddress) -> Option<SocketAddr> {
    let ip: std::net::IpAddr = addr.into();
    let usable = match ip {
        std::net::IpAddr::V4(a) => {
            !(a.is_loopback() || a.is_unspecified() || a.is_multicast() || a.is_broadcast() || a.octets()[..3] == [169, 254, 42])
        }
        std::net::IpAddr::V6(a) => {
            !(a.is_loopback() || a.is_unspecified() || a.is_multicast() || (a.segments()[0] & 0xffc0) == 0xfe80)
        }
    };
    usable.then(|| SocketAddr::new(ip, 53))
}

/// Sends `query` to `target` from a new socket of the daemon (an address on the network the app would have used, since the
/// daemon's own uid is outside every steering rule) and returns the first reply that has the query's id, within `wait`.
/// A reply longer than [`MAX_UDP_DNS`] is an error.
async fn forward_udp(target: SocketAddr, query: &[u8], wait: Duration) -> std::io::Result<Vec<u8>> {
    use std::io::{Error, ErrorKind};
    let id = query.get(..2).ok_or_else(|| Error::new(ErrorKind::InvalidInput, "query shorter than a header"))?;
    let bind: SocketAddr = if target.is_ipv4() { (Ipv4Addr::UNSPECIFIED, 0).into() } else { (Ipv6Addr::UNSPECIFIED, 0).into() };
    let socket = tokio::net::UdpSocket::bind(bind).await?;
    socket.connect(target).await?;
    socket.send(query).await?;
    timeout(wait, async {
        let mut buf = vec![0u8; MAX_UDP_DNS + 1];
        loop {
            let n = socket.recv(&mut buf).await?;
            if n > MAX_UDP_DNS {
                return Err(Error::new(ErrorKind::InvalidData, "DNS reply too large"));
            }
            // A datagram with another id is not the answer to this query; the socket is connected to the resolver, so
            // only a forged one can have it.
            if buf[..n].get(..2) == Some(id) {
                buf.truncate(n);
                return Ok(buf);
            }
        }
    })
    .await
    .map_err(|_| Error::new(ErrorKind::TimedOut, "no DNS reply"))?
}

/// The time to live value returned in DNS responses (in seconds).
const TIME_TO_LIVE_IN_DNS: u64 = 60;

/// The seconds to live of a value in the actual cache.
///
/// This should be larger than [`TIME_TO_LIVE_IN_DNS`] because otherwise we
/// could get a weird race condition.
const SECONDS_TO_LIVE_IN_CACHE: u64 = 70;

/// The amount in seconds between two flushes of the fake address caches (upstream: also the length of a rate limit
/// window; FreeholdOS patch 0004 limits each app on its own instead, see `TunnelScaffolding::dns_allow`).
const RATE_LIMIT_WINDOW: u64 = 180;


// Static assertion for TTL_DNS < TTL_CACHE < REGULAR_CLEANUP.
static_assertions::const_assert!(TIME_TO_LIVE_IN_DNS < SECONDS_TO_LIVE_IN_CACHE);
static_assertions::const_assert!(SECONDS_TO_LIVE_IN_CACHE < RATE_LIMIT_WINDOW);
static_assertions::const_assert!(TIME_TO_LIVE_IN_DNS <= 4294967295);

impl IsolatedCookies {
    fn new() -> Self {
        // Arbitrary small capacity; could be as low as 1, but larger values
        // could be a slight optimization.
        #[cfg(feature = "experimental-udp-tunnel")]
        let (resolver_send, resolver_recv) = mpsc::channel(16);
        Self {
            #[cfg(feature = "experimental-udp-tunnel")]
            isolation_token: IsolationToken::new(),
            map_v4: TimedCache::with_lifespan(Duration::from_secs(SECONDS_TO_LIVE_IN_CACHE)),
            map_v6: TimedCache::with_lifespan(Duration::from_secs(SECONDS_TO_LIVE_IN_CACHE)),
            max_v4: MAX_FAKE_V4,
            #[cfg(feature = "experimental-udp-tunnel")]
            resolver_map: Default::default(),
            #[cfg(feature = "experimental-udp-tunnel")]
            resolver_send,
            #[cfg(feature = "experimental-udp-tunnel")]
            resolver_recv,
        }
    }

    fn canonicalize_hostname(hostname: &mut String) {
        // TODO: This is incomplete
        if hostname.ends_with('.') {
            hostname.pop();
        }
    }

    /// Test whether an IP is in the correct range to possibly be an output from make_for_v4/make_for_v6
    #[cfg(feature = "experimental-udp-tunnel")]
    fn ip_in_randomized_range(addr: &IpAddress) -> bool {
        is_fake_address(addr)
    }

    /// Generate a random IPv4 address in the 198.18.0.0/15 subnet (FreeholdOS patch 0004; upstream: 10.0.0.0/8).
    ///
    /// Returns the IPv4 address or [`None`], if the address space is exhausteed.
    fn make_for_v4<S: TunnelScaffolding>(
        &mut self,
        #[allow(unused)] arti: Option<&Arc<TorClient<OnionTunnelArtiRuntime<S>>>>,
        mut hostname: String,
    ) -> Option<Ipv4Address> {
        Self::canonicalize_hostname(&mut hostname);

        // Check if we've already generated one.
        // Technically, we could check if we have already generated an IP for
        // the hostname, but that would involve to query the entire cache.
        // Instead, just generate a new one.

        #[cfg(feature = "experimental-udp-tunnel")]
        if let Some(arti) = arti {
            self.begin_resolve(arti.clone(), hostname.clone());
        }

        // FreeholdOS patch 0004: a random IPv4 address in 198.18.0.0/15 (the benchmark range of RFC 2544, never routed on
        // the internet and not used by a LAN) instead of 10.0.0.0/8. A full range is refused, not a panic.
        if self.map_v4.cache_size() >= self.max_v4 {
            return None;
        }
        let ret = loop {
            let mut r = [0u8; 3];
            rand::rng().fill_bytes(&mut r);
            /* TODO MSRV 1.91: when ip_from is stable, switch to
            let candidate = Ipv4Address::from_octets(...);
            */
            let candidate = Ipv4Address::from([FAKE_V4_PREFIX[0], FAKE_V4_PREFIX[1] | (r[0] & 1), r[1], r[2]]);
            if self.map_v4.cache_get(&candidate).is_none() {
                break candidate;
            }
        };

        self.map_v4.cache_set(ret, hostname);
        Some(ret)
    }

    fn make_for_v6<S: TunnelScaffolding>(
        &mut self,
        #[allow(unused)] arti: Option<&Arc<TorClient<OnionTunnelArtiRuntime<S>>>>,
        mut hostname: String,
    ) -> Option<Ipv6Address> {
        Self::canonicalize_hostname(&mut hostname);

        // Check if we've already generated one.
        // Technically, we could check if we have already generated an IP for
        // the hostname, but that would involve to query the entire cache.
        // Instead, just generate a new one.

        #[cfg(feature = "experimental-udp-tunnel")]
        if let Some(arti) = arti {
            self.begin_resolve(arti.clone(), hostname.clone());
        }

        // FreeholdOS patch 0004: a random IPv6 address in the upper half of the unique-local /48 fd66:7265:6568::/48
        // instead of fec0::/10, so that one rule can steer exactly these addresses into the tunnel.
        let ret = loop {
            let mut octets = [0u8; 16];
            octets[..6].copy_from_slice(&FAKE_V6_PREFIX);
            rand::rng().fill_bytes(&mut octets[6..]);
            octets[6] |= 0x80;
            /* TODO MSRV 1.91: when ip_from is stable, switch to
            let candidate = Ipv6Addr::from_octets(octets);
             */
            let candidate = Ipv6Addr::from(octets);
            if self.map_v6.cache_get(&candidate).is_none() {
                break candidate;
            }
        };

        self.map_v6.cache_set(ret, hostname);
        Some(ret)
    }

    /// Resolve a hostname to ResolverResult, using a TorClient, without interacting with the cache
    #[cfg(feature = "experimental-udp-tunnel")]
    async fn uncached_resolve<S: TunnelScaffolding>(
        arti: &TorClient<OnionTunnelArtiRuntime<S>>,
        isolation: &IsolationToken,
        hostname: &str,
    ) -> ResolverResult {
        use safelog::sensitive;

        trace!("Asynchronously resolving {:?}", sensitive(hostname));
        let mut stream_prefs = StreamPrefs::new();
        // TODO: Restrict the country code for DNS?
        stream_prefs.set_isolation(*isolation);
        let result = arti.resolve_with_prefs(hostname, &stream_prefs).await;
        trace!(
            "Asynchronously resolved {:?} -> {:?}",
            sensitive(hostname),
            sensitive(&result)
        );
        result
    }

    /// Begin asynchronously looking up the real IP addresses for a hostname
    #[cfg(feature = "experimental-udp-tunnel")]
    fn begin_resolve<S: TunnelScaffolding>(
        &self,
        arti: Arc<TorClient<OnionTunnelArtiRuntime<S>>>,
        hostname: String,
    ) {
        let sender = self.resolver_send.clone();
        let isolation = self.isolation_token;
        tokio::spawn(async move {
            let result = Self::uncached_resolve(&arti, &isolation, &hostname).await;
            let _ = sender.send((hostname, result)).await;
        });
    }

    /// Return the resolver result for a particular hostname, if it has finished
    #[cfg(feature = "experimental-udp-tunnel")]
    fn resolve_result(&mut self, hostname: &str) -> Option<&ResolverResult> {
        while let Ok((hostname, result)) = self.resolver_recv.try_recv() {
            self.resolver_map.insert(hostname, result);
        }
        self.resolver_map.get(hostname)
    }

    fn lookup_address(&mut self, addr: &IpAddress) -> Option<String> {
        match addr {
            IpAddress::Ipv4(a) => self.map_v4.cache_get(a).cloned(),
            IpAddress::Ipv6(a) => self.map_v6.cache_get(a).cloned(),
        }
    }
}

pub(crate) struct DnsCookies {
    /// Per-isolation-key isolated store of cookies.
    cookies: HashMap<Option<u64>, IsolatedCookies>,
}

impl DnsCookies {
    fn new() -> Self {
        Self {
            cookies: HashMap::new(),
        }
    }

    fn make_for_v4<S: TunnelScaffolding>(
        &mut self,
        arti: Option<&Arc<TorClient<OnionTunnelArtiRuntime<S>>>>,
        isolation_key: Option<u64>,
        hostname: String,
    ) -> Option<Ipv4Address> {
        self.cookies
            .entry(isolation_key)
            .or_insert_with(IsolatedCookies::new)
            .make_for_v4(arti, hostname)
    }

    fn make_for_v6<S: TunnelScaffolding>(
        &mut self,
        arti: Option<&Arc<TorClient<OnionTunnelArtiRuntime<S>>>>,
        isolation_key: Option<u64>,
        hostname: String,
    ) -> Option<Ipv6Address> {
        self.cookies
            .entry(isolation_key)
            .or_insert_with(IsolatedCookies::new)
            .make_for_v6(arti, hostname)
    }

    pub fn get(&mut self, isolation_key: Option<u64>, addr: &IpAddress) -> Option<String> {
        self.cookies
            .get_mut(&isolation_key)
            .and_then(|x| x.lookup_address(addr))
    }

    /// Return the resolver result for a particular hostname, if it has finished
    #[cfg(feature = "experimental-udp-tunnel")]
    fn resolve_result(
        &mut self,
        isolation_key: Option<u64>,
        hostname: &str,
    ) -> Option<&ResolverResult> {
        self.cookies
            .get_mut(&isolation_key)
            .and_then(|cookies| cookies.resolve_result(hostname))
    }

    /// Return an appropriate Arti IsolationToken corresponding to a DNS cookie isolation key
    ///
    /// Allocates a new token if necessary, remembering the mapping.
    #[cfg(feature = "experimental-udp-tunnel")]
    fn isolation_token(&mut self, isolation_key: Option<u64>) -> &IsolationToken {
        &self
            .cookies
            .entry(isolation_key)
            .or_insert_with(IsolatedCookies::new)
            .isolation_token
    }

    /// Look up the most recent resolver output for a hostname, or wait for one resolution now
    ///
    /// If the resolver has already completed at least once for this hostname and set of isolation
    /// cookies, we return that cached result. Otherwise a single attempt is performed before
    /// returning, and the result of that attempt stored if it completes.
    ///
    #[cfg(feature = "experimental-udp-tunnel")]
    async fn cached_resolve<S: TunnelScaffolding>(
        locked: &LockedDnsCookies,
        arti: &TorClient<OnionTunnelArtiRuntime<S>>,
        isolation_key: Option<u64>,
        hostname: &str,
    ) -> ResolverResult {
        // Take the lock once to check for a cached result, and get the correct Arti stream isolation token
        let (cached, isolation_token) = {
            #[allow(clippy::expect_used)]
            let mut cookies = locked.lock().expect("dns shared data lock");
            (
                cookies.resolve_result(isolation_key, hostname).cloned(),
                cookies.isolation_token(isolation_key).to_owned(),
            )
        };
        match cached {
            Some(cached) => cached,
            None => {
                // Wait for resolve over Tor
                let result =
                    IsolatedCookies::uncached_resolve(arti, &isolation_token, hostname).await;
                // Lock again to store
                #[allow(clippy::expect_used)]
                locked
                    .lock()
                    .expect("dns shared data lock")
                    .cookies
                    .entry(isolation_key)
                    .or_insert_with(IsolatedCookies::new)
                    .resolver_map
                    .insert(hostname.to_string(), result.clone());
                result
            }
        }
    }

    /// Find a real IP address corresponding to an address that might be fake (isolated)
    ///
    /// If the provided address does not look fake, it's returned unmodified.
    /// Always returns an address from the same family as the given fake address.
    ///
    #[cfg(feature = "experimental-udp-tunnel")]
    pub async fn get_resolved<S: TunnelScaffolding>(
        locked: &LockedDnsCookies,
        arti: &TorClient<OnionTunnelArtiRuntime<S>>,
        isolation_key: Option<u64>,
        addr: &IpAddress,
    ) -> Option<IpAddress> {
        if IsolatedCookies::ip_in_randomized_range(addr) {
            #[allow(clippy::expect_used)]
            let hostname = locked
                .lock()
                .expect("dns shared data lock")
                .get(isolation_key, addr);
            match hostname {
                None => None,
                Some(hostname) => {
                    match Self::cached_resolve(locked, arti, isolation_key, &hostname).await {
                        Err(_e) => None,
                        Ok(ip_addrs) => {
                            for answer in ip_addrs {
                                match (addr, answer) {
                                    (IpAddress::Ipv4(_), IpAddr::V4(_)) => {
                                        return Some(answer.into())
                                    }
                                    (IpAddress::Ipv6(_), IpAddr::V6(_)) => {
                                        return Some(answer.into())
                                    }
                                    _ => (),
                                }
                            }
                            None
                        }
                    }
                }
            }
        } else {
            Some(*addr)
        }
    }
}

/// FreeholdOS patch 0004: a reply that a forwarded query got, on its way back to the app.
struct ForwardedReply {
    bytes: Vec<u8>,
    client: IpEndpoint,
    /// The address the app sent the query to: the reply must come from it, or the app's socket drops it.
    from: Option<IpAddress>,
}

pub struct DnsManager<S: TunnelScaffolding> {
    /// One listener for both families: a socket bound to the port alone takes every datagram for port 53.
    listener_udp: UdpSocket,
    cookies: LockedDnsCookies,
    arti: Arc<TorClient<OnionTunnelArtiRuntime<S>>>,
    scaffolding: Arc<S>,
    #[cfg(feature = "experimental-udp-tunnel")]
    udp_prebuild: mpsc::Sender<UdpPrebuildRequest>,
    forward_tx: mpsc::Sender<ForwardedReply>,
    forward_rx: mpsc::Receiver<ForwardedReply>,
    forward_slots: Arc<Semaphore>,
}

impl<S> DnsManager<S>
where
    S: TunnelScaffolding,
{
    /// The IPv4 address the DNS server listens on.
    ///
    /// Uses the 169.254.0.0/16 link-local block.
    const V4_ADDRESS: Ipv4Addr = Ipv4Addr::new(169, 254, 42, 53);
    /// The IPv6 address the DNS server listens on.
    ///
    /// Uses the fe80::/10 link-local block.
    const V6_ADDRESS: Ipv6Addr = Ipv6Addr::new(0xfe80, 0, 0, 0, 0, 0, 0, 0x53);

    pub fn new<D>(tunnel: &OnionTunnel<S, D>) -> TunnelResult<Self> {
        let (forward_tx, forward_rx) = mpsc::channel(FORWARD_QUEUE);
        Ok(Self {
            listener_udp: Self::new_udp_listener(tunnel)?,
            cookies: Arc::new(Mutex::new(DnsCookies::new())),
            arti: tunnel.arti.isolated_client(),
            scaffolding: tunnel.scaffolding.clone(),
            #[cfg(feature = "experimental-udp-tunnel")]
            udp_prebuild: tunnel.udp_tunnel.prebuild_sender().clone(),
            forward_tx,
            forward_rx,
            forward_slots: Arc::new(Semaphore::new(MAX_FORWARDS)),
        })
    }

    /// FreeholdOS patch 0004: a listener for port 53 whatever the destination address is. The apps' resolver sends its
    /// queries to the resolver it was configured with (the Wi-Fi's, or ours on a network of the Tor class), and the
    /// steering rules deliver them here.
    fn new_udp_listener<D>(tunnel: &OnionTunnel<S, D>) -> TunnelResult<UdpSocket> {
        let rx_buffer = UdpSocketBuffer::new(vec![UdpPacketMetadata::EMPTY; UDP_QUEUE], vec![0; 4096 * UDP_QUEUE]);
        let tx_buffer = UdpSocketBuffer::new(vec![UdpPacketMetadata::EMPTY; UDP_QUEUE], vec![0; 4096 * UDP_QUEUE]);

        let socket = udp::Socket::new(rx_buffer, tx_buffer);

        let mut listener = UdpSocket::new(tunnel, socket);
        #[allow(clippy::expect_used)]
        listener
            .bind_any(53)
            .expect("bind error when creating DNS listener?");

        Ok(listener)
    }

    pub(crate) fn cookies(&self) -> Arc<Mutex<DnsCookies>> {
        self.cookies.clone()
    }

    fn make_answer(
        query: &dns_message_parser::Dns,
        rcode: RCode,
        answers: Vec<RR>,
    ) -> dns_message_parser::Dns {
        let flags = dns_message_parser::Flags {
            qr: true,
            opcode: dns_message_parser::Opcode::Query,
            aa: false,
            tc: false,
            rd: true,
            ra: true,
            ad: false,
            cd: false,
            rcode,
        };
        // NOTE: We can't really add authorities or additionals since we don't know
        //       what they are!
        dns_message_parser::Dns {
            id: query.id,
            flags,
            questions: query.questions.clone(),
            answers,
            authorities: vec![],
            additionals: vec![],
        }
    }

    /// The isolation key of a query, or the SERVFAIL answer to give instead when the scaffolding could not attribute it.
    fn attribute(
        query: &dns_message_parser::Dns,
        result: std::io::Result<u64>,
    ) -> Result<u64, dns_message_parser::Dns> {
        result.map_err(|_| Self::make_answer(query, RCode::ServFail, vec![]))
    }

    /// The answer to a query whose names all go through Tor: a fake address for each name, remembered for this app, and
    /// the connection to it is carried through Tor by name.
    fn answer_tor(
        &self,
        query: &dns_message_parser::Dns,
        from: IpEndpoint,
        dest: IpEndpoint,
        isolation_key: u64,
    ) -> dns_message_parser::Dns {
        let _ = (from, dest);
        Self::answer_names(&self.cookies, Some(&self.arti), query, isolation_key)
    }

    /// The fake-address answer for the questions of a query. It reads nothing but its arguments, so a test can run it on
    /// any query without a tunnel.
    fn answer_names(
        cookies: &LockedDnsCookies,
        arti: Option<&Arc<TorClient<OnionTunnelArtiRuntime<S>>>>,
        query: &dns_message_parser::Dns,
        isolation_key: u64,
    ) -> dns_message_parser::Dns {
        let mut answers = vec![];

        for question in query.questions.iter() {
            let name = question.domain_name.to_string();
            let rr = match &question.q_type {
                &QType::A => {
                    #[allow(clippy::expect_used)]
                    let mut cookies = cookies.lock().expect("cookie poisoned");
                    let cookie = match cookies.make_for_v4(arti, Some(isolation_key), name) {
                        Some(cookie) => cookie,
                        // Return a `Refused` in case of an address exhaustion.
                        None => return Self::make_answer(query, RCode::Refused, vec![]),
                    };

                    #[cfg(feature = "experimental-udp-tunnel")]
                    {
                        let _ = self.udp_prebuild.try_send(UdpPrebuildRequest {
                            isolation_key,
                            country_code,
                            address_family:
                                experimental_turn_tcp_client::proto::REQUESTED_FAMILY_IPV4,
                        });
                    }
                    RR::A(dns_message_parser::rr::A {
                        domain_name: question.domain_name.clone(),
                        ttl: TIME_TO_LIVE_IN_DNS as u32,
                        ipv4_addr: cookie,
                    })
                }
                &QType::AAAA => {
                    #[allow(clippy::expect_used)]
                    let mut cookies = cookies.lock().expect("cookie poisoned");
                    let cookie = match cookies.make_for_v6(arti, Some(isolation_key), name) {
                        Some(cookie) => cookie,
                        // Return a `Refused` in case of an address exhaustion.
                        None => return Self::make_answer(query, RCode::Refused, vec![]),
                    };
                    #[cfg(feature = "experimental-udp-tunnel")]
                    {
                        let _ = self.udp_prebuild.try_send(UdpPrebuildRequest {
                            isolation_key,
                            country_code,
                            address_family:
                                experimental_turn_tcp_client::proto::REQUESTED_FAMILY_IPV6,
                        });
                    }
                    RR::AAAA(dns_message_parser::rr::AAAA {
                        domain_name: question.domain_name.clone(),
                        ttl: TIME_TO_LIVE_IN_DNS as u32,
                        ipv6_addr: cookie,
                    })
                }
                &QType::HTTPS | &QType::SVCB => {
                    warn!(
                        "Received DNS query of type {}. The Tor network doesn't support it yet.",
                        question.q_type,
                    );
                    // return "not implemented".
                    return Self::make_answer(query, RCode::NotImp, vec![]);
                }
                // TODO(eta): implement PTR
                x => {
                    warn!(
                        "Received DNS query of type {}, which we don't support yet!",
                        x
                    );
                    // return "not implemented" -- at least it's better than nothing!
                    return Self::make_answer(query, RCode::NotImp, vec![]);
                }
            };
            answers.push(rr);
        }

        Self::make_answer(query, RCode::NoError, answers)
    }


    /// The endpoint the app sent the query to. Smoltcp reports the destination address of every datagram of a socket that
    /// is bound to the port alone; without it the family's own DNS address stands in.
    fn dest_of(meta: &UdpMetadata, from: &IpEndpoint) -> IpEndpoint {
        let addr = meta.local_address.unwrap_or(match from.addr {
            IpAddress::Ipv4(_) => Self::V4_ADDRESS.into(),
            IpAddress::Ipv6(_) => Self::V6_ADDRESS.into(),
        });
        IpEndpoint::new(addr, 53)
    }

    /// What to do with one datagram of the listener.
    fn handle(&mut self, payload: Bytes, meta: UdpMetadata) {
        let from = meta.endpoint;
        let query = match dns_message_parser::Dns::decode(payload.clone()) {
            Ok(q) => q,
            Err(e) => {
                warn!("Unable to parse DNS request: {}", e);
                return;
            }
        };
        trace!("DNS question from {}: {}", sensitive(from), sensitive(&query));
        let dest = Self::dest_of(&meta, &from);
        // FreeholdOS: a query whose owner cannot be found is refused with SERVFAIL (ADR-184, finding F03).
        let key = match Self::attribute(&query, self.scaffolding.isolate(from, dest, 17 /* UDP */)) {
            Ok(key) => key,
            Err(refusal) => {
                trace!("DNS query from {} refused: its owner could not be found", sensitive(from));
                self.reply(&refusal, from, meta.local_address);
                return;
            }
        };
        if !self.scaffolding.dns_allow(key) {
            trace!("DNS query from {} refused: the app asks too fast", sensitive(from));
            self.reply(&Self::make_answer(&query, RCode::ServFail, vec![]), from, meta.local_address);
            return;
        }
        let scaffolding = self.scaffolding.clone();
        match route_query(&query, |name| scaffolding.dns_decision(key, name)) {
            Route::Ignore => trace!("no DNS answer for {}", sensitive(from)),
            Route::Refuse => self.reply(&Self::make_answer(&query, RCode::Refused, vec![]), from, meta.local_address),
            Route::Tor => {
                let answer = self.answer_tor(&query, from, dest, key);
                self.reply(&answer, from, meta.local_address);
            }
            Route::Forward => self.forward(payload, &query, from, meta.local_address),
        }
    }

    /// Passes the query on, unchanged, to the resolver the app asked. A query that cannot be passed on, or finds no room,
    /// gets SERVFAIL at once.
    fn forward(&mut self, payload: Bytes, query: &dns_message_parser::Dns, client: IpEndpoint, from: Option<IpAddress>) {
        let servfail = Self::make_answer(query, RCode::ServFail, vec![]);
        let target = from.and_then(forward_target);
        let permit = self.forward_slots.clone().try_acquire_owned();
        let (Some(target), Ok(permit)) = (target, permit) else {
            self.reply(&servfail, client, from);
            return;
        };
        let tx = self.forward_tx.clone();
        let fallback = servfail.encode().map(|b| b.to_vec()).unwrap_or_default();
        tokio::spawn(async move {
            let bytes = forward_udp(target, &payload, FORWARD_WAIT).await.unwrap_or(fallback);
            if !bytes.is_empty() {
                let _ = tx.send(ForwardedReply { bytes, client, from }).await;
            }
            // Held until the reply is queued, so that at most MAX_FORWARDS replies wait for the loop.
            drop(permit);
        });
    }

    fn reply(&mut self, answer: &dns_message_parser::Dns, client: IpEndpoint, from: Option<IpAddress>) {
        match answer.encode() {
            Ok(v) => self.send_to(&v, client, from),
            Err(e) => warn!("Failed to encode DNS reply: {e}"),
        }
    }

    /// Sends a reply to the app from the address the query was sent to; from any other address the app's socket drops it.
    fn send_to(&mut self, bytes: &[u8], client: IpEndpoint, from: Option<IpAddress>) {
        let mut meta = UdpMetadata::from(client);
        meta.local_address = from;
        if let Err(e) = self.listener_udp.send(bytes, meta) {
            warn!("Failed to send DNS reply: {e:?}");
        }
    }

    pub async fn start(&mut self) {
        info!("Starting DNS manager");

        // FreeholdOS patch 0004: no global limit with a discard phase (see `TunnelScaffolding::dns_allow`); the caches are
        // flushed at the end of every window to save space, as before.
        let mut flush = tokio::time::interval(Duration::from_secs(RATE_LIMIT_WINDOW));
        loop {
            tokio::select! {
                r = self.listener_udp.next() => match r {
                    Some((payload, meta)) => self.handle(payload, meta),
                    None => unreachable!(),
                },
                Some(r) = self.forward_rx.recv() => self.send_to(&r.bytes, r.client, r.from),
                _ = flush.tick() => {
                    if let Ok(mut lock) = self.cookies.lock() {
                        lock.cookies.iter_mut().for_each(|(_, cookies)| {
                            cookies.map_v4.flush();
                            cookies.map_v6.flush();
                        });
                    }
                }
            }
        }
    }
}

#[cfg(test)]
mod test {
    use crate::dns::DnsCookies;
    use smoltcp::wire::{IpAddress, Ipv4Address};

    #[test]
    fn dns_cookie_basics() {
        let mut cookies = DnsCookies::new();

        let hostname_4: String = "test.hostname".into();
        let hostname_6: String = "lol.arpa".into();

        let v4 = IpAddress::Ipv4(
            cookies
                .make_for_v4::<()>(None, Some(0), hostname_4.clone())
                .unwrap(),
        );
        let v6 = IpAddress::Ipv6(
            cookies
                .make_for_v6::<()>(None, Some(0), hostname_6.clone())
                .unwrap(),
        );

        assert_eq!(cookies.get(Some(0), &v4), Some(hostname_4));
        assert_eq!(cookies.get(Some(0), &v6), Some(hostname_6));
        assert_eq!(
            cookies.get(
                Some(0),
                /* TODO MSRV 1.91: when ip_from is stable, switch to
                &IpAddress::Ipv4(Ipv4Address::from_octets([1, 2, 3, 4]))
                */
                &IpAddress::Ipv4(Ipv4Address::from([1, 2, 3, 4]))
            ),
            None
        );
    }

    #[test]
    fn dns_cookie_addresses_are_different() {
        let mut cookies = DnsCookies::new();

        let hostname_4: String = "test.hostname".into();
        let hostname_6: String = "lol.arpa".into();

        let v4 = IpAddress::Ipv4(
            cookies
                .make_for_v4::<()>(None, Some(0), hostname_4.clone())
                .unwrap(),
        );
        let v6 = IpAddress::Ipv6(
            cookies
                .make_for_v6::<()>(None, Some(0), hostname_6.clone())
                .unwrap(),
        );
        let different_v4 = IpAddress::Ipv4(
            cookies
                .make_for_v4::<()>(None, Some(0), hostname_4)
                .unwrap(),
        );
        let different_v6 = IpAddress::Ipv6(
            cookies
                .make_for_v6::<()>(None, Some(0), hostname_6)
                .unwrap(),
        );
        assert_ne!(v4, different_v4);
        assert_ne!(v6, different_v6);
    }

    #[test]
    fn dns_cookies_are_isolated() {
        let mut cookies = DnsCookies::new();

        let hostname_4: String = "test.hostname".into();
        let hostname_6: String = "lol.arpa".into();

        let v4_0 = IpAddress::Ipv4(
            cookies
                .make_for_v4::<()>(None, Some(0), hostname_4.clone())
                .unwrap(),
        );
        let v6_0 = IpAddress::Ipv6(
            cookies
                .make_for_v6::<()>(None, Some(0), hostname_6.clone())
                .unwrap(),
        );

        assert_eq!(cookies.get(Some(0), &v4_0), Some(hostname_4));
        assert_eq!(cookies.get(Some(1), &v4_0), None);
        assert_eq!(cookies.get(Some(0), &v6_0), Some(hostname_6));
        assert_eq!(cookies.get(Some(1), &v6_0), None);
    }

    #[test]
    fn dns_cookies_are_distinct_v4() {
        let mut cookies = DnsCookies::new();
        let addresses = (0..64)
            .map(|x| {
                IpAddress::Ipv4(
                    cookies
                        .make_for_v4::<()>(None, Some(0), format!("random-hostname{x}.arpa"))
                        .unwrap(),
                )
            })
            .collect::<Vec<_>>();

        // Yes, this is silly and inefficient. No, I don't care much.
        for (ia, a) in addresses.iter().enumerate() {
            for (ib, b) in addresses.iter().enumerate() {
                if ia != ib {
                    assert_ne!(a, b);
                }
            }
        }
    }

    #[test]
    fn dns_cookies_are_distinct_v6() {
        let mut cookies = DnsCookies::new();
        let addresses = (0..64)
            .map(|x| {
                IpAddress::Ipv6(
                    cookies
                        .make_for_v6::<()>(None, Some(0), format!("random-hostname{x}.arpa"))
                        .unwrap(),
                )
            })
            .collect::<Vec<_>>();

        // Yes, this is silly and inefficient. No, I don't care much.
        for (ia, a) in addresses.iter().enumerate() {
            for (ib, b) in addresses.iter().enumerate() {
                if ia != ib {
                    assert_ne!(a, b);
                }
            }
        }
    }

    // FreeholdOS: a query whose owner cannot be found is refused, not given a shared key (ADR-184, finding F03).
    #[test]
    fn isolation_failure_is_refused() {
        use crate::dns::DnsManager;
        use dns_message_parser::question::{QClass, QType, Question};
        use dns_message_parser::{Dns, Flags, Opcode, RCode};
        // FreeholdOS: a query whose owner cannot be found is refused with SERVFAIL (ADR-184, finding F03).
        let query = Dns {
            id: 0x4242,
            flags: Flags {
                qr: false,
                opcode: Opcode::Query,
                aa: false,
                tc: false,
                rd: true,
                ra: false,
                ad: false,
                cd: false,
                rcode: RCode::NoError,
            },
            questions: vec![Question {
                domain_name: "example.org.".parse().unwrap(),
                q_class: QClass::IN,
                q_type: QType::A,
            }],
            answers: vec![],
            authorities: vec![],
            additionals: vec![],
        };
        assert_eq!(DnsManager::<()>::attribute(&query, Ok(10231)), Ok(10231));
        assert_eq!(DnsManager::<()>::attribute(&query, Ok(0)), Ok(0));
        for kind in [
            std::io::ErrorKind::NotFound,
            std::io::ErrorKind::InvalidInput,
            std::io::ErrorKind::PermissionDenied,
        ] {
            let answer = DnsManager::<()>::attribute(&query, Err(std::io::Error::from(kind))).unwrap_err();
            assert_eq!(answer.id, 0x4242);
            assert!(answer.flags.qr);
            assert_eq!(answer.flags.rcode, RCode::ServFail);
            assert!(answer.answers.is_empty() && answer.authorities.is_empty() && answer.additionals.is_empty());
            assert_eq!(answer.questions, query.questions);
        }
    }

    // FreeholdOS patch 0004 -------------------------------------------------------------------------------------------

    use crate::dns::{
        forward_target, forward_udp, is_fake_address, route_query, Route, FAKE_V4_PREFIX, FAKE_V6_PREFIX, MAX_UDP_DNS,
    };
    use crate::scaffolding::DnsDecision;
    use dns_message_parser::question::{QClass, QType, Question};
    use dns_message_parser::{Dns, Flags, Opcode, RCode};
    use smoltcp::wire::Ipv6Address;
    use std::net::{Ipv4Addr, SocketAddr};
    use std::time::Duration;

    fn query_of(names: &[&str]) -> Dns {
        Dns {
            id: 0x1234,
            flags: Flags {
                qr: false,
                opcode: Opcode::Query,
                aa: false,
                tc: false,
                rd: true,
                ra: false,
                ad: false,
                cd: false,
                rcode: RCode::NoError,
            },
            questions: names
                .iter()
                .map(|n| Question { domain_name: n.parse().unwrap(), q_class: QClass::IN, q_type: QType::A })
                .collect(),
            answers: vec![],
            authorities: vec![],
            additionals: vec![],
        }
    }

    fn by_name(name: &str) -> DnsDecision {
        if name.contains("ads.") {
            DnsDecision::Tor
        } else {
            DnsDecision::Forward
        }
    }

    #[test]
    fn a_query_goes_the_way_all_its_questions_go() {
        assert_eq!(route_query(&query_of(&["ads.example.com."]), by_name), Route::Tor);
        assert_eq!(route_query(&query_of(&["www.example.com."]), by_name), Route::Forward);
        assert_eq!(route_query(&query_of(&["ads.a.com.", "ads.b.com."]), by_name), Route::Tor);
        assert_eq!(route_query(&query_of(&["www.a.com.", "www.b.com."]), by_name), Route::Forward);
    }

    #[test]
    fn a_query_that_mixes_the_two_is_refused_not_sent_one_way() {
        assert_eq!(route_query(&query_of(&["ads.a.com.", "www.b.com."]), by_name), Route::Refuse);
        assert_eq!(route_query(&query_of(&["www.b.com.", "ads.a.com."]), by_name), Route::Refuse);
    }

    #[test]
    fn a_query_without_questions_or_with_another_opcode_is_refused() {
        assert_eq!(route_query(&query_of(&[]), by_name), Route::Refuse);
        let mut q = query_of(&["ads.a.com."]);
        q.flags.opcode = Opcode::Notify;
        assert_eq!(route_query(&q, by_name), Route::Refuse);
    }

    #[test]
    fn a_response_is_dropped_so_that_two_resolvers_cannot_answer_each_other_for_ever() {
        let mut q = query_of(&["ads.a.com."]);
        q.flags.qr = true;
        assert_eq!(route_query(&q, by_name), Route::Ignore);
        q.flags.opcode = Opcode::Notify;
        assert_eq!(route_query(&q, by_name), Route::Ignore, "the response bit decides first");
    }

    #[test]
    fn the_decision_is_asked_once_per_question_with_the_name_as_written() {
        use std::cell::RefCell;
        let seen = RefCell::new(Vec::new());
        let q = query_of(&["Ads.Example.COM.", "ads.other.com."]);
        let _ = route_query(&q, |n| {
            seen.borrow_mut().push(n.to_string());
            DnsDecision::Tor
        });
        assert_eq!(seen.into_inner(), vec!["Ads.Example.COM.".to_string(), "ads.other.com.".to_string()]);
    }

    #[test]
    fn the_fake_ranges_are_exactly_198_18_15_and_the_upper_half_of_the_unique_local_48() {
        let v4 = |a: [u8; 4]| is_fake_address(&IpAddress::Ipv4(Ipv4Address::from(a)));
        assert!(v4([198, 18, 0, 0]) && v4([198, 18, 255, 255]) && v4([198, 19, 0, 0]) && v4([198, 19, 255, 255]));
        assert!(!v4([198, 17, 255, 255]) && !v4([198, 20, 0, 0]) && !v4([197, 18, 0, 0]) && !v4([199, 18, 0, 0]));
        assert!(!v4([10, 0, 0, 1]) && !v4([192, 168, 1, 1]) && !v4([169, 254, 42, 53]) && !v4([8, 8, 8, 8]));
        let v6 = |a: [u8; 16]| is_fake_address(&IpAddress::Ipv6(Ipv6Address::from(a)));
        let mut a = [0u8; 16];
        a[..6].copy_from_slice(&FAKE_V6_PREFIX);
        a[6] = 0x80;
        assert!(v6(a));
        a[6] = 0xff;
        assert!(v6(a));
        a[6] = 0x7f;
        assert!(!v6(a), "the lower half of the /48 is the tunnel's own");
        a[6] = 0;
        assert!(!v6(a));
        let mut b = [0x80u8; 16];
        b[..6].copy_from_slice(&[0xfd, 0x66, 0x72, 0x65, 0x65, 0x69]);
        assert!(!v6(b), "another /48");
        assert!(!v6([0xfe, 0xc0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1]), "the range upstream used");
        assert_eq!(FAKE_V4_PREFIX, [198, 18]);
    }

    #[test]
    fn fake_addresses_are_inside_the_ranges_and_differ() {
        let mut c = DnsCookies::new();
        for i in 0..500 {
            let a4 = c.make_for_v4::<()>(None, Some(0), format!("h{i}.example")).unwrap();
            assert!(is_fake_address(&IpAddress::Ipv4(a4)), "{a4}");
            let a6 = c.make_for_v6::<()>(None, Some(0), format!("h{i}.example")).unwrap();
            assert!(is_fake_address(&IpAddress::Ipv6(a6)), "{a6}");
        }
        let mut seen4 = std::collections::HashSet::new();
        for i in 0..200 {
            assert!(seen4.insert(c.make_for_v4::<()>(None, Some(1), format!("g{i}.example")).unwrap()));
        }
        // Both halves of the /15 are used.
        let second_octets: std::collections::HashSet<u8> = seen4.iter().map(|a| a.octets()[1]).collect();
        assert_eq!(second_octets, [18u8, 19].into_iter().collect());
    }

    #[test]
    fn a_full_range_is_refused_and_not_a_panic() {
        let mut c = DnsCookies::new();
        c.cookies.entry(Some(0)).or_insert_with(super::IsolatedCookies::new).max_v4 = 3;
        for i in 0..3 {
            assert!(c.make_for_v4::<()>(None, Some(0), format!("h{i}.example")).is_some());
        }
        assert_eq!(c.make_for_v4::<()>(None, Some(0), "one-more.example".into()), None);
        // Another app is not affected.
        assert!(c.make_for_v4::<()>(None, Some(1), "one-more.example".into()).is_some());
    }

    #[test]
    fn only_a_resolver_of_the_network_is_a_target_for_forwarding() {
        let v4 = |a: [u8; 4]| forward_target(IpAddress::Ipv4(Ipv4Address::from(a)));
        assert_eq!(v4([192, 168, 1, 1]), Some(SocketAddr::new(Ipv4Addr::new(192, 168, 1, 1).into(), 53)));
        assert_eq!(v4([8, 8, 8, 8]), Some(SocketAddr::new(Ipv4Addr::new(8, 8, 8, 8).into(), 53)));
        assert_eq!(v4([10, 0, 0, 1]).is_some(), true);
        for bad in [[127, 0, 0, 1], [127, 1, 2, 3], [0, 0, 0, 0], [224, 0, 0, 251], [255, 255, 255, 255], [169, 254, 42, 53], [169, 254, 42, 127], [169, 254, 42, 0]] {
            assert_eq!(v4(bad), None, "{bad:?}");
        }
        assert!(v4([169, 254, 43, 1]).is_some(), "another link-local /24 is an ordinary address");
        let v6 = |s: [u16; 8]| forward_target(IpAddress::Ipv6(Ipv6Address::from(s)));
        assert!(v6([0x2001, 0x4860, 0x4860, 0, 0, 0, 0, 0x8888]).is_some());
        for bad in [[0, 0, 0, 0, 0, 0, 0, 1], [0; 8], [0xff02, 0, 0, 0, 0, 0, 0, 1], [0xfe80, 0, 0, 0, 0, 0, 0, 1], [0xfe80, 0, 0, 0, 0, 0, 0, 0x53], [0xfebf, 0, 0, 0, 0, 0, 0, 1]] {
            assert_eq!(v6(bad), None, "{bad:?}");
        }
        assert!(v6([0xfec0, 0, 0, 0, 0, 0, 0, 1]).is_some(), "site-local is not link-local");
    }

    async fn upstream(reply: impl Fn(&[u8]) -> Vec<Vec<u8>> + Send + 'static) -> SocketAddr {
        let sock = tokio::net::UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let addr = sock.local_addr().unwrap();
        tokio::spawn(async move {
            let mut buf = [0u8; 4096];
            if let Ok((n, peer)) = sock.recv_from(&mut buf).await {
                for r in reply(&buf[..n]) {
                    let _ = sock.send_to(&r, peer).await;
                }
            }
        });
        addr
    }

    #[tokio::test]
    async fn a_forwarded_query_comes_back_with_the_resolvers_bytes_unchanged() {
        let up = upstream(|q| {
            let mut r = q.to_vec();
            r.extend_from_slice(b"ANSWER");
            vec![r]
        })
        .await;
        let q = [0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0];
        let r = forward_udp(up, &q, Duration::from_secs(2)).await.unwrap();
        assert_eq!(&r[..12], &q);
        assert_eq!(&r[12..], b"ANSWER");
    }

    #[tokio::test]
    async fn a_resolver_that_does_not_answer_is_a_time_out() {
        let up = upstream(|_| vec![]).await;
        let started = std::time::Instant::now();
        let e = forward_udp(up, &[1, 2, 3, 4], Duration::from_millis(300)).await.unwrap_err();
        assert_eq!(e.kind(), std::io::ErrorKind::TimedOut);
        assert!(started.elapsed() < Duration::from_secs(2));
    }

    #[tokio::test]
    async fn a_datagram_with_another_id_is_ignored_and_the_right_one_after_it_is_taken() {
        let up = upstream(|q| vec![vec![q[0] ^ 0xff, q[1], 9, 9], vec![q[0], q[1], 7, 7]]).await;
        let r = forward_udp(up, &[0x12, 0x34, 0, 0], Duration::from_secs(2)).await.unwrap();
        assert_eq!(r, vec![0x12, 0x34, 7, 7]);
        // Only the wrong id: a time-out, never that datagram.
        let up = upstream(|q| vec![vec![q[0] ^ 0xff, q[1], 9, 9]]).await;
        let e = forward_udp(up, &[0x12, 0x34, 0, 0], Duration::from_millis(300)).await.unwrap_err();
        assert_eq!(e.kind(), std::io::ErrorKind::TimedOut);
    }

    #[tokio::test]
    async fn a_reply_over_the_size_limit_is_an_error_and_one_at_the_limit_is_taken() {
        let up = upstream(|q| {
            let mut r = vec![0u8; MAX_UDP_DNS];
            r[..2].copy_from_slice(&q[..2]);
            vec![r]
        })
        .await;
        assert_eq!(forward_udp(up, &[0x12, 0x34, 0, 0], Duration::from_secs(2)).await.unwrap().len(), MAX_UDP_DNS);
        let up = upstream(|q| {
            let mut r = vec![0u8; MAX_UDP_DNS + 1];
            r[..2].copy_from_slice(&q[..2]);
            vec![r]
        })
        .await;
        let e = forward_udp(up, &[0x12, 0x34, 0, 0], Duration::from_secs(2)).await.unwrap_err();
        assert_eq!(e.kind(), std::io::ErrorKind::InvalidData);
    }

    #[tokio::test]
    async fn a_query_shorter_than_a_header_is_not_sent() {
        let up = upstream(|_| vec![vec![0, 0]]).await;
        assert_eq!(forward_udp(up, &[1], Duration::from_millis(200)).await.unwrap_err().kind(), std::io::ErrorKind::InvalidInput);
    }

    // FreeholdOS patch 0005: the DNS path reads bytes that an app controls; no input may make it panic. Inputs are random
    // bytes, valid queries with a few bytes changed or cut, and queries built from random names and types. Whatever
    // decodes goes through the routing and the answer builder, and every answer encodes.
    struct Rng(u64);

    impl Rng {
        fn next(&mut self) -> u64 {
            self.0 ^= self.0 << 13;
            self.0 ^= self.0 >> 7;
            self.0 ^= self.0 << 17;
            self.0
        }
        fn below(&mut self, n: u64) -> u64 {
            self.next() % n
        }
    }

    #[test]
    fn no_dns_message_makes_the_dns_path_panic() {
        use crate::dns::{DnsManager, LockedDnsCookies};
        use bytes::Bytes;
        use std::sync::{Arc, Mutex};
        let cookies: LockedDnsCookies = Arc::new(Mutex::new(DnsCookies::new()));
        let types = [QType::A, QType::AAAA, QType::HTTPS, QType::SVCB, QType::PTR, QType::MX, QType::TXT, QType::NS];
        let labels = ["a", "ads", "example", "x-y", "1", "xn--bcher-kva", "very-long-label-very-long-label-very-long-label-0123456789"];
        for seed in [1u64, 2, 0x9e37_79b9_7f4a_7c15, 0xfeed_beef] {
            let mut g = Rng(seed);
            let mut decoded = 0;
            for round in 0..20_000u32 {
                let bytes: Vec<u8> = match g.below(3) {
                    0 => (0..g.below(80)).map(|_| g.below(256) as u8).collect(),
                    _ => {
                        // A valid query from random labels and a random type, then up to three changed bytes or a cut.
                        let n = 1 + g.below(4) as usize;
                        let name: String = (0..n).map(|_| labels[g.below(labels.len() as u64) as usize]).collect::<Vec<_>>().join(".") + ".";
                        let mut q = query_of(&[name.as_str()]);
                        q.questions[0].q_type = types[g.below(types.len() as u64) as usize].clone();
                        q.id = g.below(65536) as u16;
                        let mut b = q.encode().map(|b| b.to_vec()).unwrap_or_default();
                        if g.below(2) == 0 && !b.is_empty() {
                            for _ in 0..1 + g.below(3) {
                                let at = g.below(b.len() as u64) as usize;
                                b[at] = g.below(256) as u8;
                            }
                        }
                        if g.below(4) == 0 && !b.is_empty() {
                            b.truncate(g.below(b.len() as u64) as usize);
                        }
                        b
                    }
                };
                if let Ok(q) = Dns::decode(Bytes::from(bytes)) {
                    decoded += 1;
                    let route = route_query(&q, |name| if name.len() % 2 == 0 { DnsDecision::Tor } else { DnsDecision::Forward });
                    let key = u64::from(round % 7);
                    let answer = DnsManager::<()>::answer_names(&cookies, None, &q, key);
                    let _ = answer.encode();
                    let refusal = DnsManager::<()>::make_answer(&q, RCode::Refused, vec![]);
                    let _ = refusal.encode();
                    let _ = route;
                }
            }
            assert!(decoded > 2_000, "seed {seed}: only {decoded} messages decoded; the test is too weak");
        }
    }
}
