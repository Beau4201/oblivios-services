//! An exceedingly simple crate to read `/proc/net/tcp(6)` and `/proc/net/udp(6)`.

use std::fs::File;
use std::io::{self, BufRead, BufReader, Lines};
use std::iter::Skip;
use std::net::{Ipv4Addr, Ipv6Addr, SocketAddr};
use std::path::Path;
use std::str::FromStr;

/// A `/proc/net` entry.
///
/// This type is sufficiently dumb that it covers all of /proc/net/{tcp, udp, tcp6, udp6}.
/// It doesn't have any more fields because we don't need any more fields.
#[derive(Clone, PartialEq, Debug)]
#[non_exhaustive]
pub struct ProcNetEntry {
    /// Local address.
    pub local_addr: SocketAddr,
    /// Remote address.
    pub remote_addr: SocketAddr,
    /// TCP or UDP state (see associated constants).
    ///
    /// Can be zero if this information is unavailable for some reason.
    pub state: u16,
    /// User ID associated with the connection.
    pub uid: u32,
}

impl ProcNetEntry {
    /// Get a iterator of `ProcNetEntry` lines from a `BufRead`.
    pub fn from_reader<R: BufRead>(reader: R) -> ProcNetIterator<R> {
        ProcNetIterator {
            inner: reader.lines().skip(1),
        }
    }

    /// Open the `path` and call `from_reader` on the returned file.
    pub fn from_path<P: AsRef<Path>>(path: P) -> io::Result<ProcNetIterator<BufReader<File>>> {
        let file = File::open(path.as_ref())?;
        let br = BufReader::new(file);
        Ok(Self::from_reader(br))
    }

    /// A shortcut for `from_path("/proc/net/tcp")`.
    pub fn tcp4() -> io::Result<ProcNetIterator<BufReader<File>>> {
        Self::from_path("/proc/net/tcp")
    }

    /// A shortcut for `from_path("/proc/net/tcp6")`.
    pub fn tcp6() -> io::Result<ProcNetIterator<BufReader<File>>> {
        Self::from_path("/proc/net/tcp6")
    }

    /// A shortcut for `from_path("/proc/net/udp")`.
    pub fn udp4() -> io::Result<ProcNetIterator<BufReader<File>>> {
        Self::from_path("/proc/net/udp")
    }

    /// A shortcut for `from_path("/proc/net/udp6")`.
    pub fn udp6() -> io::Result<ProcNetIterator<BufReader<File>>> {
        Self::from_path("/proc/net/udp6")
    }
}

/// An interator over `ProcNetEntry` items, obtained by wrapping a `BufRead`.
pub struct ProcNetIterator<R> {
    inner: Skip<Lines<R>>,
}

pub type ProcNetIteratorItem = Result<Result<ProcNetEntry, FailedParse>, io::Error>;

/// A report of a failed parse.
#[derive(Clone, PartialEq, Debug)]
#[non_exhaustive]
pub struct FailedParse {
    /// The line that failed to parse.
    pub offending_line: String,
    /// The error encountered when parsing..
    pub error: String,
}

impl<R: BufRead> Iterator for ProcNetIterator<R> {
    type Item = ProcNetIteratorItem;

    fn next(&mut self) -> Option<Self::Item> {
        match self.inner.next() {
            Some(Ok(line)) => Some(Ok(line.parse().map_err(|e| FailedParse {
                offending_line: line,
                error: e,
            }))),
            Some(Err(e)) => Some(Err(e)),
            None => None,
        }
    }
}

/// This `impl` block contains the constants for the `state` field on `ProcNetEntry`.
impl ProcNetEntry {
    pub const TCP_ESTABLISHED: u16 = 0x01;
    pub const TCP_SYN_SENT: u16 = 0x02;
    pub const TCP_SYN_RECV: u16 = 0x03;
    pub const TCP_FIN_WAIT1: u16 = 0x04;
    pub const TCP_FIN_WAIT2: u16 = 0x05;
    pub const TCP_TIME_WAIT: u16 = 0x06;
    pub const TCP_CLOSE: u16 = 0x07;
    pub const TCP_CLOSE_WAIT: u16 = 0x08;
    pub const TCP_LAST_ACK: u16 = 0x09;
    pub const TCP_LISTEN: u16 = 0x0A;
    pub const TCP_CLOSING: u16 = 0x0B;
    pub const TCP_NEW_SYN_RECV: u16 = 0x0C;

    pub const UDP_ESTABLISHED: u16 = 0x01;
    pub const UDP_CLOSE: u16 = 0x07;
}

