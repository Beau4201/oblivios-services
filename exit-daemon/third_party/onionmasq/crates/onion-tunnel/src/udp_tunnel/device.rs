//! Integration between the UDP tunnel and smoltcp's Device interface

use crate::udp_tunnel::UdpTunnel;
use smoltcp::phy::{ChecksumCapabilities, Device, TxToken};
use smoltcp::time::Instant;
use smoltcp::wire::{IpProtocol, IpRepr, UdpPacket, UdpRepr};

/// Copy datagrams from the [`UdpTunnel`]'s recv interface to the [`Device`]
///
/// Processes datagrams from the tunnel's receive queue as long as the Device
/// has remaining transmit space. There are no errors to report at this point,
/// the only outcomes are that we make progress or don't.
///
/// TODO: We need to transmit a UDP datagram with arbitrary
///       source and destination address and port. There's no
///       good place to hook this up to smoltcp yet. This
///       implementation assembles an IP header but it won't
///       be suitable for Ethernet without additional work.
///       It's also missing IP fragmentation support.
///
pub(crate) fn forward_tunnel_to_device<D: Device>(tunnel: &mut UdpTunnel, device: &mut D) {
    while let Some(tx_token) = device.transmit(Instant::now()) {
        if let Ok(udp_data) = tunnel.try_recv() {
            let checksum_caps = ChecksumCapabilities::default();
            let hop_limit = 64;
            let udp_repr = UdpRepr {
                src_port: udp_data.source.port(),
                dst_port: udp_data.dest.port(),
            };
            let ip_repr = IpRepr::new(
                udp_data.source.ip().into(),
                udp_data.dest.ip().into(),
                IpProtocol::Udp,
                udp_repr.header_len() + udp_data.data.len(),
                hop_limit,
            );
            tx_token.consume(ip_repr.buffer_len(), |mut buffer| {
                ip_repr.emit(&mut buffer, &checksum_caps);
                let mut packet = UdpPacket::new_unchecked(&mut buffer[ip_repr.header_len()..]);
                udp_repr.emit(
                    &mut packet,
                    &ip_repr.src_addr(),
                    &ip_repr.dst_addr(),
                    udp_data.data.len(),
                    |payload| payload.copy_from_slice(&udp_data.data),
                    &checksum_caps,
                );
            });
        } else {
            break;
        }
    }
}
