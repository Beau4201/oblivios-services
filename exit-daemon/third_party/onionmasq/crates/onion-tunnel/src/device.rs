//! Utilities for inspecting packets before they're passed to `smoltcp`.

use smoltcp::phy::{Device, DeviceCapabilities, RxToken as SmolRxToken};
use smoltcp::time::Instant;
use std::collections::VecDeque;

pub type Packet = Vec<u8>;

/// A `smoltcp::phy::Device` that allows inspecting received packets before they're processed
/// by the rest of the `smoltcp` stack.
///
/// # Usage
///
/// By default, this device won't ever propagate packets up to the rest of the `smoltcp` stack;
/// instead, it reads packets from its wrapped device and enqueues them.
///
/// In order to get it to propagate packets, you must call `inspect_packets` to inspect a set
/// of queued packets, take any actions you'd like based on the contents of the packets, and then
/// call `done_inspecting` to mark the inspected packets as ready for forwarding. You must then
/// call the smoltcp `Interface::poll` function in order to make progress.
pub struct VirtualDevice<D> {
    device: D,
    unprocessed: VecDeque<Packet>,
    processed: VecDeque<Packet>,
    processed_usable: bool,
    transmit_exhausted: bool,
}

impl<D> VirtualDevice<D>
where
    D: Device,
{
    /// Wrap an existing `smoltcp::phy::Device`.
    pub fn new(device: D) -> Self {
        Self {
            device,
            unprocessed: VecDeque::new(),
            processed: VecDeque::new(),
            processed_usable: false,
            transmit_exhausted: false,
        }
    }

    /// Inspect all unprocessed packets that have been queued up by the `VirtualDevice` since the
    /// last time this was called.
    ///
    /// `inspector` will be called with each packet.
    /// It may return the packet unmodified, modified, or not at all.
    ///
    /// You MUST call `done_inspecting` (and then poll the smoltcp interface) in order to make
    /// progress when you're done acting on the packet contents.
    pub fn inspect_packets<F>(&mut self, mut inspector: F)
    where
        F: FnMut(Packet) -> Option<Packet>,
    {
        self.processed_usable = false;
        for pkt in self.unprocessed.drain(..) {
            if let Some(pkt) = inspector(pkt) {
                self.processed.push_back(pkt);
            }
        }
    }

    /// Allow the `VirtualDevice` to forward packets that were inspected with `inspect_packets`.
    pub fn done_inspecting(&mut self) {
        self.processed_usable = true;
    }

    /// Check whether the underlying device signaled buffer exhaustion the last time we tried to
    /// write to it.
    pub fn check_exhausted(&self) -> bool {
        self.transmit_exhausted
    }

    /// Move packets into the unprocessed queue.
    #[allow(clippy::type_complexity)]
    fn queue(
        &mut self,
    ) -> Option<(
        <VirtualDevice<D> as Device>::RxToken<'_>,
        <VirtualDevice<D> as Device>::TxToken<'_>,
    )>
    where
        for<'a> D: 'a,
    {
        // We receive packets from our internal device and discard the Tx-token since we can
        // regenerate that from the transmit() method later.
        while let Some((rx_token, _tx_token)) = self.device.receive(Instant::now()) {
            // We consume the Rx-token to extract the packet content for inspection.
            let packet = rx_token.consume(|packet| Packet::from(packet));

            // Append the packet to our queue.
            self.unprocessed.push_back(packet);
        }
        None
    }

    /// Yield packets from our processed queue.
    #[allow(clippy::type_complexity)]
    fn forward(
        &mut self,
    ) -> Option<(
        <VirtualDevice<D> as Device>::RxToken<'_>,
        <VirtualDevice<D> as Device>::TxToken<'_>,
    )>
    where
        for<'a> D: 'a,
    {
        if let Some(device_tx_token) = self.device.transmit(Instant::now()) {
            self.processed.pop_front().map(|packet| {
                let rx_token = RxToken { packet };
                let tx_token = TxToken {
                    lower: device_tx_token,
                };
                (rx_token, tx_token)
            })
        } else {
            None
        }
    }
}

#[derive(Debug)]
pub struct RxToken {
    packet: Packet,
}

impl smoltcp::phy::RxToken for RxToken {
    fn consume<R, F>(mut self, f: F) -> R
    where
        F: FnOnce(&[u8]) -> R,
    {
        f(&mut self.packet)
    }
}

pub struct TxToken<Tx: smoltcp::phy::TxToken> {
    lower: Tx,
}

impl<Tx: smoltcp::phy::TxToken> smoltcp::phy::TxToken for TxToken<Tx> {
    fn consume<R, F>(self, len: usize, f: F) -> R
    where
        F: FnOnce(&mut [u8]) -> R,
    {
        self.lower.consume(len, f)
    }
}

impl<D> Device for VirtualDevice<D>
where
    D: Device + 'static,
{
    type RxToken<'a> = RxToken;
    type TxToken<'a> = TxToken<<D as Device>::TxToken<'a>>;

    fn receive(&mut self, _: Instant) -> Option<(Self::RxToken<'_>, Self::TxToken<'_>)> {
        if !self.processed.is_empty() && self.processed_usable {
            self.forward()
        } else {
            self.queue()
        }
    }

    fn transmit(&mut self, i: Instant) -> Option<Self::TxToken<'_>> {
        let ret = self.device.transmit(i).map(|lower| TxToken { lower });
        self.transmit_exhausted = ret.is_none();
        ret
    }

    fn capabilities(&self) -> DeviceCapabilities {
        self.device.capabilities()
    }
}
