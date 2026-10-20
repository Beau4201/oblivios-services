//! UDP Tunneling with TURN over Tor
//!
//! This builds on the TURN protocol utilities from the `webrtc` crate and the
//! TURN-over-TCP client from the `experimental-turn-tcp-client` crate. Here we add logic
//! for managing multiple allocations and interoperating with onion-tunnel
//! features, presenting a high-level interface for sending and
//! receiving tunneled datagrams.
//!
//! Note, this feature is experimental and not production ready, hence why it
//! is not present in default builds.  In the future, it will probably be
//! removed.  Please do not report bugs or security issues related to the code
//! in this submodule.

mod allocation;
mod device;
mod send;
mod tunnel;

use std::net::SocketAddr;

pub(crate) use device::forward_tunnel_to_device;
pub(crate) use send::{UdpSendContext, UdpTrySendResult};
pub(crate) use tunnel::{UdpPrebuildRequest, UdpTunnel};

/// Owned UDP datagram, received by the [`tunnel::UdpTunnel`]
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct UdpData {
    pub(crate) source: SocketAddr,
    pub(crate) dest: SocketAddr,
    pub(crate) data: Vec<u8>,
}
