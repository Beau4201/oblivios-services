//! Implementation for the UDP tunnel subsystem
//!
//! Using TURN allocations that each handle a single port and a single isolation
//! domain, we provide a unified interface for sending and receiving UDP datagrams
//! with correctly mapped addresses.
//!

use crate::{
    config::TurnConfig,
    scaffolding::{UdpTunnelChoice, UdpTunnelContext},
    udp_tunnel::{
        allocation::AllocationReaderWriter, allocation::NewAllocationContext,
        allocation::TunnelInstigator, send::UdpSendContext, send::UdpTrySendResult, UdpData,
    },
    TunnelScaffolding,
};
use experimental_turn_tcp_client::proto::{
    RequestedAddressFamily, REQUESTED_FAMILY_IPV4, REQUESTED_FAMILY_IPV6,
};
use safelog::sensitive;
use smoltcp::wire::IpEndpoint;
use std::{
    collections::HashMap,
    hash::{Hash, Hasher},
    net::{Ipv4Addr, Ipv6Addr, SocketAddr},
    sync::Arc,
    time::Instant,
};
use tokio::sync::{mpsc, oneshot, Notify};
use tor_geoip::CountryCode;

/// Maximum number of outgoing datagrams to queue per [`TurnAllocation`]
const TX_QUEUE_PER_ALLOCATION: usize = 32;

/// Maximum number of incoming datagrams to queue per [`UdpTunnel`]
const RX_QUEUE_PER_TUNNEL: usize = 32;

/// Maximum number of unprocessed requests for tunnel pre-building to keep
const PREBUILD_REQUEST_BACKLOG: usize = 32;

/// Hash key for one pre-built allocation
///
/// Pre-built allocations are made before the actual mapped address or instigator
/// are known, for specific tunnel configurations. They are stored separately per
/// isolation ID (external to this struct) and they are additionally made unique
/// by country code and address family.
///
#[derive(Debug, PartialEq, Eq)]
struct PrebuiltAllocationHashKey {
    config: Arc<TurnConfig>,
    country_code: Option<CountryCode>,
    address_family: RequestedAddressFamily,
}

impl Hash for PrebuiltAllocationHashKey {
    fn hash<H: Hasher>(&self, state: &mut H) {
        self.config.hash(state);
        self.country_code.as_ref().map(|cc| cc.get()).hash(state);
        self.address_family.0.hash(state);
    }
}

/// Hash key for one sender
///
/// Senders are unique per isolation key (external to this struct) as well
/// as country code and mapped address.
///
#[derive(Debug, Clone, PartialEq, Eq)]
struct SenderHashKey {
    country_code: Option<CountryCode>,
    mapped_addr: SocketAddr,
}

impl Hash for SenderHashKey {
    fn hash<H: Hasher>(&self, state: &mut H) {
        self.country_code.as_ref().map(|cc| cc.get()).hash(state);
        self.mapped_addr.hash(state);
    }
}

/// Context for requesting the ahead-of-time construction of related UDP tunnels
#[derive(Debug)]
pub(crate) struct UdpPrebuildRequest {
    pub(crate) isolation_key: u64,
    pub(crate) country_code: Option<CountryCode>,
    pub(crate) address_family: RequestedAddressFamily,
}

/// Holds a ready-to-use prebuilt allocation or a slot where one is being prepared
#[allow(clippy::large_enum_variant)]
enum PrebuiltAllocationSlot {
    Ready(AllocationReaderWriter),
    Pending(oneshot::Receiver<Result<AllocationReaderWriter, ()>>),
}

impl PrebuiltAllocationSlot {
    /// Start a new allocation asynchronously, returning a new Pending slot for it
    fn new<S: TunnelScaffolding>(context: NewAllocationContext<S>) -> Self {
        let (sender, receiver) = oneshot::channel();
        tokio::task::spawn(async move {
            let _ = sender.send(AllocationReaderWriter::new(&context).await);
        });
        PrebuiltAllocationSlot::Pending(receiver)
    }

    /// Start asynchronously refreshing an allocation that was previously ready
    ///
    fn refresh(&mut self) {
        let (sender, receiver) = oneshot::channel();
        match std::mem::replace(self, PrebuiltAllocationSlot::Pending(receiver)) {
            PrebuiltAllocationSlot::Ready(mut arw) => {
                tokio::task::spawn(async move {
                    let result = arw
                        .allocation
                        .refresh(&mut arw.writer, Instant::now())
                        .await;
                    let result = match result {
                        Ok(()) => Ok(arw),
                        Err(e) => {
                            tracing::warn!("Failed to refresh pre-built UDP allocation, {:?}", e);
                            Err(())
                        }
                    };
                    let _ = sender.send(result);
                });
            }
            other => *self = other,
        }
    }