impl FromStr for ProcNetEntry {
    type Err = String;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        //   sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
        //    0: 3500007F:0035 00000000:0000 0A 00000000:00000000 00:00000000 00000000   102        0 20636 1 0000000000000000 100 0 0 10 5
        //   [0]      [1]           [2]     [3]        [4]            [5]        [6]     [7]    [8]   [9]
        let line = s.split_whitespace().collect::<Vec<_>>();
        if line.len() < 9 {
            return Err("tcp4 line doesn't have enough parts".into());
        }
        let local_addr =
            parse_address(line[1]).map_err(|_| "while parsing local_addr".to_string())?;
        let remote_addr =
            parse_address(line[2]).map_err(|_| "while parsing remote_addr".to_string())?;
        let state = u16::from_str_radix(line[3], 16)
            .map_err(|x| format!("while parsing state ({}): {}", line[3], x))?;
        let uid: u32 = line[7]
            .parse()
            .map_err(|x| format!("while parsing uid ({}): {}", line[7], x))?;

        Ok(Self {
            local_addr,
            remote_addr,
            state,
            uid,
        })
    }
}

fn hex_to_vec(s: &str) -> Result<Vec<u8>, &'static str> {
    if !s.len().is_multiple_of(2) {
        Err("hex string odd number of characters")
    } else {
        (0..s.len())
            .step_by(2)
            .map(|i| {
                u8::from_str_radix(&s[i..i + 2], 16).map_err(|_| "invalid characters in hex string")
            })
            .collect()
    }
}

