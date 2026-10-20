//! Implementation for per-allocation tasks used within the UDP tunnel

use crate::{
    accounting::BandwidthCounter,
    config::TurnConfig,
    dns::{DnsCookies, LockedDnsCookies},
    isolation::OnionIsolationKey,
    runtime::OnionTunnelArtiRuntime,
    scaffolding::{ConnectionDetails, FailedConnectionDetails},
    udp_tunnel::send::IncomingDatagramSender,
    udp_tunnel::UdpData,
    TunnelScaffolding,
};
use arti_client::{config::BoolOrAuto, DataReader, DataWriter, StreamPrefs, TorClient};
use experimental_turn_tcp_client::{
    allocation::{ChannelBindAction, TurnAllocation, TurnAllocationRead, TurnReaderSignal},
    channel::ChannelNumber,
    proto::{
        RequestedAddressFamily, TurnTcpRead, TurnTcpWrite, REQUESTED_FAMILY_IPV4,
        REQUESTED_FAMILY_IPV6,
    },
};
use safelog::sensitive;
use smoltcp::wire::{IpAddress, IpEndpoint};
use std::{collections::HashMap, net::SocketAddr, sync::Arc, time::Instant};
use tokio::{
    sync::{mpsc, Notify},
    task::JoinHandle,
};
use tor_geoip::CountryCode;
use tor_proto::client::stream::ClientStreamCtrl;

/// Maximum number of binding requests we will issue between received ChannelData messages
const MAX_CHANNEL_BINDING_BACKLOG: usize = 256;

/// Maximum number of [`TurnReaderSignals`] that will be queued between outgoing [`UdpData`] messages
const MAX_READER_SIGNAL_BACKLOG: usize = 128;

/// Proxy source and destination to report to Scaffolding as the instigator for a UDP tunnel
#[derive(Debug, Clone, PartialEq, Eq)]
pub(super) struct TunnelInstigator {
    pub(super) proxy_src: IpEndpoint,
    pub(super) proxy_dst: IpEndpoint,
}

/// Context necessary to create new allocations
pub(super) struct NewAllocationContext<S: TunnelScaffolding> {
    pub(super) arti: Arc<TorClient<OnionTunnelArtiRuntime<S>>>,
    pub(super) scaffolding: Arc<S>,
    pub(super) config: Arc<TurnConfig>,
    pub(super) isolation: OnionIsolationKey,
    pub(super) country_code: Option<CountryCode>,
    pub(super) address_family: RequestedAddressFamily,
    pub(super) instigator: TunnelInstigator,
}

