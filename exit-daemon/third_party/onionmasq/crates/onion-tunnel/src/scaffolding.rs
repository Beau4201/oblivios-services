//! Types for integrating the tunnel into end-user applications.

use crate::accounting::BandwidthCounter;
use crate::config::TunnelConfig;
#[cfg(feature = "experimental-udp-tunnel")]
use crate::config::TurnConfig;
use crate::errors::ArtiError;
use crate::CountryCode;
pub use arti_client::IsolationToken;
pub use arti_client::TorAddr;
use futures::Stream;
use smoltcp::wire::IpEndpoint;
use std::collections::HashMap;
use std::io;
use std::net::SocketAddr;
use std::os::fd::RawFd;
use std::sync::Arc;
use tor_geoip::GeoipDb;
use tor_linkspec::{HasAddrs, HasRelayIds};
use tor_llcrypto::pk::ed25519::Ed25519Identity;
use tor_llcrypto::pk::rsa::RsaIdentity;
use tor_proto::client::circuit::ClientCirc;

#[cfg(target_os = "linux")]
pub mod linux;

/// Information about a failed connection.
///
/// New fields might be added to this `struct` in new versions of the crate.
#[derive(Debug)]
#[non_exhaustive]
pub struct FailedConnectionDetails {
    /// The source IP address of the connection.
    pub proxy_src: IpEndpoint,
    /// The destination IP address of the connection.
    ///
    /// Note that this is the IP on the proxy side, not the Tor side.
    pub proxy_dst: IpEndpoint,
    /// The address of the final destination that we tried to connect to via Tor.
    pub tor_dst: TorAddr,
    /// The isolation key returned by `TunnelScaffolding::isolate`.
    pub isolation_key: u64,
    /// The error returned.
    pub error: ArtiError,
}

/// Information about a newly established connection.
///
/// New fields might be added to this `struct` in new versions of the crate.
#[derive(Debug)]
#[non_exhaustive]
pub struct ConnectionDetails<'a> {
    /// The source IP address of the connection.
    pub proxy_src: IpEndpoint,
    /// The destination IP address of the connection.
    ///
    /// Note that this is the IP on the proxy side, not the Tor side.
    pub proxy_dst: IpEndpoint,
    /// The address of the final destination that we connected to via Tor.
    pub tor_dst: &'a TorAddr,
    /// The isolation key returned by `TunnelScaffolding::isolate`.
    pub isolation_key: u64,
    /// The Tor circuit that we're using for the connection.
    pub tor_circuit: &'a ClientCirc,
}

/// Details about a Tor relay used in a circuit.
///
/// This type exists to avoid having to import the various `tor-*` crates and traits
/// in order to get this information.
///
/// Note that it's possible for all of these fields to be empty if the relay is a bridge?
/// Probably? Either way, don't rely on it.
#[derive(Clone, Debug)]
pub struct CircuitRelayDetails {
    /// The RSA identity of the relay, if there is one.
    pub rsa_identity: Option<RsaIdentity>,
    /// The Ed25519 identity of the relay, if there is one.
    pub ed_identity: Option<Ed25519Identity>,
    /// The IP addresses of the relay.
    pub addresses: Vec<SocketAddr>,
    /// The country code of the relay.
    pub country_code: Option<CountryCode>,
}

impl ConnectionDetails<'_> {
    /// List the relays that make up the Tor circuit we're using for this connection.
    pub fn circuit_relays(&self) -> Vec<CircuitRelayDetails> {
        // FIXME(eta): this is an unnecessary Arc clone
        let db = GeoipDb::new_embedded();

        // TODO: Error means closed circuit, not sure if returning an empty
        // vector is okay then ...
        let path = match self.tor_circuit.single_path() {
            Ok(p) => p,
            Err(_) => return vec![],
        };

        path.iter()
            .map(|x| x.as_chan_target())
            .map(|x| {
                if let Some(x) = x {
                    CircuitRelayDetails {
                        rsa_identity: x.rsa_identity().copied(),
                        ed_identity: x.ed_identity().copied(),
                        addresses: x.addrs().collect(),
                        country_code: db
                            .lookup_country_code_multi(x.addrs().map(|x| x.ip()))
                            .copied(),
                    }
                } else {
                    CircuitRelayDetails {
                        rsa_identity: None,
                        ed_identity: None,
                        addresses: vec![],
                        country_code: None,
                    }
                }
            })
            .collect()
    }
}

/// An asynchronous request for a running tunnel to do something.
//
// FIXME(eta): Do we potentially want some form of confirmation? Is that security-sensitive?
#[derive(Debug)]
pub enum TunnelCommand {
    UpdateConfig(TunnelConfig),
    /// Refresh all circuits. This causes all new connections after the command is sent to
    /// use different circuits to the set currently used.
    RefreshCircuits,
    /// A version of `RefreshCircuits` that only affects connections using one isolation key.
    RefreshCircuitsForApp {
        isolation_key: u64,
    },
    /// Set arti's dormant or get it our of dormant state.
    SetDormant(bool),
}

