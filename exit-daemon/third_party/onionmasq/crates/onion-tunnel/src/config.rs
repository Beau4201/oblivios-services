//! Configuring the tunnel.

use crate::errors::{TunnelError, TunnelResult};
use crate::TunnelScaffolding;
#[cfg(feature = "pluggable-transports")]
use arti_client::config::pt::TransportConfigBuilder;
use arti_client::config::{
    BoolOrAuto, BridgeConfigBuilder, CfgPath, ConfigurationSource, TorClientConfigBuilder,
};
use arti_client::TorClientConfig;
#[cfg(feature = "experimental-udp-tunnel")]
use std::hash::{Hash, Hasher};
#[cfg(feature = "pluggable-transports")]
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::path::PathBuf;
use std::str::FromStr;
use std::sync::Arc;
use tor_config::ConfigurationSources;
#[cfg(feature = "pluggable-transports")]
use tor_linkspec::PtTransportName;
#[cfg(feature = "pluggable-transports")]
use tracing::trace;

// Re-export Arti types used in the configuration
pub use arti_client::{IntoTorAddr, TorAddr, TorAddrError};

// Default port number for TURN servers
#[cfg(feature = "experimental-udp-tunnel")]
pub const DEFAULT_TURN_PORT: u16 = experimental_turn_tcp_client::DEFAULT_PORT;

// Re-export UDP configuration types
#[cfg(feature = "experimental-udp-tunnel")]
pub use experimental_turn_tcp_client::auth::TurnAuth;

/// Configuration for an `OnionTunnel`.
///
/// # Usage notes
///
/// This struct is marked `#[non_exhaustive]` so future fields can be added, which means it's
/// slightly harder to make one by hand. You can make use of the `Default` impl, though:
///
/// ```rust
/// use onion_tunnel::config::TunnelConfig;
///
/// let mut cfg = TunnelConfig::default();
/// // for example
/// cfg.state_dir = Some("/path/to/state".into());
/// ```
#[derive(Clone, Debug, PartialEq, Default)]
#[non_exhaustive]
pub struct TunnelConfig {
    /// Path to a custom arti configuration TOML.
    ///
    /// Having this option set, other arti configuration options,
    /// such as [`state_dir`] are fully ignored.
    pub arti_cfg_path: Option<PathBuf>,
    /// Do not check permissions on the files we use.
    pub disable_fs_permission_checks: bool,
    /// Where to store Arti's persistent state.
    pub state_dir: Option<PathBuf>,
    /// Where to store Arti's cache.
    pub cache_dir: Option<PathBuf>,
    /// Where to store persistent data for Pluggable Transports.
    ///
    /// If not specified, a subdirectory of `state_dir` (or its default value)
    /// will be used.
    pub pt_dir: Option<PathBuf>,
    /// A list of bridge lines to use.
    ///
    /// If this is empty, bridges will be disabled.
    pub bridge_lines: Vec<String>,
    // Port of local host address where an unmanaged PT client is listening
    pub unmanaged_pt_port: Option<u16>,
    // pluggable transport type
    pub transport_type: Option<String>,
    #[cfg(feature = "pcap")]
    /// A path to write a pcap file to for debugging.
    pub pcap_path: Option<PathBuf>,
}

impl TunnelConfig {
    /// Make an Arti `TorClientConfig` using the values in this `TunnelConfig`.
    pub(crate) fn arti_config(
        &self,
        _scaffolding: Arc<impl TunnelScaffolding>,
    ) -> TunnelResult<TorClientConfig> {
        // If an `arti_cfg_path` is set, just use it and ignore everything else.
        if self.arti_cfg_path.is_some() {
            #[allow(clippy::unwrap_used)]
            let arti_cfg_path = self.arti_cfg_path.clone().unwrap();
            let mut sources = ConfigurationSources::new_empty();
            sources.push_source(
                ConfigurationSource::from_path(arti_cfg_path),
                tor_config::sources::MustRead::MustRead,
            );

            if self.disable_fs_permission_checks {
                sources.set_mistrust(fs_mistrust::Mistrust::new_dangerously_trust_everyone());
                sources.push_option("storage.permissions.dangerously_trust_everyone=true");
            }

            let cfg = sources.load().map_err(TunnelError::ArtiConfig)?;
            let tcc = tor_config::resolve::<TorClientConfig>(cfg)
                .map_err(TunnelError::ArtiConfigResolve)?;

            return Ok(tcc);
        }

        let mut builder = TorClientConfigBuilder::default();

        if self.disable_fs_permission_checks {
            builder.storage().permissions().dangerously_trust_everyone();
        }

        if let Some(ref state_dir) = self.state_dir {
            builder
                .storage()
                .state_dir(CfgPath::new_literal(state_dir.to_owned()));
        }

        if let Some(ref cache_dir) = self.cache_dir {
            builder
                .storage()
                .cache_dir(CfgPath::new_literal(cache_dir.to_owned()));
        }

        if !self.bridge_lines.is_empty() {
            for line in self.bridge_lines.iter() {
                let bcb =
                    BridgeConfigBuilder::from_str(line).map_err(TunnelError::BadBridgeLine)?;

                builder.bridges().bridges().push(bcb);
            }
            builder.bridges().enabled(BoolOrAuto::Explicit(true));
        }

        builder.address_filter().allow_local_addrs(true);

        #[cfg(feature = "pluggable-transports")]
        {
            use safelog::sensitive;

            trace!("Attempting to enable pluggable transports.");

            if let (Some(port), Some(transport_type)) =
                (self.unmanaged_pt_port, self.transport_type.clone())
            {
                let socket_addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(127, 0, 0, 1)), port);
                let mut tcb = TransportConfigBuilder::default();

                #[allow(clippy::unwrap_used)]
                tcb.protocols(vec![
                    PtTransportName::from_str(transport_type.as_str()).unwrap()
                ])
                .proxy_addr(socket_addr);

                builder.bridges().transports().push(tcb);
                trace!(
                    "Registered {} at {}",
                    transport_type.as_str(),
                    sensitive(socket_addr)
                );
            }
        }

        builder.build().map_err(TunnelError::ArtiConfigBuild)
    }
}

