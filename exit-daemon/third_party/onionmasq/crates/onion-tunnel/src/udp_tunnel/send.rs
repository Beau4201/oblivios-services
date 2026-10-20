//! Utilities that relate to dispatching individual UDP datagrams

use crate::{
    dns::LockedDnsCookies, isolation::OnionIsolationKey, runtime::OnionTunnelArtiRuntime,
    udp_tunnel::UdpData, TunnelScaffolding,
};
use arti_client::TorClient;
use safelog::sensitive;
use smoltcp::wire::IpEndpoint;
use std::{
    collections::HashMap,
    net::{IpAddr, Ipv4Addr, Ipv6Addr},
    sync::Arc,
};
use tokio::sync::mpsc;
use tokio::sync::Notify;

/// Incoming datagram sender
#[derive(Clone)]
pub(super) struct IncomingDatagramSender {
    pub(super) recv_notify: Arc<Notify>,
    pub(super) udp_sender: mpsc::Sender<UdpData>,
}

impl IncomingDatagramSender {
    /// Try to enqueue a UDP datagram for delivery into the VPN interior
    pub(super) async fn send(&self, data: UdpData) {
        tracing::trace!("Dispatching received UDP datagram, {:?}", sensitive(&data));
        if self.udp_sender.send(data).await.is_ok() {
            self.recv_notify.notify_one();
        } else {
            tracing::warn!("Dropping UDP, tunnel incoming buffer exhausted")
        }
    }
}

/// Potential results from trying to send one UDP datagram
pub(crate) enum UdpTrySendResult {
    /// Datagram was queued successfully
    Accepted,
    /// Cannot accept the datagram, but a rejection should not be sent
    Dropped,
    /// Do not accept; the caller may send an ICMP rejection immediately
    Reject,
}

/// Context necessary to send on a `UdpTunnel`
pub(crate) struct UdpSendContext<'a, 's, 'e, 'd, S: TunnelScaffolding> {
    pub(crate) arti: &'a Arc<TorClient<OnionTunnelArtiRuntime<S>>>,
    pub(crate) scaffolding: &'s Arc<S>,
    pub(crate) app_epochs: &'e HashMap<u64, u16>,
    pub(crate) global_epoch: u16,
    pub(crate) dns_cookies: &'d LockedDnsCookies,
}

impl<S: TunnelScaffolding> UdpSendContext<'_, '_, '_, '_, S> {
    /// Determine the app isolation ID for a still-alive UDP socket.
    ///
    /// Logs failures.
    ///
    pub(super) fn isolate(&self, data: &UdpData) -> Result<u64, ()> {
        let proto_udp = 17;
        match self
            .scaffolding
            .isolate(data.source.into(), data.dest.into(), proto_udp)
        {
            Ok(v) if v != 0 => Ok(v),
            _ => {
                // Try the search again using an unset destination
                // TODO: The scaffolding API could better account for un-connected sockets
                let unspecified = match data.source.ip() {
                    IpAddr::V4(_) => IpEndpoint::new(Ipv4Addr::UNSPECIFIED.into(), 0),
                    IpAddr::V6(_) => IpEndpoint::new(Ipv6Addr::UNSPECIFIED.into(), 0),
                };
                match self
                    .scaffolding
                    .isolate(data.source.into(), unspecified, proto_udp)
                {
                    Ok(v) => {
                        if v != 0 {
                            tracing::warn!("Scaffolding finds UDP isolation from {:?} to {:?} only when retrying search with unspecified destination",
                            sensitive(data.source), sensitive(data.dest));
                        }
                        Ok(v)
                    }
                    Err(e) => {
                        tracing::error!(
                "Dropping UDP from {:?} to {:?}, unable to determine app isolation ID, {:?}",
                sensitive(data.source),
                sensitive(data.dest),
                e
            );
                        Err(())
                    }
                }
            }
        }
    }

    pub(super) fn onion_isolation_key(&self, app_id: u64) -> OnionIsolationKey {
        OnionIsolationKey {
            app_id,
            global_epoch: self.global_epoch,
            app_epoch: self.app_epochs.get(&app_id).copied().unwrap_or(0),
        }
    }
}