impl<S: TunnelScaffolding> NewAllocationContext<S> {
    /// Start a background task for managing one TURN allocation
    ///
    /// Uses the provided existing allocation, or creates a new one if None.
    ///
    /// This task will live until the underlying TCP connection closes for any
    /// reason or the mapping expires. If additional datagrams are sent to its
    /// mapped address after the task has ended, it will be restarted.
    ///
    /// If the allocation handshake completes successfully, this launches
    /// a second task for the incoming datagram path before handling the
    /// outgoing datagram path.
    ///
    /// This per-allocation task waits for DNS resolution where necessary, to
    /// establish a bidirectional mapping between the fake generated IP addresses
    /// used within the tunnel and the external IP addresses we must use with
    /// the TURN relay.
    ///
    pub(super) fn launch_allocation_task(
        self,
        allocation: Option<AllocationReaderWriter>,
        dns_cookies: LockedDnsCookies,
        recv_notify: Arc<Notify>,
        mapped_addr: SocketAddr,
        incoming_sender: mpsc::Sender<UdpData>,
        mut receiver: mpsc::Receiver<UdpData>,
    ) -> JoinHandle<()> {
        tokio::task::spawn(async move {
            let AllocationReaderWriter {
                mut allocation,
                reader,
                mut writer,
            } = match allocation {
                Some(prebuilt) => {
                    tracing::debug!(
                        "Adopting prebuilt allocation for {:?} {:?}, connected to TURN server {:?}",
                        sensitive(&self.isolation),
                        sensitive(&self.instigator),
                        sensitive(&self.config.tor_addr)
                    );
                    prebuilt
                }
                None => {
                    tracing::debug!(
                        "Starting new allocation for {:?} {:?}, connecting to TURN server {:?}",
                        sensitive(&self.isolation),
                        sensitive(&self.instigator),
                        sensitive(&self.config.tor_addr)
                    );
                    match AllocationReaderWriter::new(&self).await {
                        Ok(v) => v,
                        Err(_already_logged) => return,
                    }
                }
            };

            let counter = self
                .scaffolding
                .get_bandwidth_counter(self.isolation.app_id);

            // Start the reader task, which consumes peer mappings and produces `TurnReaderSignal`s.
            // The peer mappings account for the TURN client's channel bindings as well as the fake IP addresses
            // created by the DNS subsystem.
            let (peer_map_sender, peer_map_receiver) = mpsc::channel(MAX_CHANNEL_BINDING_BACKLOG);
            let (signal_sender, mut signal_receiver) = mpsc::channel(MAX_READER_SIGNAL_BACKLOG);
            Self::launch_reader_task(
                reader,
                mapped_addr,
                IncomingDatagramSender {
                    recv_notify,
                    udp_sender: incoming_sender,
                },
                peer_map_receiver,
                signal_sender,
                counter.clone(),
            );

            // Keep a local memo for dns_cookies.get_resolved(), so we don't have to acquire the lock at every datagram
            let mut resolver_memo: HashMap<(Option<u64>, IpAddress), Option<IpAddress>> =
                Default::default();

            // Now handle incoming UdpData from the caller and signals from the reader task
            tracing::trace!(
                "Starting write loop for {:?} {:?}",
                sensitive(self.isolation),
                sensitive(self.instigator)
            );
            loop {
                tokio::select! {
                    signal = signal_receiver.recv() => {
                        match signal {
                            Some(signal) => {
                                tracing::trace!("Handling signal in TURN client");
                                if let Err(e) = allocation.handle_signal(signal, Instant::now()) {
                                    tracing::debug!("Error handling signal in TURN client, {:?}", e);
                                    return;
                                }
                            }
                            None => {
                                tracing::debug!("Ending TURN allocation because signal channel closed");
                                return;
                            }
                        }
                    }
                    udp_data = receiver.recv() => {
                        match udp_data {
                            Some(udp_data) => {
                                // Look up the real IP address, using a memo to avoid excessive DnsCookies locking
                                let resolver_key: (Option<u64>, IpAddress) = (Some(self.isolation.app_id), udp_data.dest.ip().into());
                                let resolver_result = match resolver_memo.get(&resolver_key) {
                                    None => {
                                        let resolver_result = DnsCookies::get_resolved(&dns_cookies, &self.arti, resolver_key.0, &resolver_key.1).await;
                                        resolver_memo.insert(resolver_key, resolver_result);
                                        tracing::debug!("UDP from {:?} to {:?}, using real address {:?}", sensitive(udp_data.source), sensitive(udp_data.dest ), sensitive(resolver_result));
                                        resolver_result
                                    },
                                    Some(result) => result.to_owned()
                                };
                                match resolver_result {
                                    None => {
                                        tracing::debug!("Dropping outgoing UDP, can't determine real destination IP for {:?}", sensitive(resolver_key));
                                    }
                                    Some(real_ip_addr) => {
                                        // Now that the real IP addr is known, pass it to the TURN client.
                                        // New bindings are propagated back to the reader using matching IP addresses.
                                        let real_dest = SocketAddr::new(real_ip_addr.into(), udp_data.dest.port());
                                        match allocation.send(&mut writer, Instant::now(), real_dest, &udp_data.data).await {
                                            Err(e) => {
                                                tracing::debug!("Error sending datagram in TURN client, {:?}", e);
                                                return;
                                             }
                                             Ok(status) => {
                                        // Report sent bandwidth
                                        if let Some(counter) = &counter {
                                            counter.on_tx(udp_data.data.len() as u64);
                                        }
                                        if let ChannelBindAction::Created = status.action {
                                            // Propagate new channel bindings to our other task
                                            if peer_map_sender.send((udp_data.dest, status.info.number)).await.is_err() {
                                                tracing::debug!("Ending TURN allocation because peer map channel closed");
                                                return;
                                            }
                                            // Report this to the scaffolding as a new destination address
                                            // TODO: Can't get tor_circuit from the writer half
                                            // scaffolding.on_established(ConnectionDetails {
                                            //     proxy_src: self.instigator.proxy_src,
                                            //     proxy_dst: self.instigator.proxy_dst,
                                            //     tor_dst: &self.tor_addr,
                                            //     isolation_key: self.isolation.app_id,
                                            //     tor_circuit: writer.inner().ctrl().circuit(),
                                            // });
                                        }
                                    }
                                }
                            }}}
                            None => {
                                tracing::debug!("Ending TURN allocation because main channel closed");
                                return;
                            }
                        }
                    }
                }
            }
        })
    }