/// Parse a hex address, like '3500007F:0035' or '401A0D2AFECA5375FF77F3C0992BB8FE:E078',
/// into a `SocketAddr`.
fn parse_address(addr: &str) -> Result<SocketAddr, &'static str> {
    let mut iter = addr.split(':');

    let addr_part = iter.next().ok_or("no addr part")?;
    let addr_bytes = hex_to_vec(addr_part)?;

    let port_part = iter.next().ok_or("no port part")?;
    let port_bytes: [u8; 2] = hex_to_vec(port_part)?
        .try_into()
        .map_err(|_| "insufficient port bytes")?;
    let port = u16::from_be_bytes(port_bytes);

    match addr_bytes.len() {
        4 => {
            // IPv4 case: one native-order u32
            let octets: [u8; 4] = addr_bytes
                .try_into()
                .map_err(|_| "insufficient v4 address bytes")?;
            let addr: u32 = u32::from_ne_bytes(octets);

            Ok(SocketAddr::new(Ipv4Addr::from(addr).into(), port))
        }
        16 => {
            // IPv6 case: 4 native-order u32s (in a big-endian order)
            //
            // (example on a little endian system)
            // 2a0d:1a40:7553:cafe:c0f3:77ff:feb8:2b99 <- actual
            // 401A 0D2A|FECA 5375|FF77 F3C0|992B B8FE <- procfs

            // Output array in network byte order
            let mut octets: [u8; 16] = [0; 16];

            #[allow(clippy::identity_op)]
            for i in 0..4 {
                // Get the u32 chunk (as native byte order)
                let chunk_bytes_ne: [u8; 4] = addr_bytes[(i * 4)..(i + 1) * 4].try_into().unwrap();
                // Convert it into a u32
                let chunk = u32::from_ne_bytes(chunk_bytes_ne);
                // Convert the u32 into network byte order
                let chunk_bytes_be = chunk.to_be_bytes();

                // Copy that into the result array
                octets[(i * 4) + 0] = chunk_bytes_be[0];
                octets[(i * 4) + 1] = chunk_bytes_be[1];
                octets[(i * 4) + 2] = chunk_bytes_be[2];
                octets[(i * 4) + 3] = chunk_bytes_be[3];
            }

            Ok(SocketAddr::new(Ipv6Addr::from(octets).into(), port))
        }
        _ => Err("invalid addr:port length"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{BufReader, Cursor};

    #[test]
    #[cfg(target_endian = "little")] // TODO(eta): find some strings from a big-endian system, too
    fn it_parses_addresses() {
        assert_eq!(
            parse_address("55BD1F2C:E2DE").unwrap(),
            "44.31.189.85:58078".parse().unwrap()
        );
        assert_eq!(
            parse_address("401A0D2AFECA5375FF77F3C0992BB8FE:E078").unwrap(),
            "[2a0d:1a40:7553:cafe:c0f3:77ff:feb8:2b99]:57464"
                .parse()
                .unwrap()
        );
    }

    #[test]
    #[cfg(target_endian = "little")]
    fn proc_examples() {
        let tcp = r#"  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
   0: 3500007F:0035 00000000:0000 0A 00000000:00000000 00:00000000 00000000   102        0 20636 1 0000000000000000 100 0 0 10 5
   1: 00000000:0016 00000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 22805 1 0000000000000000 100 0 0 10 0
   2: 55BD1F2C:E2DE B9F61C95:1A0B 01 00000000:00000000 00:00000000 00000000  1000        0 153267 1 0000000000000000 34 4 28 10 -1"#;
        let br = BufReader::new(Cursor::new(tcp.as_bytes()));

        assert_eq!(
            ProcNetEntry::from_reader(br)
                .collect::<Result<Vec<_>, _>>()
                .unwrap(),
            vec![
                Ok(ProcNetEntry {
                    local_addr: "127.0.0.53:53".parse().unwrap(),
                    remote_addr: "0.0.0.0:0".parse().unwrap(),
                    state: ProcNetEntry::TCP_LISTEN,
                    uid: 102
                }),
                Ok(ProcNetEntry {
                    local_addr: "0.0.0.0:22".parse().unwrap(),
                    remote_addr: "0.0.0.0:0".parse().unwrap(),
                    state: ProcNetEntry::TCP_LISTEN,
                    uid: 0
                }),
                Ok(ProcNetEntry {
                    local_addr: "44.31.189.85:58078".parse().unwrap(),
                    remote_addr: "149.28.246.185:6667".parse().unwrap(),
                    state: ProcNetEntry::TCP_ESTABLISHED,
                    uid: 1000
                })
            ],
        );

        let udp = r#"   sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode ref pointer drops
  651: 3500007F:0035 00000000:0000 07 00000000:00000000 00:00000000 00000000   102        0 20635 2 0000000000000000 0
  666: 55BD1F2C:0044 00000000:0000 07 00000000:00000000 00:00000000 00000000   101        0 153934 2 0000000000000000 0"#;
        let br = BufReader::new(Cursor::new(udp.as_bytes()));

        assert_eq!(
            ProcNetEntry::from_reader(br)
                .collect::<Result<Vec<_>, _>>()
                .unwrap(),
            vec![
                Ok(ProcNetEntry {
                    local_addr: "127.0.0.53:53".parse().unwrap(),
                    remote_addr: "0.0.0.0:0".parse().unwrap(),
                    state: ProcNetEntry::UDP_CLOSE,
                    uid: 102
                }),
                Ok(ProcNetEntry {
                    local_addr: "44.31.189.85:68".parse().unwrap(),
                    remote_addr: "0.0.0.0:0".parse().unwrap(),
                    state: ProcNetEntry::UDP_CLOSE,
                    uid: 101
                })
            ],
        );

        let tcp6 = r#"  sl  local_address                         remote_address                        st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
   0: 00000000000000000000000000000000:0016 00000000000000000000000000000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 22816 1 0000000000000000 100 0 0 10 0
   1: 401A0D2AFECA5375FF77F3C0992BB8FE:E078 003C002600000000FF933CF00D99A1FE:1A0B 01 00000000:00000000 00:00000000 00000000  1000        0 153258 1 0000000000000000 35 4 30 10 -1
   2: 401A0D2AFECA5375FF77F3C0992BB8FE:0016 401A0D2AFECA5375EFECD29D6F9E9F3F:CA5C 01 00000000:00000000 02:0004DBCE 00000000     0        0 22983 4 0000000000000000 20 4 5 1 0 -1"#;
        let br = BufReader::new(Cursor::new(tcp6.as_bytes()));

        assert_eq!(
            ProcNetEntry::from_reader(br)
                .collect::<Result<Vec<_>, _>>()
                .unwrap(),
            vec![
                Ok(ProcNetEntry {
                    local_addr: "[::]:22".parse().unwrap(),
                    remote_addr: "[::]:0".parse().unwrap(),
                    state: ProcNetEntry::TCP_LISTEN,
                    uid: 0
                }),
                Ok(ProcNetEntry {
                    local_addr: "[2a0d:1a40:7553:cafe:c0f3:77ff:feb8:2b99]:57464"
                        .parse()
                        .unwrap(),
                    remote_addr: "[2600:3c00::f03c:93ff:fea1:990d]:6667".parse().unwrap(),
                    state: ProcNetEntry::TCP_ESTABLISHED,
                    uid: 1000
                }),
                Ok(ProcNetEntry {
                    local_addr: "[2a0d:1a40:7553:cafe:c0f3:77ff:feb8:2b99]:22"
                        .parse()
                        .unwrap(),
                    remote_addr: "[2a0d:1a40:7553:cafe:9dd2:ecef:3f9f:9e6f]:51804"
                        .parse()
                        .unwrap(),
                    state: ProcNetEntry::TCP_ESTABLISHED,
                    uid: 0
                })
            ]
        );
    }
}
