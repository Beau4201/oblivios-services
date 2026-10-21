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
use std::net::{Ipv4Addr, Ipv6Addr};
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
use smoltcp::wire::IpEndpoint;
use smoltcp::{
    socket::udp::PacketBuffer as UdpSocketBuffer, socket::udp::PacketMetadata as UdpPacketMetadata,
    wire::IpAddress, wire::Ipv4Address, wire::Ipv6Address,
};
#[cfg(feature = "experimental-udp-tunnel")]
use tokio::sync::mpsc;
use tokio::time::timeout;
use tracing::{info, trace, warn};

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
    #[cfg(feature = "experimental-udp-tunnel")]
    resolver_map: HashMap<String, ResolverResult>,
    #[cfg(feature = "experimental-udp-tunnel")]
    resolver_send: mpsc::Sender<(String, ResolverResult)>,
    #[cfg(feature = "experimental-udp-tunnel")]
    resolver_recv: mpsc::Receiver<(String, ResolverResult)>,
}

/// The time to live value returned in DNS responses (in seconds).
const TIME_TO_LIVE_IN_DNS: u64 = 60;

/// The seconds to live of a value in the actual cache.
///
/// This should be larger than [`TIME_TO_LIVE_IN_DNS`] because otherwise we
/// could get a weird race condition.
const SECONDS_TO_LIVE_IN_CACHE: u64 = 70;

/// The amount in seconds on how long a rate limit window is open.
const RATE_LIMIT_WINDOW: u64 = 180;

/// The amount of requests that are processed in the time span of one rate limit
/// window.  Once this limit has been reached, all further requests are ignored
/// until the rate limit gets reset.
const RATE_LIMIT_MAX_REQS: u64 = 65535;

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
        match addr {
            IpAddress::Ipv4(v4) => v4.octets()[0] == 10,
            IpAddress::Ipv6(v6) => v6.octets()[..2] == [0xfe, 0xc0],
        }
    }

    /// Generate a random IPv4 address in the 10.0.0.0/8 subnet.
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

        // Make a random IPv4 address in the range 10.0.0.0/8.
        let mut ret = [10, 0, 0, 0];

        // 16777214 = 2^24 - 2, the total amount of IPv4 addresses in 10.0.0.0/8.
        // It is unlikely that this is reached before TTL expires.
        assert!(self.map_v4.cache_size() < 16777214);

        loop {
            rand::rng().fill_bytes(&mut ret[1..]);
            /* TODO MSRV 1.91: when ip_from is stable, switch to
            let ret = Ipv4Address::from_octets(ret);
            */
            let ret = Ipv4Address::from(ret);

            if self.map_v4.cache_get(&ret).is_none() {
                break;
            }
        }
        /* TODO MSRV 1.91: when ip_from is stable, switch to
        let ret = Ipv4Address::from_octets(ret);
         */
        let ret = Ipv4Address::from(ret);

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

        // Make a random IPv6 address in the range fec0::/10.
        // This range is the deprecated "site-local address" range, but it should still
        // be fine to use; it's just a convenient reserved block that's definitely not globally
        // routable.

        // It is not necessary to check for an address space exhaustion,
        // as other operating system limits are more likely to be hit,
        // before self.map_v6.len() exceeds 2^118.

        let mut ret = [0xfe, 0xc0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0];
        loop {
            rand::rng().fill_bytes(&mut ret[2..]);
            /* TODO MSRV 1.91: when ip_from is stable, switch to
            let ret = Ipv6Addr::from_octets(ret);
             */
            let ret = Ipv6Addr::from(ret);
            if self.map_v6.cache_get(&ret).is_none() {
                break;
            }
        }
        /* TODO MSRV 1.91: when ip_from is stable, switch to
        let ret = Ipv6Addr::from_octets(ret);
         */
        let ret = Ipv6Addr::from(ret);

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

