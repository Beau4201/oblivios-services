use safelog::sensitive;
use smoltcp::time::Duration;
#[cfg(feature = "experimental-udp-tunnel")]
use smoltcp::wire::UdpPacket;
use smoltcp::wire::{
    IpAddress, IpEndpoint, Ipv6ExtHeader, Ipv6ExtHeaderRepr, Ipv6HopByHopHeader, Ipv6HopByHopRepr,
    Ipv6OptionFailureType, Ipv6OptionRepr, Ipv6Packet,
};
use smoltcp::{
    socket::tcp::Socket as TcpSocket,
    socket::tcp::SocketBuffer as TcpSocketBuffer,
    wire::{IpProtocol, Ipv4Packet, TcpPacket},
};
#[cfg(feature = "experimental-udp-tunnel")]
use std::net::SocketAddr;
use tracing::{debug, trace, warn};

use crate::device::Packet;

#[allow(clippy::large_enum_variant)]
pub enum ParseResult {
    Icmp,
    NewTcpSocket {
        source: IpEndpoint,
        dest: IpEndpoint,
        socket: TcpSocket<'static>,
    },
    #[cfg(feature = "experimental-udp-tunnel")]
    UdpData(crate::udp_tunnel::UdpData),
}

pub struct Parser {}

const SOCKET_TIMEOUT: Option<Duration> = Some(Duration::from_secs(120));

