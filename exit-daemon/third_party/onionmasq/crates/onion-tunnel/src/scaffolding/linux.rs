//! [`TunnelScaffolding`] implemented for Linux.
//!
//! Usually, this trait is implemented in a downstream trait.  Originally, it
//! was also implemented in the `onionmasq` crate rather than `onion-tunnel`.
//! However, because oniux makes use of it too, we decided to move it here
//! eventually.  Maybe it is better to create an external crate named
//! `onionmasq-linux` that is a library only but I feel that would not fit into
//! the current schema either.

#[cfg(feature = "experimental-udp-tunnel")]
use std::collections::HashMap;
use std::os::fd::AsRawFd;
use std::{ffi::c_void, io, net::SocketAddr, os::fd::RawFd};

#[cfg(feature = "experimental-udp-tunnel")]
use crate::scaffolding::{UdpTunnelChoice, UdpTunnelContext};
use crate::TunnelScaffolding;

use simple_proc_net::ProcNetEntry;
use smoltcp::wire::IpEndpoint;
use tokio::net::TcpSocket;
use tor_geoip::CountryCode;

use tracing::{info, trace, warn};

pub struct LinuxScaffolding {
    pub cc: Option<CountryCode>,
    pub can_mark: bool,
    #[cfg(feature = "experimental-udp-tunnel")]
    pub udp_config: UdpTunnelChoice,
}

impl LinuxScaffolding {
    /// Check whether we can call setsockopt().
    pub fn can_mark() -> bool {
        // TODO: Somehow propegate the errors instead of ignoring them
        let dummy_socket = match TcpSocket::new_v4() {
            Ok(s) => s,
            Err(_) => return false,
        };
        Self::mark_fd(dummy_socket.as_raw_fd()).is_ok()
    }

    /// The fwmark to set on arti connections (so policy routing can avoid them being routed
    /// recursively back into the tunnel).
    ///
    /// This is just a randomly generated set of 2 bytes that hopefully won't conflict with
    /// anything else.
    pub const FWMARK: libc::c_int = 0xc185;

    /// Mark the provided socket file descriptor with the `FWMARK`.
    fn mark_fd(fd: RawFd) -> io::Result<()> {
        let ret = unsafe {
            libc::setsockopt(
                fd,
                libc::SOL_SOCKET,
                libc::SO_MARK,
                &Self::FWMARK as *const libc::c_int as *const c_void,
                size_of::<libc::c_int>() as _,
            )
        };
        if ret != 0 {
            Err(io::Error::last_os_error())
        } else {
            Ok(())
        }
    }
}

impl TunnelScaffolding for LinuxScaffolding {
    fn protect(&self, fd: RawFd, _: &SocketAddr) -> io::Result<()> {
        if self.can_mark {
            Self::mark_fd(fd)?;
        }
        Ok(())
    }

    fn should_protect(&self, _: u64) -> bool {
        // On normal Linux we will never leak.
        false
    }

    fn locate(&self, _: IpEndpoint, _: IpEndpoint, _: u64) -> Option<CountryCode> {
        self.cc
    }

    fn on_bootstrapped(&self) {
        info!("Connection to Tor complete!");
    }

    fn isolate(&self, src: IpEndpoint, dst: IpEndpoint, ip_proto: u8) -> io::Result<u64> {
        use safelog::sensitive;

        let iter = match ip_proto {
            6 => ProcNetEntry::tcp4()?.chain(ProcNetEntry::tcp6()?),
            17 => ProcNetEntry::udp4()?.chain(ProcNetEntry::udp6()?),
            x => panic!("unknown ip_proto {x}"),
        };
        for entry in iter {
            match entry? {
                Ok(v) => {
                    if IpEndpoint::from(v.local_addr) == src
                        && IpEndpoint::from(v.remote_addr) == dst
                    {
                        use safelog::sensitive;

                        trace!(
                            "isolated {} -> {} proto {ip_proto} as {}",
                            sensitive(src),
                            sensitive(dst),
                            sensitive(v.uid)
                        );
                        return Ok(v.uid as u64);
                    }
                }
                Err(e) => {
                    warn!("Failed to parse /proc line: {}", e.offending_line);
                    warn!("Error encountered: {}", e.error);
                }
            }
        }
        warn!(
            "no isolation found for {} -> {} proto {ip_proto}",
            sensitive(src),
            sensitive(dst)
        );
        Ok(0)
    }

    #[cfg(feature = "experimental-udp-tunnel")]
    fn udp_tunnel(&self, context: &UdpTunnelContext) -> UdpTunnelChoice {
        use safelog::sensitive;

        info!("requesting new UDP tunnel for {:?}", sensitive(context));
        self.udp_config.clone()
    }

    #[cfg(feature = "experimental-udp-tunnel")]
    fn udp_prebuild(&self, isolation_key: u64) -> HashMap<UdpTunnelChoice, usize> {
        use safelog::sensitive;

        const NUM_EXTRA_TUNNELS: usize = 2;
        trace!(
            "maintaining pre-built UDP tunnels, request {:?} for isolation {:?}",
            NUM_EXTRA_TUNNELS,
            sensitive(isolation_key)
        );
        let choice = self.udp_config.clone();
        let mut result: HashMap<UdpTunnelChoice, usize> = Default::default();
        result.insert(choice, NUM_EXTRA_TUNNELS);
        result
    }
}