    /// Maintain a list of allocations, removing any that are expired or disconnected
    ///
    /// Any allocation slots left in the Ready state should actually be usable.
    ///
    fn maintain_vec(vec: &mut Vec<PrebuiltAllocationSlot>, now: Instant) {
        vec.retain_mut(|slot| slot.retain_refreshed(now))
    }

    /// Maintain a list of allocations, and if one is Ready pop it from the list.
    ///
    fn maintain_vec_and_select(
        vec: &mut Vec<PrebuiltAllocationSlot>,
        now: Instant,
    ) -> Option<AllocationReaderWriter> {
        Self::maintain_vec(vec, now);
        let selected_index = vec.iter().enumerate().find_map(|(index, slot)| match slot {
            PrebuiltAllocationSlot::Pending(_) => None,
            PrebuiltAllocationSlot::Ready(_) => Some(index),
        });
        match selected_index {
            None => None,
            Some(index) => match vec.swap_remove(index) {
                PrebuiltAllocationSlot::Pending(_) => unreachable!(),
                PrebuiltAllocationSlot::Ready(ready) => Some(ready),
            },
        }
    }

    /// Check the allocation for expiry or completion, and begin a refresh if necessary
    ///
    /// Returns `true` if the allocation slot is still usable, making this function
    /// a useful predicate for `Vec::retain_mut`. If the allocation is not yet expired
    /// but needs to be refreshed, it will be taken out of the slot and asynchronously
    /// refreshed on a background task.
    ///
    fn retain_refreshed(&mut self, now: Instant) -> bool {
        if let PrebuiltAllocationSlot::Pending(rx) = self {
            match rx.try_recv() {
                Err(oneshot::error::TryRecvError::Empty) => {
                    // Still waiting
                    return true;
                }
                Err(oneshot::error::TryRecvError::Closed) => {
                    // Task died while pending?
                    return false;
                }
                Ok(Err(())) => {
                    // Allocation failed, and the error was already reported
                    return false;
                }
                Ok(Ok(ready)) => {
                    *self = PrebuiltAllocationSlot::Ready(ready);
                }
            }
        }
        match self {
            PrebuiltAllocationSlot::Pending(_) => unreachable!(),
            PrebuiltAllocationSlot::Ready(arw) => {
                // Already expired? Lose it.
                if arw.allocation.timers().is_expired(now) {
                    return false;
                }
                // Exit without refresh if we don't need it yet
                if !arw.allocation.timers().needs_refresh(now) {
                    return true;
                }
            }
        }
        self.refresh();
        true
    }
}

/// UDP tunnel, transporting datagrams via TURN-over-Tor
///
/// Each tunnel owns zero or more active TURN Allocations, which map
/// to virtual source addresses within the VPN.
///
pub(crate) struct UdpTunnel {
    recv_notify: Arc<Notify>,
    incoming_sender: mpsc::Sender<UdpData>,
    incoming_receiver: mpsc::Receiver<UdpData>,
    senders_by_app: HashMap<u64, HashMap<SenderHashKey, mpsc::Sender<UdpData>>>,
    prebuilt_by_app: HashMap<u64, HashMap<PrebuiltAllocationHashKey, Vec<PrebuiltAllocationSlot>>>,
    prebuild_sender: mpsc::Sender<UdpPrebuildRequest>,
    prebuild_receiver: mpsc::Receiver<UdpPrebuildRequest>,
}

impl UdpTunnel {
    /// Create the UDP tunnel subsystem
    ///
    /// Receivable datagrams are internally queued prior to a wakeup on recv_notify.
    /// Upon waking up, call try_recv() to get the next available datagram.
    ///
    pub(crate) fn new(recv_notify: Arc<Notify>) -> Self {
        let (incoming_sender, incoming_receiver) = mpsc::channel(RX_QUEUE_PER_TUNNEL);
        let (prebuild_sender, prebuild_receiver) = mpsc::channel(PREBUILD_REQUEST_BACKLOG);
        Self {
            recv_notify,
            incoming_sender,
            incoming_receiver,
            senders_by_app: HashMap::new(),
            prebuilt_by_app: HashMap::new(),
            prebuild_sender,
            prebuild_receiver,
        }
    }