impl Parser {
    fn maybe_make_tcp_socket(packet: &TcpPacket<&[u8]>) -> Option<TcpSocket<'static>> {
        if packet.syn() && !packet.ack() {
            Some(TcpSocket::new(
                TcpSocketBuffer::new(vec![0; 4096]),
                TcpSocketBuffer::new(vec![0; 4096]),
            ))
        } else {
            None
        }
    }

    fn handle_tcp_packet(
        src_addr: IpAddress,
        dst_addr: IpAddress,
        payload: &[u8],
    ) -> Option<ParseResult> {
        match TcpPacket::new_checked(payload) {
            Ok(tcp_packet) => {
                #[cfg(feature = "very-verbose")]
                trace!(
                    "pkt tcp {}:{} -> {}:{}{}{}{}{}{}{}",
                    sensitive(src_addr),
                    sensitive(tcp_packet.src_port()),
                    sensitive(dst_addr),
                    sensitive(tcp_packet.dst_port()),
                    sensitive(if tcp_packet.syn() { " syn" } else { "" }),
                    sensitive(if tcp_packet.ack() { " ack" } else { "" }),
                    sensitive(if tcp_packet.fin() { " fin" } else { "" }),
                    sensitive(if tcp_packet.rst() { " rst" } else { "" }),
                    sensitive(if tcp_packet.psh() { " psh" } else { "" }),
                    sensitive(if tcp_packet.urg() { " urg" } else { "" }),
                );
                if let Some(mut socket) = Self::maybe_make_tcp_socket(&tcp_packet) {
                    let src_port = tcp_packet.src_port();
                    let dst_port = tcp_packet.dst_port();

                    trace!(
                        "New incoming TCP connection: {}:{} -> {}:{}",
                        sensitive(src_addr),
                        sensitive(src_port),
                        sensitive(dst_addr),
                        sensitive(dst_port)
                    );

                    assert!(!socket.is_open());
                    assert!(!socket.is_active());

                    socket.set_timeout(SOCKET_TIMEOUT);
                    trace!("Setted socket timeout to {SOCKET_TIMEOUT:?}");

                    if let Err(e) = socket.listen((dst_addr, dst_port)) {
                        // This is either because it's unaddressable or already open. Either way,
                        // it's not a serious problem.
                        warn!("Failed to create listening socket: {e:?}");
                    }
                    return Some(ParseResult::NewTcpSocket {
                        source: IpEndpoint {
                            addr: src_addr,
                            port: src_port,
                        },
                        dest: IpEndpoint {
                            addr: dst_addr,
                            port: dst_port,
                        },
                        socket,
                    });
                }
            }
            Err(err) => {
                warn!("Unable to parse TCP packet: {}", err);
            }
        }
        None
    }

    #[cfg(feature = "experimental-udp-tunnel")]
    fn handle_udp_packet(
        src_addr: IpAddress,
        dst_addr: IpAddress,
        payload: &[u8],
    ) -> Option<ParseResult> {
        match UdpPacket::new_checked(payload) {
            Ok(udp_packet) => {
                #[cfg(feature = "very-verbose")]
                trace!(
                    "pkt udp {}:{} -> {}:{}",
                    sensitive(src_addr),
                    sensitive(udp_packet.src_port()),
                    sensitive(dst_addr),
                    sensitive(udp_packet.dst_port()),
                );

                // This could use std::net::IpAddr::is_global once that
                // is stable. We mostly want to filter out link-local and
                // multicast, which will include our DNS server address.
                let dst_is_global = match &dst_addr {
                    IpAddress::Ipv4(v4) => {
                        !(v4.is_link_local()
                            || v4.is_broadcast()
                            || v4.is_multicast()
                            || v4.is_unspecified())
                    }
                    IpAddress::Ipv6(v6) => {
                        !(v6.is_unicast_link_local() || v6.is_multicast() || v6.is_unspecified())
                    }
                };

                if dst_is_global
                    && udp_packet.src_port() != 0
                    && udp_packet.dst_port() != 0
                    && udp_packet.verify_checksum(&src_addr, &dst_addr)
                {
                    // Convert smoltcp to std data types here
                    let source = SocketAddr::new(src_addr.into(), udp_packet.src_port());
                    let dest = SocketAddr::new(dst_addr.into(), udp_packet.dst_port());

                    // The UDP tunnel needs an owned Vec for the data,
                    // which it will internally queue for some time. Without
                    // a reference counted input buffer, this is the point
                    // where we get stuck making a copy of the datagram.
                    return Some(ParseResult::UdpData(crate::udp_tunnel::UdpData {
                        source,
                        dest,
                        data: udp_packet.payload().to_vec(),
                    }));
                }
            }
            Err(err) => {
                warn!("Unable to parse UDP packet: {}", err);
            }
        }
        None
    }

    pub fn parse(packet: &Packet) -> Option<ParseResult> {
        let vers = if let Some(ver_ihl) = packet.first() {
            ver_ihl >> 4
        } else {
            debug!("Failed to parse packet: empty packet");
            return None;
        };
        let (src, dst, protocol, payload) = match vers {
            4 => {
                let pkt = match Ipv4Packet::new_checked(packet.as_slice()) {
                    Ok(v) => v,
                    Err(e) => {
                        debug!("Failed to parse IPv4 packet: {}", e);
                        return None;
                    }
                };
                (
                    IpAddress::Ipv4(pkt.src_addr()),
                    IpAddress::Ipv4(pkt.dst_addr()),
                    pkt.next_header(),
                    pkt.payload(),
                )
            }
            6 => {
                let pkt = match Ipv6Packet::new_checked(packet.as_slice()) {
                    Ok(v) => v,
                    Err(e) => {
                        debug!("Failed to parse IPv6 packet: {}", e);
                        return None;
                    }
                };

                let mut payload = pkt.payload();
                let mut next_header = pkt.next_header();

                // Sigh. IPv6 has a 'nifty' feature that lets the 'next header' actually be this
                // "hop-by-hop protocol" extension that has a bunch of custom option fields.
                // We have to imitate what smoltcp does here and parse it out to see what the
                // *real* protocol and payload are, making sure to drop packets where the
                // options header says we should drop them if we don't understand them. (Otherwise,
                // we could be tricked into opening a socket for something smoltcp will drop!)
                //
                // (look at smoltcp src/iface/interface/ipv6.rs for what it does with these)
                if let IpProtocol::HopByHop = next_header {
                    let ext_header = match Ipv6ExtHeader::new_checked(payload) {
                        Ok(v) => v,
                        Err(e) => {
                            debug!("Failed to parse IPv6 extension header: {}", e);
                            return None;
                        }
                    };
                    let ext_repr = match Ipv6ExtHeaderRepr::parse(&ext_header) {
                        Ok(v) => v,
                        Err(e) => {
                            debug!("Failed to parse IPv6 extension header: {}", e);
                            return None;
                        }
                    };

                    let hbh_hdr = match Ipv6HopByHopHeader::new_checked(ext_repr.data) {
                        Ok(v) => v,
                        Err(e) => {
                            debug!("Failed to parse IPv6 hop-by-hop header: {}", e);
                            return None;
                        }
                    };

                    let hbh_repr = match Ipv6HopByHopRepr::parse(&hbh_hdr) {
                        Ok(v) => v,
                        Err(e) => {
                            debug!("Failed to parse IPv6 hop-by-hop header: {}", e);
                            return None;
                        }
                    };

                    for opt_repr in &hbh_repr.options {
                        match opt_repr {
                            Ipv6OptionRepr::Pad1 | Ipv6OptionRepr::PadN(_) => (),
                            Ipv6OptionRepr::Unknown { type_, .. } => {
                                match Ipv6OptionFailureType::from(*type_) {
                                    Ipv6OptionFailureType::Skip => (),
                                    _ => {
                                        warn!("Encountered unknown hop-by-hop option, dropping packet!");
                                        return None;
                                    }
                                }
                            }
                            _ => {
                                warn!("Encountered unknown hop-by-hop option (2nd case), dropping packet!");
                                return None;
                            }
                        }
                    }

                    next_header = ext_repr.next_header;
                    payload = &payload[(ext_repr.header_len() + ext_repr.data.len())..];
                }

                (
                    IpAddress::Ipv6(pkt.src_addr()),
                    IpAddress::Ipv6(pkt.dst_addr()),
                    next_header,
                    payload,
                )
            }
            x => {
                debug!("Unknown IP protocol version {}", x);
                return None;
            }
        };
        if protocol == IpProtocol::Icmp || protocol == IpProtocol::Icmpv6 {
            return Some(ParseResult::Icmp);
        }
        if let IpProtocol::Tcp = protocol {
            return Self::handle_tcp_packet(src, dst, payload);
        }
        #[cfg(feature = "experimental-udp-tunnel")]
        if let IpProtocol::Udp = protocol {
            return Self::handle_udp_packet(src, dst, payload);
        }
        None
    }
}