/// Information about a UDP tunnel we are considering
///
/// The application's isolation ID will be known, as well as the arbitrary source address
/// assigned to this port allocation. We include the original destination address which
/// prompted this allocation, but applications may use the allocation for other peers.
///
#[cfg(feature = "experimental-udp-tunnel")]
#[derive(Debug)]
pub struct UdpTunnelContext {
    pub src: IpEndpoint,
    pub initial_dst: IpEndpoint,
    pub isolation_key: u64,
}

/// Types of UDP tunneling
///
/// Currently we can either reject a UDP allocation, or use a TURN server via Tor.
/// Other ways to handle UDP might be added here in the future.
///
#[cfg(feature = "experimental-udp-tunnel")]
#[derive(Debug, Default, Clone, Hash, Eq, PartialEq)]
pub enum UdpTunnelChoice {
    #[default]
    Reject,
    Turn(Arc<TurnConfig>),
}

/// Support functions used to integrate an [`crate::OnionTunnel`] into its environment.
///
/// An `OnionTunnel` can be parameterized on a type implementing this trait in
/// order to extend the tunnel's functionality.
///
/// The unit type `()` may be used if no scaffolding is required.
pub trait TunnelScaffolding: Send + Sync + 'static {
    /// Indicate to the underlying OS that the provided socket should not go via the TUN device.
    ///
    /// This function is called after `socket()` but before `connect()` with the file descriptor
    /// of the socket to be protected, as well as the intended destination address of the socket.
    ///
    /// This function is useful if you're running the tunnel in a network configuration where
    /// all traffic goes via the tunnel, as you need some way to make sure the tunnel doesn't use
    /// itself for connections to the Tor network.
    fn protect(&self, fd: RawFd, addr: &SocketAddr) -> io::Result<()> {
        let _ = fd;
        let _ = addr;
        Ok(())
    }

    /// Indicate to the underlying OS that all sockets having a particular
    /// `isolation_key` to not go via the TUN device, in other words, get
    /// "protected" using [`TunnelScaffolding::protect()`].
    ///
    /// This function is called shortly before the code would usually create the
    /// arti [`DataStream`](arti_client::DataStream) and associate the internal
    /// socket with it.
    ///
    /// The default implementation returns `false` by default because this is a
    /// rather scary/dangerous option that *WILL* leak if used the wrong way.
    /// The main idea behind this is to leak on applications that already include
    /// Tor themselves, such as the Tor Browser, for which it would not make sense
    /// to go through the Tor network twice, which in fact might even pose a larger
    /// security risk.
    fn should_protect(&self, isolation_key: u64) -> bool {
        let _ = isolation_key;
        false
    }

    /// Return an 'isolation key' to be used for a new connection between the provided
    /// endpoints. `ip_proto` is an IP protocol number (e.g. 6 for TCP)
    ///
    /// An isolation key is an arbitrary 64-bit unsigned integer. Connections with the same
    /// isolation key will be allowed to share Tor circuits (and will thus probably appear to
    /// be coming from the same exit node IP address). Care will be taken to ensure connections
    /// with two different isolation keys do not share circuits.
    ///
    /// The default implementation returns 0 for all endpoint pairs.
    fn isolate(&self, src: IpEndpoint, dst: IpEndpoint, ip_proto: u8) -> io::Result<u64> {
        let _ = src;
        let _ = dst;
        let _ = ip_proto;
        Ok(0)
    }

    /// Return a country code to be used for a connection, in order to make it appear to the
    /// destination that the traffic is coming from somewhere in the given country.
    ///
    /// This limits the Tor exit relays used for the connection to ones whose IP addresses
    /// appear to be located inside the given country. If `None` is returned, no limiting is
    /// performed.
    fn locate(&self, src: IpEndpoint, dst: IpEndpoint, isolation_key: u64) -> Option<CountryCode> {
        let _ = src;
        let _ = dst;
        let _ = isolation_key;
        None
    }

    /// Notify the scaffolding that Arti bootstrapping has completed.
    ///
    /// This is called once the tunnel is ready to use for traffic.
    fn on_bootstrapped(&self) {}

    /// Notify the scaffolding that a new directory is available.
    /// The `relays_by_country` map has per-country-code relay counts.
    //
    // TODO(eta): This is only ever invoked once, and feels hacked on. Also, `CountryCode` doesn't
    //            implement `Hash`, which sucks, so we have to use `String`.
    fn on_directory(&self, relays_by_country: HashMap<String, usize>) {
        let _ = relays_by_country;
    }

    /// Notify the scaffolding of a new proxied connection.
    ///
    /// This is called by the tunnel when a new connection is made through the Tor network.
    /// You can use this to, for example, display information about the circuit a connection
    /// is using in your app's UI.
    fn on_established(&self, details: ConnectionDetails<'_>) {
        let _ = details;
    }

    /// Notify the scaffolding of a failure to connect via Tor.
    ///
    /// This is called by the tunnel when an attempt to establish a connection through the
    /// Tor network fails for some reason.
    fn on_arti_failure(&self, details: FailedConnectionDetails) {
        let _ = details;
    }

    /// Notify the scaffolding of a connection ending.
    ///
    /// If the connection ends abnormally, `err` will be filled with the I/O error reason why
    /// the connection failed.
    ///
    /// This is called by the tunnel when a proxied connection is closed for whatever reason.
    /// It usually matches up with a prior call to `on_established` or `on_arti_failure`.
    ///
    /// (Note that if a connection fails to establish, you might get a call to `on_arti_failure`
    /// and then this function as well, signifying the failure to connect via Tor and the closing
    /// of the proxied connection respectively.)
    fn on_socket_close(&self, src: IpEndpoint, dst: IpEndpoint, err: Option<io::Error>) {
        let _ = src;
        let _ = dst;
        let _ = err;
    }

    /// Provide a stream of commands that can be used to control the running tunnel.
    ///
    /// Since the `OnionTunnel` cannot have commands called on it while it is running, this provides
    /// a mechanism for other code to control it remotely.
    ///
    /// If the command stream returns `None` (i.e. hangs up), the proxy will shut down.
    ///
    /// This function will only be called once. If `None` is returned, no commands will be used.
    //
    // NOTE(eta): This is boxed because otherwise you'd have to add associated types everywhere,
    //            which would impose a necessary cost on people even if they didn't use this method.
    fn command_stream(&self) -> Option<Box<dyn Stream<Item = TunnelCommand> + Send + Sync>> {
        None
    }

    /// Provide a bandwidth counter to track the bandwidth of all circuits using a given isolation
    /// key.
    ///
    /// The intended usage of this API is per-isolation-key bandwidth accounting; the total
    /// bandwidth used by all TCP connections that share the `isolation_key` will be written
    /// to the returned `BandwidthCounter`.
    ///
    /// This function can be called more than once for the same `isolation_key`. You should ideally
    /// return the same `BandwidthCounter` if you want things to work properly.
    fn get_bandwidth_counter(&self, isolation_key: u64) -> Option<Arc<BandwidthCounter>> {
        let _ = isolation_key;
        None
    }

    /// Provide configuration for a new UDP tunnel
    ///
    /// Requires the "experimental-udp-tunnel" crate feature.
    ///
    /// UDP tunnels will be created on demand if possible, one per isolated local port.
    /// The Scaffolding will also see the initial destination that prompted this allocation
    /// to be made, but applications may use the same allocation for additional destinations
    /// without warning.
    ///
    #[cfg(feature = "experimental-udp-tunnel")]
    fn udp_tunnel(&self, _context: &UdpTunnelContext) -> UdpTunnelChoice {
        Default::default()
    }

    /// Potentially request pre-built UDP tunnels
    ///
    /// This will be called opportunistically, when the VPN is already seeing some
    /// other indication of activity for the indicated isolation key. Returns the number
    /// of pre-built tunnels for each desired configuration.
    ///
    /// UdpTunnelChoice::Reject has no effect if it's included in the results.
    ///
    /// Pre-built tunnels will be used any time udp_tunnel() returns an equivalent
    /// UdpTunnelChoice.
    ///
    #[cfg(feature = "experimental-udp-tunnel")]
    fn udp_prebuild(&self, _isolation_key: u64) -> HashMap<UdpTunnelChoice, usize> {
        Default::default()
    }
}