    /// Start a background task for the reader half of one active allocation
    ///
    /// This task's main job is to read incoming datagrams from a `TurnTcpRead`
    /// and direct them to the `IncomingDatagramSender`. Doing this requires
    /// that we maintain a mapping from TURN channel number back to peer address.
    /// This mapping can be updated by incoming channel messages. We also provide
    /// an outgoing channel for "signals" that the TURN client must transport
    /// from its read half back to its write half, to manage nonce rotation.
    ///
    /// The task will live until the underlying TCP connection closes for any
    /// reason or the mapping expires. If additional datagrams are sent to its
    /// mapped address after the task has ended, it will be restarted.
    ///
    /// If the allocation handshake completes successfully, this launches
    /// a second task for the incoming datagram path before handling the
    /// outgoing datagram path.
    ///
    fn launch_reader_task(
        mut reader: TurnTcpRead<DataReader>,
        mapped_addr: SocketAddr,
        incoming_sender: IncomingDatagramSender,
        mut peer_map_receiver: mpsc::Receiver<(SocketAddr, ChannelNumber)>,
        signal_sender: mpsc::Sender<TurnReaderSignal>,
        counter: Option<Arc<BandwidthCounter>>,
    ) -> JoinHandle<()> {
        tokio::task::spawn(async move {
            let mut peer_map: HashMap<ChannelNumber, SocketAddr> = HashMap::new();
            tracing::trace!("Starting read loop for {:?}", sensitive(mapped_addr));
            loop {
                let read_result = TurnAllocation::read(&mut reader).await;

                // Always drain the peer_map_receiver before processing each read
                while let Ok((peer_addr, peer_channel)) = peer_map_receiver.try_recv() {
                    peer_map.insert(peer_channel, peer_addr);
                }

                match read_result {
                    Err(e) => {
                        tracing::debug!("Ending TURN reader because of read error, {:?}", e);
                        return;
                    }
                    Ok(TurnAllocationRead::Signal(s)) => {
                        if signal_sender.send(s).await.is_err() {
                            tracing::debug!(
                                "Ending TURN reader because signal channel disconnected"
                            );
                            return;
                        }
                    }
                    Ok(TurnAllocationRead::ChannelData(channel, data)) => {
                        match peer_map.get(&channel) {
                            None => {
                                tracing::debug!(
                                    "Unexpected incoming TURN datagram on {:?}",
                                    channel
                                );
                            }
                            Some(peer) => {
                                if let Some(counter) = &counter {
                                    counter.on_rx(data.len() as u64);
                                }
                                incoming_sender
                                    .send(UdpData {
                                        source: *peer,
                                        dest: mapped_addr,
                                        data,
                                    })
                                    .await;
                            }
                        }
                    }
                }
            }
        })
    }
}