#[cfg(test)]
mod test {
    use smoltcp::wire::{Ipv4Address, Ipv6Address, TcpSeqNumber};

    use super::*;

    const IP4_HEADER_LEN: usize = 20;
    const IP6_HEADER_LEN: usize = 40;
    const TCP_HEADER_LEN: usize = 20;
    const UDP_HEADER_LEN: usize = 8;

    const TCP_SRC_PORT: u16 = 42;
    const TCP_DST_PORT: u16 = 4242;
    const UDP_SRC_PORT: u16 = TCP_SRC_PORT;
    const UDP_DST_PORT: u16 = TCP_DST_PORT;

    // Those values have no deeper meaning.
    const TCP_INITIAL_SEQ: i32 = 32434;
    const TCP_INITIAL_ACK: i32 = 64641;

    /* TODO MSRV 1.91: when ip_from is stable, switch to `from_octets` */
    const IP4_SRC_ADDR: Ipv4Address = Ipv4Address::new(192, 168, 0, 1);
    const IP4_DST_ADDR: Ipv4Address = Ipv4Address::new(192, 168, 0, 2);

    //const IP6_SRC_ADDR: Ipv6Address = Ipv6Address::LOOPBACK;
    //const IP6_DST_ADDR: Ipv6Address = Ipv6Address::LOOPBACK;
    const IP6_SRC_ADDR: Ipv6Address = Ipv6Address::new(
        0x2a02, 0x8108, 0x983f, 0xffec, 0x068, 0xb2f3, 0x2619, 0x8b5b,
    );
    const IP6_DST_ADDR: Ipv6Address = Ipv6Address::new(
        0x2a02, 0x8108, 0x983f, 0xffec, 0x068, 0xb2f3, 0x2619, 0x8b5b,
    );

    /// Creates a TCP packet without filling the checksums.
    fn tcp(data: &[u8], syn: bool, ack: bool) -> Vec<u8> {
        let mut tcp_raw = vec![0x0; TCP_HEADER_LEN + data.len()];
        let mut tcp = TcpPacket::new_unchecked(&mut tcp_raw);

        tcp.set_src_port(TCP_SRC_PORT);
        tcp.set_dst_port(TCP_DST_PORT);
        tcp.set_seq_number(TcpSeqNumber(TCP_INITIAL_SEQ));
        tcp.set_ack_number(TcpSeqNumber(TCP_INITIAL_ACK));
        tcp.set_header_len(TCP_HEADER_LEN as u8);
        tcp.clear_flags();
        tcp.set_syn(syn);
        tcp.set_ack(ack);
        tcp.set_window_len(128);
        tcp.set_urgent_at(0);
        tcp.payload_mut().copy_from_slice(data);

        tcp_raw
    }