    /// Receive the next incoming UDP datagram if possible, without blocking
    pub(crate) fn try_recv(&mut self) -> Result<UdpData, mpsc::error::TryRecvError> {
        self.incoming_receiver.try_recv()
    }

    /// Return a sender that can be used to submit UdpPrebuildRequests from anywhere
    pub(crate) fn prebuild_sender(&self) -> &mpsc::Sender<UdpPrebuildRequest> {
        &self.prebuild_sender
    }

    /// Maintain pre-built tunnels, draining queued requests from the prebuild sender
    pub(crate) fn maintain_udp_prebuild_from_queue<S: TunnelScaffolding>(
        &mut self,
        context: &UdpSendContext<'_, '_, '_, '_, S>,
    ) {
        while let Ok(request) = self.prebuild_receiver.try_recv() {
            self.maintain_udp_prebuild(context, &request);
        }
    }

    /// Maintain our set of pre-built tunnels, creating or refreshing them as necessary
    ///
    /// This uses the supplied application ID, country code, and address family,
    /// and asks the scaffolding for a list of tunnel configurations.
    ///
    /// We synchronously remove tunnels that need work, and asynchronously
    /// handle new and refreshed prebuilt allocations in disposable background tasks.
    ///
    fn maintain_udp_prebuild<S: TunnelScaffolding>(
        &mut self,
        context: &UdpSendContext<'_, '_, '_, '_, S>,
        request: &UdpPrebuildRequest,
    ) {
        let configurations = context.scaffolding.udp_prebuild(request.isolation_key);
        let unspecified_address = if request.address_family == REQUESTED_FAMILY_IPV6 {
            IpEndpoint::new(Ipv6Addr::UNSPECIFIED.into(), 0)
        } else {
            IpEndpoint::new(Ipv4Addr::UNSPECIFIED.into(), 0)
        };
        let instigator = TunnelInstigator {
            proxy_src: unspecified_address,
            proxy_dst: unspecified_address,
        };
        for (tunnel_choice, number_of_allocations_needed) in configurations {
            match tunnel_choice {
                UdpTunnelChoice::Reject => {
                    // Ignored in udp_prebuild() results
                }
                UdpTunnelChoice::Turn(config) => {
                    // Visit the list of prebuilt allocations matching this configuration
                    let prebuilt = self
                        .prebuilt_by_app
                        .entry(request.isolation_key)
                        .or_default()
                        .entry(PrebuiltAllocationHashKey {
                            config: config.clone(),
                            country_code: request.country_code,
                            address_family: RequestedAddressFamily(request.address_family.0),
                        })
                        .or_default();

                    // Maintain each allocation in this list, removing if it's done for
                    PrebuiltAllocationSlot::maintain_vec(prebuilt, Instant::now());

                    // Add more allocations if needed to reach the requested number
                    while number_of_allocations_needed > prebuilt.len() {
                        tracing::debug!(
                            "Starting to pre-build a UDP allocation for {:?}",
                            sensitive(request)
                        );
                        prebuilt.push(PrebuiltAllocationSlot::new(NewAllocationContext {
                            arti: context.arti.clone(),
                            scaffolding: context.scaffolding.clone(),
                            config: config.clone(),
                            isolation: context.onion_isolation_key(request.isolation_key),
                            country_code: request.country_code,
                            address_family: RequestedAddressFamily(request.address_family.0),
                            instigator: instigator.clone(),
                        }));
                    }
                }
            }
        }
    }

    /// Remove all active allocations
    ///
    /// Removes all references held to active allocations. The corresponding
    /// background tasks and Tor connections will end asynchronously.
    ///
    pub(crate) fn remove_all_allocations(&mut self) {
        self.senders_by_app.clear();
        self.prebuilt_by_app.clear();
    }

    /// Remove allocations for a particular application
    ///
    /// This tears down all active UDP allocations. They will be recreated at the
    /// next outgoing datagram that requires an allocation.
    ///
    pub(crate) fn remove_allocations_for_app_id(&mut self, app_id: u64) {
        self.senders_by_app.remove(&app_id);
        self.prebuilt_by_app.remove(&app_id);
    }