pub struct DnsManager<S: TunnelScaffolding> {
    listener_udp_v4: UdpSocket,
    listener_udp_v6: UdpSocket,
    cookies: LockedDnsCookies,
    arti: Arc<TorClient<OnionTunnelArtiRuntime<S>>>,
    scaffolding: Arc<S>,
    #[cfg(feature = "experimental-udp-tunnel")]
    udp_prebuild: mpsc::Sender<UdpPrebuildRequest>,
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
        Ok(Self {
            listener_udp_v4: Self::new_udp_listener(tunnel, Self::V4_ADDRESS.into())?,
            listener_udp_v6: Self::new_udp_listener(tunnel, Self::V6_ADDRESS.into())?,
            cookies: Arc::new(Mutex::new(DnsCookies::new())),
            arti: tunnel.arti.isolated_client(),
            scaffolding: tunnel.scaffolding.clone(),
            #[cfg(feature = "experimental-udp-tunnel")]
            udp_prebuild: tunnel.udp_tunnel.prebuild_sender().clone(),
        })
    }

    fn new_udp_listener<D>(
        tunnel: &OnionTunnel<S, D>,
        bind_addr: IpAddress,
    ) -> TunnelResult<UdpSocket> {
        let rx_buffer = UdpSocketBuffer::new(vec![UdpPacketMetadata::EMPTY], vec![0; 4096]);
        let tx_buffer = UdpSocketBuffer::new(vec![UdpPacketMetadata::EMPTY], vec![0; 4096]);

        let socket = udp::Socket::new(rx_buffer, tx_buffer);

        let mut listener = UdpSocket::new(tunnel, socket);
        #[allow(clippy::expect_used)]
        listener
            .bind(bind_addr, 53)
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

    fn build_answer(
        &self,
        query: &dns_message_parser::Dns,
        from: IpEndpoint,
    ) -> Option<dns_message_parser::Dns> {
        let dest = if matches!(from.addr, IpAddress::Ipv4(..)) {
            IpEndpoint::new(Self::V4_ADDRESS.into(), 53)
        } else {
            IpEndpoint::new(Self::V6_ADDRESS.into(), 53)
        };

        // FreeholdOS: a query whose owner cannot be found is refused with SERVFAIL. Upstream falls back to a
        // shared key here, which lets two apps share one DNS isolation (its own comment says so); the exit
        // daemon attributes every flow to a uid or refuses it (ADR-184, finding F03).
        let isolation_key = match Self::attribute(query, self.scaffolding.isolate(from, dest, 17 /* UDP */)) {
            Ok(key) => Some(key),
            Err(refusal) => {
                trace!(
                    "DNS query from {} refused: its owner could not be found",
                    sensitive(from)
                );
                return Some(refusal);
            }
        };

        // For UDP tunnel pre-building, look up what the associated country code would be
        #[cfg(feature = "experimental-udp-tunnel")]
        let country_code = isolation_key.and_then(|key| self.scaffolding.locate(from, dest, key));

        let mut answers = vec![];

        for question in query.questions.iter() {
            let name = question.domain_name.to_string();
            let rr = match &question.q_type {
                &QType::A => {
                    #[allow(clippy::expect_used)]
                    let mut cookies = self.cookies.lock().expect("cookie poisoned");
                    let cookie = match cookies.make_for_v4(Some(&self.arti), isolation_key, name) {
                        Some(cookie) => cookie,
                        // Return a `Refused` in case of an address exhaustion.
                        None => return Some(Self::make_answer(query, RCode::Refused, vec![])),
                    };

                    #[cfg(feature = "experimental-udp-tunnel")]
                    if let Some(isolation_key) = isolation_key {
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
                    let mut cookies = self.cookies.lock().expect("cookie poisoned");
                    let cookie = match cookies.make_for_v6(Some(&self.arti), isolation_key, name) {
                        Some(cookie) => cookie,
                        // Return a `Refused` in case of an address exhaustion.
                        None => return Some(Self::make_answer(query, RCode::Refused, vec![])),
                    };
                    #[cfg(feature = "experimental-udp-tunnel")]
                    if let Some(isolation_key) = isolation_key {
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
                    return Some(Self::make_answer(query, RCode::NotImp, vec![]));
                }
                // TODO(eta): implement PTR
                x => {
                    warn!(
                        "Received DNS query of type {}, which we don't support yet!",
                        x
                    );
                    // return "not implemented" -- at least it's better than nothing!
                    return Some(Self::make_answer(query, RCode::NotImp, vec![]));
                }
            };
            answers.push(rr);
        }

        Some(Self::make_answer(query, RCode::NoError, answers))
    }

    fn decode(&self, payload: Bytes, from: IpEndpoint) -> Option<dns_message_parser::Dns> {
        let ret = dns_message_parser::Dns::decode(payload);
        match ret {
            Ok(p) => {
                trace!("DNS question from {}: {}", sensitive(from), sensitive(&p));
                let ret = self.build_answer(&p, from);
                if let Some(ref r) = ret {
                    trace!("DNS answer to {}: {}", sensitive(from), sensitive(r));
                } else {
                    trace!("no DNS answer for {}", sensitive(from));
                }
                ret
            }
            Err(e) => {
                warn!("Unable to parse DNS request: {}", e);
                None
            }
        }
    }

    pub async fn start(&mut self) {
        info!("Starting DNS manager");

        loop {
            let _ = timeout(Duration::from_secs(RATE_LIMIT_WINDOW), async {
            // Process at most `RATE_LIMIT_MAX_REQS` requests.
            for _ in 0..RATE_LIMIT_MAX_REQS {
                tokio::select! {
                    r = self.listener_udp_v4.next() => match r {
                        Some((payload, endpoint)) => {
                            if let Some(reply) = self.decode(payload, endpoint) {
                                match reply.encode() {
                                    Ok(v) => {
                                        if let Err(e) = self.listener_udp_v4.send(&v, endpoint) {
                                            warn!("Failed to send DNS reply: {e:?}");
                                        }
                                    },
                                    Err(e) => warn!("Failed to encode DNS reply: {e}")
                                }
                            }
                        }
                        None => unreachable!(),
                    },
                    r = self.listener_udp_v6.next() => match r {
                        Some((payload, endpoint)) => {
                            if let Some(reply) = self.decode(payload, endpoint) {
                                match reply.encode() {
                                    Ok(v) => {
                                        if let Err(e) = self.listener_udp_v6.send(&v, endpoint) {
                                            warn!("Failed to send DNS reply: {e:?}");
                                        }
                                    },
                                    Err(e) => warn!("Failed to encode DNS reply: {e}")
                                }
                            }
                        }
                        None => unreachable!(),
                    }
                }
            }

            // We have reached the limit; until tokio terminates us due to the
            // `RATE_LIMIT_WINDOW` being reached, lets endlessly read on the
            // sockets and discard all incoming datagrams.
            loop {
                tokio::select! {
                    _ = self.listener_udp_v4.next() => {},
                    _ = self.listener_udp_v6.next() => {},
                }
            }
        })
        .await;

            // Flush the cache at the end of the rate limit window to save
            // space.
            if let Ok(mut lock) = self.cookies.lock() {
                lock.cookies.iter_mut().for_each(|(_, cookies)| {
                    cookies.map_v4.flush();
                    cookies.map_v6.flush();
                });
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
}
