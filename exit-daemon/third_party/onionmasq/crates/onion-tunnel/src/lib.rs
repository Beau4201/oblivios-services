#![warn(clippy::unwrap_used)]
#![warn(clippy::expect_used)]
#![allow(clippy::result_large_err)]

pub mod accounting;
pub mod config;
mod device;
mod dns;
pub mod errors;
mod isolation;
mod parser;
mod proxy;
mod runtime;
pub mod scaffolding;
mod socket;
mod tuntap;
#[cfg(feature = "experimental-udp-tunnel")]
pub(crate) mod udp_tunnel;

use arti_client::status::BootstrapEvents;
pub use arti_client::status::BootstrapStatus;
pub use arti_client::CountryCode;
use arti_client::TorClient;
use device::VirtualDevice;
use dns::{DnsManager, LockedDnsCookies};
use futures::stream::StreamExt;
use futures::{future::Either, FutureExt};
use onionmasq_device_testing::{AsyncDevice, PollableAsyncDevice};
use proxy::ArtiProxy;
use runtime::OnionTunnelArtiRuntime;
use safelog::sensitive;
use smoltcp::{
    iface::{Interface, PollResult},
    phy::Medium,
    wire::{IpAddress, IpCidr, Ipv4Address, Ipv6Address},
};
#[cfg(feature = "pcap")]
use std::fs::File;
use std::pin::Pin;
use std::sync::Mutex;
use std::{collections::HashMap, os::fd::AsRawFd};
use std::{io, sync::Arc};
use tracing::{debug, error, trace, warn};

use crate::config::TunnelConfig;
use crate::errors::{TunnelError, TunnelResult};
use crate::isolation::OnionIsolationKey;
use crate::parser::ParseResult;
use crate::scaffolding::TunnelCommand;
pub use crate::scaffolding::TunnelScaffolding;
// ObliviOS patch 0004: the fake address ranges, so that the exit daemon can check its steering rules against them.
pub use crate::dns::{is_fake_address, FAKE_V4_PREFIX, FAKE_V6_PREFIX};
use crate::tuntap::AsyncTunTapInterface;
use crate::{parser::Parser, socket::TcpSocket};
pub use arti_client::config as tor_config;
pub use arti_client::{config::TorClientConfigBuilder, TorClientConfig};
use rand::Rng;
use smoltcp::iface::{SocketHandle, SocketSet};
#[cfg(feature = "pcap")]
use smoltcp::phy::{PcapMode, PcapWriter};
use smoltcp::socket::tcp::State as TcpState;
use smoltcp::socket::Socket;
use smoltcp::time::{Duration, Instant};
pub use smoltcp::wire::IpEndpoint;
use tokio::io::{AsyncRead, AsyncWrite};
use tokio::sync::Notify;
use tokio::task::JoinHandle;
use tor_geoip::HasCountryCode;

const ASYNC_DEVICE_BUFFER_SIZE: usize = 1500 * 4;

/// The type of the device smoltcp uses to send and receive packets.
///
/// Uses `VirtualDevice` so that we can inspect the packets before passing them on.
type TunnelDevice = VirtualDevice<AsyncDevice<ASYNC_DEVICE_BUFFER_SIZE>>;

/// A wrapper for the `TunnelDevice` that might wrap it in a `PcapWriter`, or not.
enum TunnelDeviceWrapper {
    #[cfg(feature = "pcap")]
    Pcap(PcapWriter<TunnelDevice, File>),
    Raw(TunnelDevice),
}

impl TunnelDeviceWrapper {
    fn inner(&mut self) -> &mut TunnelDevice {
        match self {
            TunnelDeviceWrapper::Raw(ref mut d) => d,
            #[cfg(feature = "pcap")]
            TunnelDeviceWrapper::Pcap(ref mut d) => d.get_mut(),
        }
    }
}

/// Information about a proxy task.
struct ProxyInformation {
    /// The join handle for the proxy task.
    handle: JoinHandle<()>,
    /// The isolation key used for the connection.
    isolation_key: u64,
}