    /// Try to dispatch an outgoing UDP datagram, but don't wait for buffer space.
    ///
    /// Maintains any related pre-built allocations.
    ///
    /// For every outgoing datagram, we synchronously determine the app isolation
    /// ID by using the source and destination address allocated at the current
    /// moment. If the app ID cannot be determined, we reject the packet.
    /// If the TunnelScaffolding cannot assign Turn parameters, we reject the packet.
    ///
    /// This also drops the packet if a queue fills up.
    /// If the queue does not yet exist, it is immediately created
    /// and the underlying allocation is established asynchronously.
    ///
    /// The supplied TorClient and TunnelScaffolding will be referenced by each
    /// allocation's background task.
    ///
    pub(crate) fn try_send<S: TunnelScaffolding>(
        &mut self,
        context: &UdpSendContext<'_, '_, '_, '_, S>,
        data: UdpData,
    ) -> UdpTrySendResult {
        tracing::trace!("Trying to send UDP datagram, {:?}", sensitive(&data));

        // Look up app ID and country code, necessary to locate the proper already-open tunnel
        let app_id = match context.isolate(&data) {
            Ok(app_id) => app_id,
            Err(()) => return UdpTrySendResult::Reject,
        };
        let country_code = context
            .scaffolding
            .locate(data.source.into(), data.dest.into(), app_id);
        let mapped_addr = data.source;
        let address_family = match &mapped_addr {
            SocketAddr::V4(_) => REQUESTED_FAMILY_IPV4,
            SocketAddr::V6(_) => REQUESTED_FAMILY_IPV6,
        };
        tracing::trace!(
            "Starting to send UDP datagram with app_id: {:?} country_code: {:?} mapped_addr: {:?}",
            sensitive(app_id),
            sensitive(country_code),
            sensitive(mapped_addr),
        );

        // Maintain related pre-built allocations, without indirection through the queue
        self.maintain_udp_prebuild(
            context,
            &UdpPrebuildRequest {
                isolation_key: app_id,
                country_code,
                address_family: RequestedAddressFamily(address_family.0),
            },
        );

        // Is there already a cached sender?
        // This is the fast path.
        // Fall through and retain the UdpData if there is none or it was stale.
        let data = match self
            .senders_by_app
            .get(&app_id)
            .and_then(|app_senders| {
                app_senders.get(&SenderHashKey {
                    country_code,
                    mapped_addr,
                })
            })
            .map(|sender| sender.to_owned())
        {
            None => data,
            Some(sender) => match sender.try_send(data) {
                Ok(()) => return UdpTrySendResult::Accepted,
                Err(mpsc::error::TrySendError::Full(_)) => return UdpTrySendResult::Dropped,
                Err(mpsc::error::TrySendError::Closed(data)) => data,
            },
        };

        // Prepare to set up a new sender, using a new or pre-built allocation.
        // We'll need to determine the specific UDP tunnel configuration.
        let tunnel_context = UdpTunnelContext {
            src: data.source.into(),
            initial_dst: data.dest.into(),
            isolation_key: app_id,
        };
        let config = match context.scaffolding.udp_tunnel(&tunnel_context) {
            UdpTunnelChoice::Reject => return UdpTrySendResult::Reject,
            UdpTunnelChoice::Turn(config) => config,
        };
        let instigator = TunnelInstigator {
            proxy_src: data.source.into(),
            proxy_dst: data.dest.into(),
        };
        let prebuilt_key = PrebuiltAllocationHashKey {
            config: config.clone(),
            country_code,
            address_family: RequestedAddressFamily(address_family.0),
        };
        let isolation = context.onion_isolation_key(app_id);
        let alloc_context = NewAllocationContext {
            arti: context.arti.clone(),
            scaffolding: context.scaffolding.clone(),
            config,
            isolation,
            country_code,
            address_family,
            instigator,
        };

        // Check the prebuilt allocations that would match, and if any is usable adopt it now
        let now = Instant::now();
        let allocation = self
            .prebuilt_by_app
            .get_mut(&app_id)
            .and_then(|app_prebuilt| app_prebuilt.get_mut(&prebuilt_key))
            .and_then(|vec| PrebuiltAllocationSlot::maintain_vec_and_select(vec, now));

        // Launch the allocation, don't wait for it to finish
        let (sender, receiver) = mpsc::channel(TX_QUEUE_PER_ALLOCATION);
        alloc_context.launch_allocation_task(
            allocation,
            context.dns_cookies.clone(),
            self.recv_notify.clone(),
            mapped_addr,
            self.incoming_sender.clone(),
            receiver,
        );

        // Store the sender for future use
        self.senders_by_app.entry(app_id).or_default().insert(
            SenderHashKey {
                country_code,
                mapped_addr,
            },
            sender.clone(),
        );

        match sender.try_send(data) {
            Ok(()) => UdpTrySendResult::Accepted,
            Err(_) => UdpTrySendResult::Dropped,
        }
    }
}