    #[cfg(feature = "experimental-udp-tunnel")]
    /// Creates a UDP packet without filling the checksums.
    fn udp(data: &[u8]) -> Vec<u8> {
        let mut udp_raw = vec![0x0; UDP_HEADER_LEN + data.len()];
        let mut udp = UdpPacket::new_unchecked(&mut udp_raw);

        udp.set_src_port(UDP_SRC_PORT);
        udp.set_dst_port(UDP_DST_PORT);
        udp.set_len((UDP_HEADER_LEN + data.len()) as u16);
        udp.payload_mut().copy_from_slice(data);

        udp_raw
    }

    #[cfg(feature = "experimental-udp-tunnel")]
    /// Creates a UDP packet and encapsulates it into an IPv4 packet.
    fn ip4_udp(mut udp_raw: Vec<u8>) -> Vec<u8> {
        let mut ip4_raw = vec![0x0; IP4_HEADER_LEN + udp_raw.len()];
        let mut ip4 = Ipv4Packet::new_unchecked(&mut ip4_raw);

        ip4.set_version(4);
        ip4.set_header_len(IP4_HEADER_LEN as u8);
        ip4.set_dscp(0);
        ip4.set_ecn(0);
        ip4.set_total_len((IP4_HEADER_LEN + udp_raw.len()) as u16);
        ip4.set_ident(0);
        ip4.clear_flags();
        ip4.set_frag_offset(0);
        ip4.set_dont_frag(true);
        ip4.set_more_frags(true);
        ip4.set_hop_limit(0);
        ip4.set_next_header(IpProtocol::Udp);
        ip4.set_src_addr(IP4_SRC_ADDR);
        ip4.set_dst_addr(IP4_DST_ADDR);

        let mut udp = UdpPacket::new_unchecked(&mut udp_raw);

        // Fill the checksums here, because they need to be aware of src and dst.
        udp.fill_checksum(
            &IpAddress::Ipv4(ip4.src_addr()),
            &IpAddress::Ipv4(ip4.dst_addr()),
        );

        ip4.payload_mut().copy_from_slice(&udp_raw);
        ip4.fill_checksum();

        ip4_raw
    }

    // Creates a TCP packet and encapsulates it into an IPv4 packet.
    fn ip4_tcp(mut tcp_raw: Vec<u8>) -> Vec<u8> {
        let mut ip4_raw = vec![0x0; IP4_HEADER_LEN + tcp_raw.len()];
        let mut ip4 = Ipv4Packet::new_unchecked(&mut ip4_raw);

        ip4.set_version(4);
        ip4.set_header_len(IP4_HEADER_LEN as u8);
        ip4.set_dscp(0);
        ip4.set_ecn(0);
        ip4.set_total_len((IP4_HEADER_LEN + tcp_raw.len()) as u16);
        ip4.set_ident(0);
        ip4.clear_flags();
        ip4.set_frag_offset(0);
        ip4.set_dont_frag(true);
        ip4.set_more_frags(true);
        ip4.set_hop_limit(0);
        ip4.set_next_header(IpProtocol::Tcp);
        ip4.set_src_addr(IP4_SRC_ADDR);
        ip4.set_dst_addr(IP4_DST_ADDR);

        let mut tcp = TcpPacket::new_unchecked(&mut tcp_raw);

        // Fill the checksums here, because they need to be aware of src and dst.
        tcp.fill_checksum(
            &IpAddress::Ipv4(ip4.src_addr()),
            &IpAddress::Ipv4(ip4.dst_addr()),
        );

        ip4.payload_mut().copy_from_slice(&tcp_raw);
        ip4.fill_checksum();

        ip4_raw
    }