pub struct OnionTunnel<S = (), D = AsyncTunTapInterface>
where
    S: TunnelScaffolding,
{
    /// The smoltcp interface type.
    interface: Interface,
    /// The smoltcp socket set.
    ///
    /// This is wrapped inside a mutex to allow other tasks to send and receive on the sockets.
    socket_set: Arc<Mutex<SocketSet<'static>>>,
    /// The device smoltcp uses to send and receive packets.
    device: TunnelDeviceWrapper,
    /// The pollable async half of the `AsyncDevice`.
    pollable_adev: PollableAsyncDevice<D, ASYNC_DEVICE_BUFFER_SIZE>,
    arti: Arc<TorClient<OnionTunnelArtiRuntime<S>>>,
    dns_cookies: Option<LockedDnsCookies>,
    scaffolding: Arc<S>,
    /// A `Notify` used to make the main loop do a poll.
    poll_notify: Arc<Notify>,
    /// An index of smoltcp socket handles to the `proxy()` task join handle handling that socket.
    proxy_handles: HashMap<SocketHandle, ProxyInformation>,
    /// The join handle for the `dns()` task.
    dns_join_handle: Option<JoinHandle<()>>,
    /// The current 'global isolation epoch', which we increment every time a new identity is
    /// requested.
    ///
    /// NOTE(eta): This should be incremented with wrapping logic, to prevent panic on overflow.
    global_epoch: u16,
    /// A map of isolation keys to their individual 'app epoch', which works like the global one
    /// except per-app. This is only touched when we actually increment the epoch, to save space.
    app_epochs: HashMap<u64, u16>,
    /// Optional UDP tunnel
    #[cfg(feature = "experimental-udp-tunnel")]
    udp_tunnel: udp_tunnel::UdpTunnel,
}

impl<S> OnionTunnel<S, AsyncTunTapInterface>
where
    S: TunnelScaffolding,
{
    /// Create an `OnionTunnel` from a file descriptor, representing a TUN device.
    pub async fn create_with_fd<F: AsRawFd>(
        scaffolding: S,
        fd: F,
        config: TunnelConfig,
    ) -> TunnelResult<Self> {
        debug!("OnionTunnel::create_with_fd()");
        let tuntap = match AsyncTunTapInterface::new_from_fd(fd) {
            Ok(v) => {
                debug!("successfully created tun interface");
                v
            }
            Err(e) => {
                warn!("couldn't create tun interface: {}", e);
                return Err(TunnelError::TunSetup(e));
            }
        };

        Self::create_custom(scaffolding, tuntap, config).await
    }

    /// Create an `OnionTunnel` from a TUN device name.
    #[cfg(any(target_os = "linux", target_os = "android"))]
    pub async fn new(scaffolding: S, iface_name: &str, config: TunnelConfig) -> TunnelResult<Self> {
        debug!("OnionTunnel::new({iface_name:?})");
        let tun =
            AsyncTunTapInterface::new(iface_name, Medium::Ip).map_err(TunnelError::TunSetup)?;

        Self::create_custom(scaffolding, tun, config).await
    }

    /// Return the inner TorClient used by Onionmasq
    ///
    /// The returned client is isolated from any connections Onionmasq may attempt.
    pub fn get_tor_client(&self) -> Arc<TorClient<OnionTunnelArtiRuntime<S>>> {
        self.arti.isolated_client()
    }
}