impl TunnelScaffolding for () {}

#[cfg(test)]
mod test {
    use smoltcp::wire::IpAddress;

    use super::*;

    struct DummyScaffolding;
    impl TunnelScaffolding for DummyScaffolding {}

    #[test]
    fn default() {
        let dummy = DummyScaffolding {};
        assert_eq!(
            dummy.protect(-1, &"127.0.0.1:80".parse().unwrap()).unwrap(),
            ()
        );
        assert_eq!(
            dummy
                .isolate(
                    IpEndpoint::new(IpAddress::v4(127, 0, 0, 1), 80),
                    IpEndpoint::new(IpAddress::v4(127, 0, 0, 1), 80),
                    17
                )
                .unwrap(),
            0
        );
        assert_eq!(
            dummy.locate(
                IpEndpoint::new(IpAddress::v4(127, 0, 0, 1), 80),
                IpEndpoint::new(IpAddress::v4(127, 0, 0, 1), 80),
                17
            ),
            None
        );

        dummy.on_bootstrapped();
        dummy.on_directory(HashMap::new());
        //dummy.on_established(...);
        //dummy.on_arti_failure(...);
        dummy.on_socket_close(
            IpEndpoint::new(IpAddress::v4(127, 0, 0, 1), 80),
            IpEndpoint::new(IpAddress::v4(127, 0, 0, 1), 80),
            None,
        );

        assert!(dummy.command_stream().is_none());
        assert_eq!(dummy.get_bandwidth_counter(0), None);

        //dummy.udp_tunnel(...);
        #[cfg(feature = "experimental-udp-tunnel")]
        assert_eq!(dummy.udp_prebuild(0), HashMap::new());
    }
}