    /// Creates a TCP packet and encapsulates it into an IPv6 packet.
    fn ip6_tcp(mut tcp_raw: Vec<u8>) -> Vec<u8> {
        let mut ip6_raw = vec![0x0; IP6_HEADER_LEN + tcp_raw.len()];
        let mut ip6 = Ipv6Packet::new_unchecked(&mut ip6_raw);

        ip6.set_version(6);
        ip6.set_traffic_class(0);
        ip6.set_flow_label(0);
        ip6.set_payload_len(tcp_raw.len() as u16);
        ip6.set_next_header(IpProtocol::Tcp);
        ip6.set_hop_limit(0);
        ip6.set_src_addr(IP6_SRC_ADDR);
        ip6.set_dst_addr(IP6_DST_ADDR);

        let mut tcp = TcpPacket::new_unchecked(&mut tcp_raw);

        // Fill the checksums here, because they need to be aware of src and dst.
        tcp.fill_checksum(
            &IpAddress::Ipv6(ip6.src_addr()),
            &IpAddress::Ipv6(ip6.dst_addr()),
        );
        ip6.payload_mut().copy_from_slice(&tcp_raw);

        ip6_raw
    }

    #[cfg(feature = "experimental-udp-tunnel")]
    /// Creates a UDP packet and encapsulates it into an IPv6 packet.
    fn ip6_udp(mut udp_raw: Vec<u8>) -> Vec<u8> {
        let mut ip6_raw = vec![0x0; IP6_HEADER_LEN + udp_raw.len()];
        let mut ip6 = Ipv6Packet::new_unchecked(&mut ip6_raw);

        ip6.set_version(6);
        ip6.set_traffic_class(0);
        ip6.set_flow_label(0);
        ip6.set_payload_len(udp_raw.len() as u16);
        ip6.set_next_header(IpProtocol::Udp);
        ip6.set_hop_limit(0);
        ip6.set_src_addr(IP6_SRC_ADDR);
        ip6.set_dst_addr(IP6_DST_ADDR);

        let mut udp = UdpPacket::new_unchecked(&mut udp_raw);

        // Fill the checksums here, because they need to be aware of src and dst.
        udp.fill_checksum(
            &IpAddress::Ipv6(ip6.src_addr()),
            &IpAddress::Ipv6(ip6.dst_addr()),
        );
        ip6.payload_mut().copy_from_slice(&udp_raw);

        ip6_raw
    }

    /// Generates an IPv6 packet with a hop-by-header followed by a TCP segment.
    fn ip6_hop_by_hop(mut hop_by_hop: Vec<u8>) -> Vec<u8> {
        // Unlike in other code snippets, we concat later (i.e. no big raw array)
        let mut ip6_raw = vec![0x0; IP6_HEADER_LEN];
        let mut ip6 = Ipv6Packet::new_unchecked(&mut ip6_raw);

        // Begin by creating an ordinary IPv6 packet header.
        ip6.set_version(6);
        ip6.set_traffic_class(0);
        ip6.set_flow_label(0);
        ip6.set_payload_len((hop_by_hop.len() + TCP_HEADER_LEN + 3) as u16);
        ip6.set_next_header(IpProtocol::HopByHop);
        ip6.set_hop_limit(0);
        ip6.set_src_addr(IP6_SRC_ADDR);
        ip6.set_dst_addr(IP6_DST_ADDR);

        // Now create the TCP packet.
        let mut tcp_raw = tcp("foo".as_bytes(), true, false);
        let mut tcp = TcpPacket::new_unchecked(&mut tcp_raw);
        tcp.fill_checksum(
            &IpAddress::Ipv6(ip6.src_addr()),
            &IpAddress::Ipv6(ip6.dst_addr()),
        );

        // Now concat everything together.
        let mut raw = Vec::new();
        raw.append(&mut ip6_raw);
        raw.append(&mut hop_by_hop);
        raw.append(&mut tcp_raw);

        raw
    }

    /// Tests the creation of a TCP/IPv4 socket.
    #[test]
    fn test_tcp_socket_create_ip4() {
        // Ordinary IPv4 TCP handshake creation if and only if (SYN true, ACK false).
        let packet = ip4_tcp(tcp("foo".as_bytes(), true, false));
        match Parser::parse(&packet).unwrap() {
            ParseResult::NewTcpSocket {
                source,
                dest,
                socket: _,
            } => {
                assert_eq!(
                    source,
                    IpEndpoint::new(IpAddress::Ipv4(IP4_SRC_ADDR), TCP_SRC_PORT)
                );
                assert_eq!(
                    dest,
                    IpEndpoint::new(IpAddress::Ipv4(IP4_DST_ADDR), TCP_DST_PORT)
                );
                // We do not validate `socket`, we are not testing smoltcp.
            }
            _ => panic!("received no NewTcpSocket"),
        }
    }