impl<S, D> OnionTunnel<S, D>
where
    S: TunnelScaffolding,
    D: AsyncRead + AsyncWrite,
{
    /// Create an `OnionTunnel` with a completely custom device `D`.
    ///
    /// # Warning
    ///
    /// The device must behave in accordance with the restrictions on `AsyncDevice::new`.
    /// In particular, it MUST read and write whole packets at a time (i.e. one call to `poll_read`
    /// must read a whole packet or nothing, and one call to `poll_write` must write a whole packet
    /// or nothing). Splitting reads and writes will break the tunnel.
    pub async fn create_custom(
        scaffolding: S,
        device: D,
        config: TunnelConfig,
    ) -> TunnelResult<Self> {
        // FIXME(eta): MTU?
        let (adev, pollable_adev) = AsyncDevice::new(device, Medium::Ip, 1500);

        let scaffolding = Arc::new(scaffolding);

        let rt = runtime::make_runtime(Arc::clone(&scaffolding)).await;
        let arti_config = config.arti_config(Arc::clone(&scaffolding))?;
        let arti_builder = TorClient::with_runtime(rt).config(arti_config);
        let arti = arti_builder
            .create_unbootstrapped_async()
            .await
            .map_err(TunnelError::ArtiSetup)?;
        let mut device = VirtualDevice::new(adev);

        let mut iface_config = smoltcp::iface::Config::new(smoltcp::wire::HardwareAddress::Ip);
        iface_config.random_seed = rand::rng().next_u64();

        let mut iface = Interface::new(iface_config, &mut device, Instant::now());

        iface.set_any_ip(true);

        // FIXME(eta): I think we just need to insert these to make packets flow, and they aren't
        //             actually relevant for anything. This assumption should probably be checked.
        #[allow(clippy::expect_used)]
        iface
            .routes_mut()
            .add_default_ipv4_route(Ipv4Address::new(0, 0, 0, 1))
            .expect("smoltcp routing table full?");

        #[allow(clippy::expect_used)]
        iface
            .routes_mut()
            .add_default_ipv6_route(Ipv6Address::from([0xfe80, 0, 0, 0, 0, 0, 0, 0x1]))
            .expect("smoltcp routing table full?");
        /* TODO MSRV 1.91: use from_segments when ip_from is stable
        iface
            .routes_mut()
            .add_default_ipv6_route(Ipv6Address::from_segments([0xfe80, 0, 0, 0, 0, 0, 0, 0x1]))
            .expect("smoltcp routing table full?");
        */

        iface.update_ip_addrs(|ip_addrs| {
            #[allow(clippy::expect_used)]
            ip_addrs
                .push(IpCidr::new(IpAddress::v4(0, 0, 0, 1), 0))
                .expect("smoltcp IP address vector not big enough?")
        });

        iface.update_ip_addrs(|ip_addrs| {
            #[allow(clippy::expect_used)]
            ip_addrs
                .push(IpCidr::new(IpAddress::v6(0xfe80, 0, 0, 0, 0, 0, 0, 0x1), 0))
                .expect("smoltcp IP address vector not big enough?")
        });

        let socket_set = Arc::new(Mutex::new(SocketSet::new(vec![])));

        #[cfg(feature = "pcap")]
        let device = match config.pcap_path {
            Some(v) => {
                error!("*** WARNING WARNING WARNING ***");
                error!("All tunnel traffic is being logged to {}. Do not use the tunnel for anything sensitive!", v.to_string_lossy());
                error!("*** WARNING WARNING WARNING ***");

                let file = File::create(v).map_err(TunnelError::PcapSetup)?;
                let pcap = PcapWriter::new(device, file, PcapMode::Both);
                TunnelDeviceWrapper::Pcap(pcap)
            }
            None => TunnelDeviceWrapper::Raw(device),
        };
        #[cfg(not(feature = "pcap"))]
        let device = TunnelDeviceWrapper::Raw(device);

        let poll_notify = Arc::new(Notify::new());

        Ok(Self {
            #[cfg(feature = "experimental-udp-tunnel")]
            udp_tunnel: udp_tunnel::UdpTunnel::new(poll_notify.clone()),
            interface: iface,
            device,
            socket_set,
            arti,
            pollable_adev,
            dns_cookies: None,
            scaffolding,
            poll_notify,
            proxy_handles: HashMap::new(),
            dns_join_handle: None,
            global_epoch: 0,
            app_epochs: Default::default(),
        })
    }

    fn proxy(
        &mut self,
        socket: TcpSocket,
        source: IpEndpoint,
        dest: IpEndpoint,
    ) -> TunnelResult<ProxyInformation> {
        let app_id = match self.scaffolding.isolate(source, dest, 6) {
            Ok(v) => v,
            Err(e) => {
                socket.abort();
                return Err(TunnelError::ScaffoldingIsolation(e));
            }
        };
        let country_code = self.scaffolding.locate(source, dest, app_id);
        let isolation = OnionIsolationKey {
            app_id,
            app_epoch: self.app_epochs.get(&app_id).copied().unwrap_or(0),
            global_epoch: self.global_epoch,
        };
        let handle = socket.handle();
        trace!(
            "socket {}: new proxied connection {}->{}, isolation {:?}, country {:?}",
            handle,
            sensitive(source),
            sensitive(dest),
            sensitive(isolation),
            sensitive(country_code)
        );
        // When new TCP connections start, use this as one opportunity to maintain pre-built UDP tunnels
        #[cfg(feature = "experimental-udp-tunnel")]
        let _ = self
            .udp_tunnel
            .prebuild_sender()
            .try_send(udp_tunnel::UdpPrebuildRequest {
                isolation_key: isolation.app_id,
                country_code,
                address_family: match dest.addr {
                    IpAddress::Ipv4(_) => {
                        experimental_turn_tcp_client::proto::REQUESTED_FAMILY_IPV4
                    }
                    IpAddress::Ipv6(_) => {
                        experimental_turn_tcp_client::proto::REQUESTED_FAMILY_IPV6
                    }
                },
            });
        let scaff = Arc::clone(&self.scaffolding);
        #[allow(clippy::unwrap_used)]
        let mut proxy = ArtiProxy {
            socket: socket.clone(),
            arti: self.arti.clone(),
            cookies: self.dns_cookies.as_ref().unwrap().clone(),
            isolation_key: isolation,
            country_code,
            scaffolding: Arc::clone(&scaff),
            source,
            dest,
        };
        let handle = tokio::spawn(async move {
            match proxy.start().await {
                Ok(_) => {
                    trace!("socket {}: connection closed", handle);
                    socket.close();
                    scaff.on_socket_close(source, dest, None);
                }
                Err(e) => {
                    trace!("socket {}: connection failed: {}", handle, e);
                    socket.abort();
                    scaff.on_socket_close(source, dest, Some(e));
                }
            }
        });
        Ok(ProxyInformation {
            handle,
            isolation_key: app_id,
        })
    }

    fn dns(&mut self) -> TunnelResult<(JoinHandle<()>, LockedDnsCookies)> {
        // Spawn the DNS manager service.
        let mut dns_manager = DnsManager::new(self)?;
        let dns_cookies = dns_manager.cookies();
        Ok((
            tokio::spawn(async move { dns_manager.start().await }),
            dns_cookies,
        ))
    }

    fn poll(&mut self) -> bool {
        #[allow(clippy::unwrap_used)]
        let mut socket_set = self.socket_set.lock().unwrap();

        // TODO: Maybe it is better to return smoltcp::iface::PollResult here?
        self.interface
            .poll(Instant::now(), self.device.inner(), &mut socket_set)
            == PollResult::SocketStateChanged
    }

    fn poll_delay(&mut self) -> Option<Duration> {
        #[allow(clippy::unwrap_used)]
        let socket_set = self.socket_set.lock().unwrap();
        self.interface.poll_delay(Instant::now(), &socket_set)
    }

    fn handle_command(&mut self, cmd: TunnelCommand) {
        match cmd {
            TunnelCommand::UpdateConfig(config) => {
                trace!("Updating tunnel configuration");
                match config.arti_config(self.scaffolding.clone()) {
                    Ok(new_config) => {
                        if let Err(e) = self
                            .arti
                            .reconfigure(&new_config, tor_config::Reconfigure::WarnOnFailures)
                        {
                            warn!("Unable to reconfigure arti's config: {}", e);
                        }
                    }
                    Err(e) => warn!("Unable to generate arti config: {}", e),
                }
            }
            TunnelCommand::RefreshCircuits => {
                trace!("Refreshing circuits.");
                self.global_epoch = self.global_epoch.wrapping_add(1);
                #[cfg(feature = "experimental-udp-tunnel")]
                self.udp_tunnel.remove_all_allocations();
                #[allow(clippy::unwrap_used)]
                for (_, s) in self.socket_set.lock().unwrap().iter_mut() {
                    if let Socket::Tcp(s) = s {
                        s.abort();
                    }
                }
            }
            TunnelCommand::RefreshCircuitsForApp { isolation_key } => {
                trace!("Refreshing circuits for app {}.", sensitive(isolation_key));
                let entry = self.app_epochs.entry(isolation_key).or_insert(0);
                *entry = entry.wrapping_add(1);
                #[cfg(feature = "experimental-udp-tunnel")]
                self.udp_tunnel.remove_allocations_for_app_id(isolation_key);
                #[allow(clippy::unwrap_used)]
                for (h, s) in self.socket_set.lock().unwrap().iter_mut() {
                    if let Some(info) = self.proxy_handles.get(&h) {
                        if let Socket::Tcp(s) = s {
                            if info.isolation_key == isolation_key {
                                s.abort();
                            }
                        }
                    }
                }
            }
            TunnelCommand::SetDormant(b) => {
                let mode = if b {
                    arti_client::DormantMode::Soft
                } else {
                    arti_client::DormantMode::Normal
                };
                trace!("Setting arti in dormant mode: {:?}", mode);
                self.arti.set_dormant(mode);
            }
        }
    }

    pub async fn run(&mut self) -> TunnelResult<()> {
        debug!("Starting tunnel...");
        let tc = self.arti.clone();
        // We do bootstrapping by continously pulling events from the event
        // stream.  If the event stream reports that the client is usable, then
        // nice: Exit cleanly.  If the event stream reports that we are blocked,
        // fail with this instead.  This approach is better than a timeout logic
        // because it uses native ARti features to achieve this goal.
        let mut bootstrap_task = Box::pin(tokio::spawn(async move {
            tc.bootstrap()
                .await
                .map_err(TunnelError::BootstrapFailure)?;
            let mut events = tc
                .chanmgr()
                .map_err(TunnelError::BootstrapFailure)?
                .bootstrap_events();
            while let Some(status) = events.next().await {
                if let Some(blocked) = status.blockage() {
                    return Err(TunnelError::BootstrapBlocked(blocked));
                }
                if status.usable() {
                    break;
                }
            }
            Ok(())
        }))
        .fuse();

        // Spawn the DNS manager service.
        let (dns_join_handle, dns_cookies) = self.dns()?;
        self.dns_join_handle = Some(dns_join_handle);
        self.dns_cookies = Some(dns_cookies.clone());

        // Get a command stream, if there is one.
        let mut command_stream = Pin::from(
            self.scaffolding
                .command_stream()
                .unwrap_or_else(|| Box::new(futures::stream::pending())),
        );

        // reusable buffer
        let mut sockets = vec![];

        loop {
            // Do one poll to make sure we enqueue packets in the `VirtualDevice`'s queue.
            self.poll();

            // Inspect packets at the IP payload layer, prior to smoltcp's socket
            // implemenation. This is where we notice TCP SYN flags and allocate
            // new sockets, and this is where all non-socket-based UDP traffic exits
            // through UdpTunnel if configured.
            //
            // New sockets are queued for processing below. Other UDP datagrams
            // are sent to UdpTunnel's send queues. Note that we never allow a
            // backlog of outgoing UDP datagrams to block the device. We prefer
            // to drop UDP rather than stalling the TCP connections as well.
            //
            self.device
                .inner()
                .inspect_packets(|packet| match Parser::parse(&packet) {
                    None => Some(packet),
                    Some(ParseResult::Icmp) => None,
                    Some(ParseResult::NewTcpSocket {
                        source,
                        dest,
                        socket,
                    }) => {
                        sockets.push((source, dest, socket));
                        Some(packet)
                    }
                    #[cfg(feature = "experimental-udp-tunnel")]
                    Some(ParseResult::UdpData(udp_data)) => {
                        match self.udp_tunnel.try_send(
                            &udp_tunnel::UdpSendContext {
                                arti: &self.arti,
                                scaffolding: &self.scaffolding,
                                app_epochs: &self.app_epochs,
                                global_epoch: self.global_epoch,
                                dns_cookies: &dns_cookies,
                            },
                            udp_data,
                        ) {
                            udp_tunnel::UdpTrySendResult::Accepted => {
                                // Hide this packet from smoltcp
                                None
                            }
                            udp_tunnel::UdpTrySendResult::Dropped => {
                                tracing::warn!("Dropping UDP");
                                None
                            }
                            udp_tunnel::UdpTrySendResult::Reject => {
                                // Let smoltcp send an ICMP reply
                                Some(packet)
                            }
                        }
                    }
                });

            // Also, make sure we remove old sockets that are closed.
            {
                #[allow(clippy::unwrap_used)]
                let mut socket_set = self.socket_set.lock().unwrap();

                let handles_to_remove = socket_set
                    .iter()
                    .filter_map(|(h, s)| {
                        if let Socket::Tcp(tsock) = s {
                            if tsock.state() == TcpState::Closed {
                                if let Some(task) = self.proxy_handles.get(&h) {
                                    if task.handle.is_finished() {
                                        trace!("socket {}: garbage collecting closed socket", h);
                                        self.proxy_handles.remove(&h);
                                    } else {
                                        trace!("socket {}: waiting for task to finish still", h);
                                        return None;
                                    }
                                } else {
                                    trace!(
                                        "socket {}: garbage collecting closed socket (no task)",
                                        h
                                    );
                                }
                                return Some(h);
                            }
                        }
                        None
                    })
                    .collect::<Vec<_>>();

                for hdl in handles_to_remove {
                    socket_set.remove(hdl);
                }
            }

            // Start proxying all the sockets we made. This registers them with `smoltcp`, ensuring
            // there's a corresponding listening socket for every TCP SYN we observed.`
            for (source, dest, mut socket) in sockets.drain(..) {
                socket.pause_synack(true);
                let socket = TcpSocket::new(self, socket);
                let handle = socket.handle();
                match self.proxy(socket, source, dest) {
                    Ok(v) => {
                        if self.proxy_handles.insert(handle, v).is_some() {
                            // I think smoltcp's socket handles are based on the index of the socket
                            // in smoltcp's internal backing store, so they could overlap like this.
                            // Let's just log this for now.
                            error!(
                                "{}->{}: displaced an older socket in proxy_handles!",
                                sensitive(source),
                                sensitive(dest)
                            );
                        }
                    }
                    Err(e) => {
                        trace!(
                            "{}->{}: failed to proxy: {}",
                            sensitive(source),
                            sensitive(dest),
                            e
                        );
                        // TODO(eta): maybe this error type should be `TunnelError`
                        self.scaffolding.on_socket_close(
                            source,
                            dest,
                            Some(io::Error::other(e.to_string())),
                        );
                    }
                }
            }

            // Maintain pre-built UDP tunnels, if necessary
            #[cfg(feature = "experimental-udp-tunnel")]
            if let Some(dns_cookies) = &self.dns_cookies {
                self.udp_tunnel
                    .maintain_udp_prebuild_from_queue(&udp_tunnel::UdpSendContext {
                        arti: &self.arti,
                        scaffolding: &self.scaffolding,
                        app_epochs: &self.app_epochs,
                        global_epoch: self.global_epoch,
                        dns_cookies,
                    });
            }

            // Now we've registered the sockets, we can let smoltcp have the packets we just
            // looked at.
            self.device.inner().done_inspecting();

            self.poll();

            let poll_delay = if self.device.inner().check_exhausted() {
                // Our device's transmit buffer is full, so there's no point in asking smoltcp
                // for a poll delay -- we need to wait for the buffer to clear first.
                //
                // If we don't check this, we can get into really sad situations where smoltcp
                // has data to send -- so will always return 0 -- but there's no point in polling
                // again until the AsyncDevice shifts some packets around (if we do, we just burn
                // CPU, which isn't ideal on a mobile device...)
                Either::Right(futures::future::pending::<()>())
            } else {
                self.poll_delay()
                    .map(|x| Either::Left(tokio::time::sleep(std::time::Duration::from(x))))
                    .unwrap_or_else(|| Either::Right(futures::future::pending::<()>()))
            };

            // "Transmit" UDP packets that arrived through a UdpTunnel
            #[cfg(feature = "experimental-udp-tunnel")]
            udp_tunnel::forward_tunnel_to_device(&mut self.udp_tunnel, self.device.inner());

            tokio::select! {
                _ = poll_delay => {
                    // poll delay elapsed
                }
                _ = self.poll_notify.notified() => {
                    // some other task wanted us to do a poll
                }
                cmd = command_stream.next() => {
                    match cmd {
                        Some(cmd) => self.handle_command(cmd),
                        None => {
                            debug!("Command stream hung up; stopping proxy.");
                            return Ok(());
                        }
                    }
                }
                res = self.pollable_adev.next() => {
                    // PollableAsyncDevice did something; check for an I/O error.
                    // If it returns None, panic; this can only happen if we polled it after
                    // it returned an error (impossible).
                    #[allow(clippy::expect_used)]
                    res
                        .expect("PollableAsyncDevice returned None")
                        .map_err(TunnelError::TunIo)?;
                }
                join_ret = &mut bootstrap_task => {
                    match join_ret {
                        // The bootstrap task completed without timing out.
                        Ok(bootstrap_ret) => {
                            match bootstrap_ret {
                                // Successful bootstrap.
                                Ok(_) => {
                                    debug!("Arti bootstrapping complete.");
                                    self.scaffolding.on_bootstrapped();

                                    let netdir = match self.arti.dirmgr().map_err(TunnelError::BootstrapFailure)?.timely_netdir() {
                                        Ok(v) => v,
                                        Err(e) => {
                                            warn!("Couldn't get a netdir after completed bootstrap: {e}");
                                            continue;
                                        }
                                    };

                                    let mut country_map: HashMap<String, usize> = HashMap::new();
                                    let mut count = 0;
                                    for relay in netdir.relays() {
                                        // We filter by the exit flag.
                                        if !relay.low_level_details().is_flagged_exit() {
                                            continue;
                                        }
                                        if let Some(cc) = relay.country_code() {
                                            *country_map.entry(cc.to_string()).or_insert(0) += 1;
                                            count += 1;
                                        }
                                    }
                                    debug!("Found {count} relays with country codes attached");
                                    self.scaffolding.on_directory(country_map);
                                },
                                // Bootstrap failure from arti.
                                Err(e) => return Err(e),
                            }
                        },
                        // The bootstrap task future failed with an error.
                        Err(join_err) => {
                            if join_err.is_panic() {
                                std::panic::resume_unwind(join_err.into_panic());
                            }
                        }
                    }
                }
            }
        }
    }

    pub fn get_bootstrap_events(&mut self) -> BootstrapEvents {
        self.arti.bootstrap_events()
    }
}

impl<S, D> Drop for OnionTunnel<S, D>
where
    S: TunnelScaffolding,
{
    fn drop(&mut self) {
        debug!(
            "Shutting down proxy on drop ({} handles)",
            self.proxy_handles.len()
        );
        if let Some(jh) = self.dns_join_handle.take() {
            jh.abort();
        }
        for (_, task) in self.proxy_handles.drain() {
            task.handle.abort()
        }
    }
}

pub fn version_string() -> &'static str {
    concat!(
        env!("CARGO_PKG_VERSION"),
        " ",
        env!("ONIONTUNNEL_COMMIT_HASH")
    )
}