/// Pre-built Turn allocation stored with its reader and writer
///
/// Live allocations aren't stored like this, since the individual pieces
/// become owned by different tasks. These pre-built allocations stay inactive
/// unless they are being refreshed.
///
pub(super) struct AllocationReaderWriter {
    pub(super) allocation: TurnAllocation,
    pub(super) reader: TurnTcpRead<DataReader>,
    pub(super) writer: TurnTcpWrite<DataWriter>,
}

impl AllocationReaderWriter {
    /// Create a new connection and TurnAllocation from a NewAllocationContext
    ///
    /// Successes and failures are reported to the Scaffolding.
    ///
    /// On success, returns the TurnAllocation as well as the separate
    /// TurnTcpRead and TurnTcpWrite instances for the connection,
    /// as a AllocationReaderWriter.
    ///
    pub(super) async fn new<S: TunnelScaffolding>(
        context: &NewAllocationContext<S>,
    ) -> Result<AllocationReaderWriter, ()> {
        let mut stream_prefs = StreamPrefs::new();
        stream_prefs.connect_to_onion_services(BoolOrAuto::Explicit(true));
        stream_prefs.set_isolation(context.isolation);
        if context.address_family == REQUESTED_FAMILY_IPV4 {
            stream_prefs.ipv4_preferred();
        }
        if context.address_family == REQUESTED_FAMILY_IPV6 {
            stream_prefs.ipv6_preferred();
        }
        if let Some(cc) = context.country_code {
            stream_prefs.exit_country(cc);
        }

        let arti_stream = match context
            .arti
            .connect_with_prefs(context.config.tor_addr.clone(), &stream_prefs)
            .await
        {
            Ok(v) => v,
            Err(e) => {
                tracing::error!(
                    "Arti failure while connecting to TURN relay server, {:?}",
                    e
                );
                context
                    .scaffolding
                    .on_arti_failure(FailedConnectionDetails {
                        proxy_src: context.instigator.proxy_src,
                        proxy_dst: context.instigator.proxy_dst,
                        tor_dst: context.config.tor_addr.clone(),
                        isolation_key: context.isolation.app_id,
                        error: e,
                    });
                return Err(());
            }
        };
        tracing::debug!(
            "Connected to TURN server {:?}, for {:?} {:?}",
            sensitive(&context.config.tor_addr),
            sensitive(&context.isolation),
            sensitive(&context.instigator)
        );

        let tor_circuit = &arti_stream
            .client_stream_ctrl()
            .ok_or(())?
            .tunnel()
            .ok_or(())?;
        let tor_circuit = tor_circuit.as_single_circ().map_err(|_| ())?;

        context.scaffolding.on_established(ConnectionDetails {
            proxy_src: context.instigator.proxy_src,
            proxy_dst: context.instigator.proxy_dst,
            tor_dst: &context.config.tor_addr,
            isolation_key: context.isolation.app_id,
            tor_circuit,
        });

        // Complete the "Allocation" handshake, authenticating to the server if requested
        let (reader, writer) = arti_stream.split();
        let mut reader = TurnTcpRead::new(reader);
        let mut writer = TurnTcpWrite::new(writer);
        match TurnAllocation::new(
            &mut reader,
            &mut writer,
            &context.config.auth,
            RequestedAddressFamily(context.address_family.0),
            Instant::now(),
        )
        .await
        {
            Ok(allocation) => {
                tracing::debug!(
                    "UDP allocation for {:?} {:?} complete, relayed address is {:?}",
                    sensitive(&context.isolation),
                    sensitive(&context.instigator),
                    sensitive(&allocation.relayed_address())
                );
                Ok(Self {
                    allocation,
                    reader,
                    writer,
                })
            }
            Err(e) => {
                tracing::error!(
                    "Can't establish a TURN allocation for {:?} {:?}, {:?}",
                    sensitive(context.isolation),
                    sensitive(&context.instigator),
                    e
                );
                Err(())
            }
        }
    }
}