    /// Tests the creation of a TCP/IPv6 socket.
    #[test]
    fn test_tcp_socket_create_ip6() {
        // Ordinary IPv6 TCP handshake creation if and only if (SYN true, ACK false).
        let packet = ip6_tcp(tcp("foo".as_bytes(), true, false));
        match Parser::parse(&packet).unwrap() {
            ParseResult::NewTcpSocket {
                source,
                dest,
                socket: _,
            } => {
                assert_eq!(
                    source,
                    IpEndpoint::new(IpAddress::Ipv6(IP6_SRC_ADDR), TCP_SRC_PORT)
                );
                assert_eq!(
                    dest,
                    IpEndpoint::new(IpAddress::Ipv6(IP6_DST_ADDR), TCP_DST_PORT)
                );
                // We do not validate `socket`, we are not testing smoltcp.
            }
            _ => panic!("received no NewTcpSocket"),
        }

        // Some illegal handshake creations if and only if (SYN true, ACK false).
        assert!(Parser::parse(&ip6_tcp(tcp("foo".as_bytes(), true, true))).is_none());
        assert!(Parser::parse(&ip6_tcp(tcp("foo".as_bytes(), false, true))).is_none());
        assert!(Parser::parse(&ip6_tcp(tcp("foo".as_bytes(), false, false))).is_none());
    }

    /// Tests the handling of an illegal IPv4 packet.
    #[test]
    fn test_ip4_illegal() {
        let mut raw = vec![0x0; 1];
        let mut ip4 = Ipv4Packet::new_unchecked(&mut raw);
        ip4.set_version(4);
        assert!(Parser::parse(&raw).is_none());
    }

    /// Tests the handling of an illegal IPv6 packet.
    #[test]
    fn test_ip6_illegal() {
        let mut raw = vec![0x0; 1];
        let mut ip6 = Ipv6Packet::new_unchecked(&mut raw);
        ip6.set_version(6);
        assert!(Parser::parse(&raw).is_none());
    }

    /// Tests an illegal TCP packet.
    #[test]
    fn test_tcp_illegal() {
        // First create a legal TCP packet.
        // We use IPv6 because then its just one checksum.
        let mut packet = ip6_tcp(tcp(&[], true, false));

        // Now screw up the checksum.
        for i in IP6_HEADER_LEN..packet.len() {
            packet[i] = 0;
        }

        assert!(Parser::parse(&packet).is_none());
    }

    /// Tests the handling of an unknown IP version protocol packet.
    #[test]
    fn test_ip_unknown() {
        // Send a complete garbage package.
        let garbage = vec![0x0_u8; 1024];
        assert!(Parser::parse(&garbage).is_none());
    }

    /// Tests the handling of an IPv6 packet that contains the hop-by-hop extension.
    #[test]
    fn test_ip6_hop_by_hop() {
        assert!(Parser::parse(&ip6_hop_by_hop(vec![6, 0, 0, 0, 0, 0, 0, 0])).is_some());
    }

    /// Test an IPv6 extension packet that is too small in its size.
    #[test]
    fn test_ip6_ext_too_small() {
        assert!(Parser::parse(&ip6_hop_by_hop(vec![6, 0, 0, 0, 0, 0, 0])).is_none());
        assert!(Parser::parse(&ip6_hop_by_hop(vec![6])).is_none());
        assert!(Parser::parse(&ip6_hop_by_hop(vec![])).is_none());
    }

    /// Test an IPv6 hop-by-hop packet that is invalid.
    #[test]
    fn test_ip6_hop_by_hop_invalid() {
        // Totally empty.
        assert!(Parser::parse(&ip6_hop_by_hop(vec![0, 0, 0, 0, 0, 0, 0, 0])).is_none());

        // Invalid option.
        assert!(Parser::parse(&ip6_hop_by_hop(vec![6, 0, 0xFF, 0, 0, 0, 0, 0])).is_none());
    }

    /// Tests an ordinary UDP packet over IPv4.
    #[cfg(feature = "experimental-udp-tunnel")]
    #[test]
    fn test_ip4_udp() {
        use std::net::SocketAddrV4;

        use crate::udp_tunnel::UdpData;

        let ip4 = ip4_udp(udp("foo".as_bytes()));
        match Parser::parse(&ip4).unwrap() {
            ParseResult::UdpData(udp) => {
                assert_eq!(
                    udp,
                    UdpData {
                        source: SocketAddr::V4(SocketAddrV4::new(
                            IP4_SRC_ADDR.into(),
                            UDP_SRC_PORT
                        )),
                        dest: SocketAddr::V4(SocketAddrV4::new(IP4_DST_ADDR.into(), UDP_DST_PORT)),
                        data: "foo".as_bytes().to_vec()
                    }
                )
            }
            _ => panic!("missing ParseResult::UdpData"),
        }
    }