/// TURN server configuration, for UDP tunneling.
#[cfg(feature = "experimental-udp-tunnel")]
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct TurnConfig {
    pub tor_addr: TorAddr,
    pub auth: TurnAuth,
}

#[cfg(feature = "experimental-udp-tunnel")]
impl TurnConfig {
    /// Create a new default TURN configuration with the given server string
    ///
    /// The address will be converted to a [`TorAddr`], using a default port
    /// number if none was explicitly given.
    ///
    pub fn new(addr: &str) -> Result<Self, TorAddrError> {
        Ok(Self {
            tor_addr: match addr.into_tor_addr() {
                Ok(tor_addr) => tor_addr,
                Err(TorAddrError::NoPort) => (addr, DEFAULT_TURN_PORT).into_tor_addr()?,
                Err(e) => return Err(e),
            },
            auth: TurnAuth::None,
        })
    }
}

#[cfg(feature = "experimental-udp-tunnel")]
impl Hash for TurnConfig {
    fn hash<H: Hasher>(&self, state: &mut H) {
        self.tor_addr.to_string().hash(state);
        self.auth.hash(state);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn arti_config() {
        let mut cfg = TunnelConfig::default();
        cfg.state_dir = Some(PathBuf::from_str("foo").unwrap());
        cfg.cache_dir = Some(PathBuf::from_str("bar").unwrap());
        let scaffolding = Arc::new(());
        let arti_cfg = cfg.arti_config(scaffolding).unwrap();

        // Create a default arti config with some changes applied.
        let mut default_arti_cfg = TorClientConfigBuilder::default();
        default_arti_cfg.address_filter().allow_local_addrs(true);
        default_arti_cfg
            .storage()
            .state_dir(CfgPath::new_literal("foo"));
        default_arti_cfg
            .storage()
            .cache_dir(CfgPath::new_literal("bar"));
        let default_arti_cfg = default_arti_cfg.build().unwrap();

        assert_eq!(arti_cfg, default_arti_cfg);
    }

    #[test]
    fn bridges() {
        let mut cfg = TunnelConfig::default();

        // The following bridge lines were taken from arti's unit tests.
        cfg.bridge_lines = vec![
            "38.229.33.83:80 $0bac39417268b96b9f514e7f63fa6fba1a788955 ed25519:dGhpcyBpcyBpbmNyZWRpYmx5IHNpbGx5ISEhISEhISE".into(),
            "obfs4 some-host:80 $0bac39417268b96b9f514e7f63fa6fba1a788955 iat-mode=1".into()
        ];

        let scaffolding = Arc::new(());
        let arti_cfg = cfg.arti_config(scaffolding).unwrap();

        let mut default_arti_cfg = TorClientConfigBuilder::default();
        default_arti_cfg.bridges().bridges().push(BridgeConfigBuilder::from_str("38.229.33.83:80 $0bac39417268b96b9f514e7f63fa6fba1a788955 ed25519:dGhpcyBpcyBpbmNyZWRpYmx5IHNpbGx5ISEhISEhISE").unwrap());
        default_arti_cfg.bridges().bridges().push(
            BridgeConfigBuilder::from_str(
                "obfs4 some-host:80 $0bac39417268b96b9f514e7f63fa6fba1a788955 iat-mode=1",
            )
            .unwrap(),
        );
        default_arti_cfg
            .bridges()
            .enabled(BoolOrAuto::Explicit(true));

        default_arti_cfg.address_filter().allow_local_addrs(true);
        let default_arti_cfg = default_arti_cfg.build().unwrap();

        assert_eq!(arti_cfg, default_arti_cfg);
    }

    #[test]
    #[cfg(feature = "pluggable-transports")]
    fn pluggable_transports() {
        use arti_client::config::pt::TransportConfigBuilder;
        use std::net::{IpAddr, Ipv4Addr, SocketAddr};
        use tor_linkspec::PtTransportName;

        let mut cfg = TunnelConfig::default();
        cfg.unmanaged_pt_port = Some(4242);
        cfg.transport_type = Some("obfs4".into());
        let scaffolding = Arc::new(());
        let arti_cfg = cfg.arti_config(scaffolding).unwrap();

        let mut default_arti_cfg = TorClientConfigBuilder::default();
        let mut tcb = TransportConfigBuilder::default();
        tcb.protocols(vec![PtTransportName::from_str("obfs4").unwrap()])
            .proxy_addr(SocketAddr::new(
                IpAddr::V4(Ipv4Addr::new(127, 0, 0, 1)),
                4242,
            ));
        default_arti_cfg.bridges().transports().push(tcb);
        default_arti_cfg.address_filter().allow_local_addrs(true);
        let default_arti_cfg = default_arti_cfg.build().unwrap();

        assert_eq!(arti_cfg, default_arti_cfg);
    }

    #[test]
    #[cfg(feature = "experimental-udp-tunnel")]
    fn turn_config() {
        assert_eq!(
            TurnConfig::new("127.0.0.1").unwrap(),
            TurnConfig {
                tor_addr: ("127.0.0.1", DEFAULT_TURN_PORT).into_tor_addr().unwrap(),
                auth: TurnAuth::None,
            }
        );
        assert_eq!(
            TurnConfig::new("").unwrap_err(),
            TorAddrError::InvalidHostname
        );
    }
}