    /// Tests an ordinary UDP packet over IPv6.
    #[cfg(feature = "experimental-udp-tunnel")]
    #[test]
    fn test_ip6_udp() {
        use std::net::SocketAddrV6;

        use crate::udp_tunnel::UdpData;

        let ip6 = ip6_udp(udp("foo".as_bytes()));
        match Parser::parse(&ip6).unwrap() {
            ParseResult::UdpData(udp) => {
                assert_eq!(
                    udp,
                    UdpData {
                        source: SocketAddr::V6(SocketAddrV6::new(
                            IP6_SRC_ADDR.into(),
                            UDP_SRC_PORT,
                            0,
                            0
                        )),
                        dest: SocketAddr::V6(SocketAddrV6::new(
                            IP6_DST_ADDR.into(),
                            UDP_DST_PORT,
                            0,
                            0
                        )),
                        data: "foo".as_bytes().to_vec()
                    }
                )
            }
            _ => panic!("missing ParseResult::UdpData"),
        }
    }

    #[cfg(feature = "experimental-udp-tunnel")]
    #[test]
    fn test_ip6_udp_illegal() {
        // Create a UDP IPv4 packet and modify the last byte to invalidate the checksum.
        let mut ip4 = ip4_udp(udp("foo".as_bytes()));
        let len = ip4.len();
        ip4[len - 1] = 0;

        assert!(Parser::parse(&ip4).is_none());
    }

    // FreeholdOS patch 0005: the parser reads packets that an app controls; no packet may make it panic. Seeds are valid
    // packets (IPv4 and IPv6, with and without a hop-by-hop header), mutated at a few bytes, cut, or extended; the rest are
    // random bytes with the version nibble of IPv4 or IPv6.
    struct Rng(u64);

    impl Rng {
        fn next(&mut self) -> u64 {
            self.0 ^= self.0 << 13;
            self.0 ^= self.0 >> 7;
            self.0 ^= self.0 << 17;
            self.0
        }
        fn below(&mut self, n: u64) -> u64 {
            self.next() % n
        }
    }

    #[test]
    fn no_packet_makes_the_parser_panic() {
        let seeds: Vec<Vec<u8>> = vec![
            ip4_tcp(tcp(b"", true, false)),
            ip4_tcp(tcp(b"payload", false, true)),
            ip6_tcp(tcp(b"", true, false)),
            ip6_tcp(tcp(b"hello", true, true)),
            ip6_hop_by_hop(ip6_tcp(tcp(b"", true, false))),
            ip6_hop_by_hop(vec![6, 0, 1, 4, 0, 0, 0, 0]),
            ip6_hop_by_hop(vec![17, 0, 0xc2, 4, 0, 0, 0, 0]),
        ];
        for seed in [1u64, 3, 0x9e37_79b9_7f4a_7c15, 0xcafe_f00d] {
            let mut g = Rng(seed);
            for _ in 0..30_000 {
                let pkt: Vec<u8> = if g.below(5) == 0 {
                    let n = g.below(160) as usize;
                    let mut v: Vec<u8> = (0..n).map(|_| g.below(256) as u8).collect();
                    if let Some(first) = v.first_mut() {
                        *first = if g.below(2) == 0 { 0x45 } else { 0x60 };
                    }
                    v
                } else {
                    let mut v = seeds[g.below(seeds.len() as u64) as usize].clone();
                    for _ in 0..g.below(5) {
                        if !v.is_empty() {
                            let at = g.below(v.len() as u64) as usize;
                            v[at] = g.below(256) as u8;
                        }
                    }
                    match g.below(4) {
                        0 => v.truncate(g.below(v.len() as u64 + 1) as usize),
                        1 => v.extend((0..g.below(40)).map(|_| g.below(256) as u8)),
                        _ => {}
                    }
                    v
                };
                let _ = Parser::parse(&pkt);
            }
        }
    }
}
