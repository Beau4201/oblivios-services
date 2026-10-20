// SPDX-License-Identifier: Apache-2.0
//! The typed control protocol between the exit app and the daemon (ADR-184). Safe Rust only. A frame is a `u32`
//! big-endian length of tag plus payload, the tag byte, then the payload. A decoder checks the claimed length before it
//! looks at anything else and never allocates from it: the only allocations are the bounded interface name and the
//! output of the encoders.

/// The largest tag-plus-payload a peer may claim.
pub const MAX_FRAME: usize = 1024;
/// The longest interface name the kernel accepts (IFNAMSIZ - 1).
pub const IFNAME_MAX: usize = 15;

/// Lower and upper bound of the Arti memory quota a peer may ask for. `[assumption]` until decision D-K.
const MEMORY_MIN: u64 = 32 * 1024 * 1024;
const MEMORY_MAX: u64 = 1024 * 1024 * 1024;

const TAG_HELLO: u8 = 0x01;
const TAG_SET_MEMORY_MAX: u8 = 0x02;
const TAG_REFRESH_APP: u8 = 0x03;
const TAG_REFRESH_ALL: u8 = 0x04;
const TAG_SHUTDOWN: u8 = 0x05;
const TAG_READY: u8 = 0x81;
const TAG_STATE: u8 = 0x82;
const TAG_FAILURES: u8 = 0x83;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ToDaemon {
    Hello { version: u8 },
    SetMemoryMax { bytes: u64 },
    RefreshApp { uid: u32 },
    RefreshAll,
    Shutdown,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum DaemonState {
    Bootstrapping = 0,
    Up = 1,
    Failed = 2,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum FromDaemon {
    Ready { iface: String },
    State { state: DaemonState },
    Failures { uid: u32, count: u32 },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DecodeError {
    /// More bytes are needed; nothing is wrong yet.
    Incomplete,
    /// The claimed length is above [`MAX_FRAME`]; the stream must be closed.
    TooLong(usize),
    UnknownTag(u8),
    /// The frame is empty or its payload is shorter than the message needs.
    BadLength,
    BadIface,
    BadValue,
    /// The payload is longer than the message needs.
    Trailing,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PolicyError {
    TooSmall,
    TooLarge,
}

fn frame(tag: u8, payload: &[u8]) -> Vec<u8> {
    // Every payload built here is far below MAX_FRAME; the cast cannot truncate.
    let len = (payload.len() + 1) as u32;
    let mut v = Vec::with_capacity(4 + 1 + payload.len());
    v.extend_from_slice(&len.to_be_bytes());
    v.push(tag);
    v.extend_from_slice(payload);
    v
}

pub fn encode_to_daemon(m: &ToDaemon) -> Vec<u8> {
    match m {
        ToDaemon::Hello { version } => frame(TAG_HELLO, &[*version]),
        ToDaemon::SetMemoryMax { bytes } => frame(TAG_SET_MEMORY_MAX, &bytes.to_be_bytes()),
        ToDaemon::RefreshApp { uid } => frame(TAG_REFRESH_APP, &uid.to_be_bytes()),
        ToDaemon::RefreshAll => frame(TAG_REFRESH_ALL, &[]),
        ToDaemon::Shutdown => frame(TAG_SHUTDOWN, &[]),
    }
}

pub fn encode_from_daemon(m: &FromDaemon) -> Vec<u8> {
    match m {
        FromDaemon::Ready { iface } => {
            debug_assert!(valid_iface(iface.as_bytes()), "the caller validates the interface name");
            frame(TAG_READY, iface.as_bytes())
        }
        FromDaemon::State { state } => frame(TAG_STATE, &[*state as u8]),
        FromDaemon::Failures { uid, count } => {
            let mut p = [0u8; 8];
            p[..4].copy_from_slice(&uid.to_be_bytes());
            p[4..].copy_from_slice(&count.to_be_bytes());
            frame(TAG_FAILURES, &p)
        }
    }
}

/// Splits one frame off the front of `buf`: the tag, the payload and the number of bytes the frame uses.
fn split(buf: &[u8]) -> Result<(u8, &[u8], usize), DecodeError> {
    let head: [u8; 4] = buf
        .get(..4)
        .and_then(|h| <[u8; 4]>::try_from(h).ok())
        .ok_or(DecodeError::Incomplete)?;
    let len = u32::from_be_bytes(head) as usize;
    // The claimed length decides before any body byte is needed or any buffer is sized.
    if len > MAX_FRAME {
        return Err(DecodeError::TooLong(len));
    }
    let end = 4 + len;
    let body = buf.get(4..end).ok_or(DecodeError::Incomplete)?;
    // A frame of length 0 has no tag byte.
    let (tag, payload) = body.split_first().ok_or(DecodeError::BadLength)?;
    Ok((*tag, payload, end))
}

fn fixed<const N: usize>(payload: &[u8]) -> Result<[u8; N], DecodeError> {
    if payload.len() > N {
        return Err(DecodeError::Trailing);
    }
    <[u8; N]>::try_from(payload).map_err(|_| DecodeError::BadLength)
}

pub fn decode_to_daemon(buf: &[u8]) -> Result<(ToDaemon, usize), DecodeError> {
    let (tag, payload, used) = split(buf)?;
    let msg = match tag {
        TAG_HELLO => ToDaemon::Hello { version: fixed::<1>(payload)?[0] },
        TAG_SET_MEMORY_MAX => ToDaemon::SetMemoryMax { bytes: u64::from_be_bytes(fixed::<8>(payload)?) },
        TAG_REFRESH_APP => ToDaemon::RefreshApp { uid: u32::from_be_bytes(fixed::<4>(payload)?) },
        TAG_REFRESH_ALL => {
            fixed::<0>(payload)?;
            ToDaemon::RefreshAll
        }
        TAG_SHUTDOWN => {
            fixed::<0>(payload)?;
            ToDaemon::Shutdown
        }
        other => return Err(DecodeError::UnknownTag(other)),
    };
    Ok((msg, used))
}

/// An interface name the daemon may announce: 1 to [`IFNAME_MAX`] bytes of `[A-Za-z0-9_.-]`, and not `.` or `..`
/// (the kernel refuses both).
pub fn valid_iface(name: &[u8]) -> bool {
    !name.is_empty()
        && name.len() <= IFNAME_MAX
        && name != b"."
        && name != b".."
        && name.iter().all(|b| matches!(b, b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'_' | b'.' | b'-'))
}

pub fn decode_from_daemon(buf: &[u8]) -> Result<(FromDaemon, usize), DecodeError> {
    let (tag, payload, used) = split(buf)?;
    let msg = match tag {
        TAG_READY => {
            if !valid_iface(payload) {
                return Err(DecodeError::BadIface);
            }
            let iface = String::from_utf8(payload.to_vec()).map_err(|_| DecodeError::BadIface)?;
            FromDaemon::Ready { iface }
        }
        TAG_STATE => {
            let state = match fixed::<1>(payload)?[0] {
                0 => DaemonState::Bootstrapping,
                1 => DaemonState::Up,
                2 => DaemonState::Failed,
                _ => return Err(DecodeError::BadValue),
            };
            FromDaemon::State { state }
        }
        TAG_FAILURES => {
            let p = fixed::<8>(payload)?;
            FromDaemon::Failures {
                uid: u32::from_be_bytes([p[0], p[1], p[2], p[3]]),
                count: u32::from_be_bytes([p[4], p[5], p[6], p[7]]),
            }
        }
        other => return Err(DecodeError::UnknownTag(other)),
    };
    Ok((msg, used))
}

/// The memory quota a peer may set: 32 MiB to 1 GiB.
pub fn validate_memory_max(bytes: u64) -> Result<u64, PolicyError> {
    if bytes < MEMORY_MIN {
        Err(PolicyError::TooSmall)
    } else if bytes > MEMORY_MAX {
        Err(PolicyError::TooLarge)
    } else {
        Ok(bytes)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn frame(tag: u8, payload: &[u8]) -> Vec<u8> {
        let mut v = ((payload.len() + 1) as u32).to_be_bytes().to_vec();
        v.push(tag);
        v.extend_from_slice(payload);
        v
    }

    #[test]
    fn every_message_to_the_daemon_round_trips() {
        let cases = [
            (ToDaemon::Hello { version: 1 }, 1),
            (ToDaemon::SetMemoryMax { bytes: 64 * 1024 * 1024 }, 8),
            (ToDaemon::RefreshApp { uid: 10231 }, 4),
            (ToDaemon::RefreshAll, 0),
            (ToDaemon::Shutdown, 0),
        ];
        for (msg, payload) in cases {
            let bytes = encode_to_daemon(&msg);
            assert_eq!(bytes.len(), 4 + 1 + payload, "{msg:?}");
            assert_eq!(decode_to_daemon(&bytes), Ok((msg, 4 + 1 + payload)));
        }
    }

    #[test]
    fn every_message_from_the_daemon_round_trips() {
        let cases = [
            (FromDaemon::Ready { iface: "tun0".into() }, 4),
            (FromDaemon::Ready { iface: "a".repeat(IFNAME_MAX) }, IFNAME_MAX),
            (FromDaemon::State { state: DaemonState::Bootstrapping }, 1),
            (FromDaemon::State { state: DaemonState::Up }, 1),
            (FromDaemon::State { state: DaemonState::Failed }, 1),
            (FromDaemon::Failures { uid: 10231, count: 7 }, 8),
        ];
        for (msg, payload) in cases {
            let bytes = encode_from_daemon(&msg);
            assert_eq!(bytes.len(), 4 + 1 + payload, "{msg:?}");
            assert_eq!(decode_from_daemon(&bytes), Ok((msg, 4 + 1 + payload)));
        }
    }

    #[test]
    fn wire_layout_is_fixed() {
        assert_eq!(encode_to_daemon(&ToDaemon::Hello { version: 7 }), [0, 0, 0, 2, 0x01, 7]);
        assert_eq!(
            encode_to_daemon(&ToDaemon::SetMemoryMax { bytes: 0x0102_0304_0506_0708 }),
            [0, 0, 0, 9, 0x02, 1, 2, 3, 4, 5, 6, 7, 8]
        );
        assert_eq!(encode_to_daemon(&ToDaemon::RefreshApp { uid: 0x0a0b_0c0d }), [0, 0, 0, 5, 0x03, 10, 11, 12, 13]);
        assert_eq!(encode_to_daemon(&ToDaemon::RefreshAll), [0, 0, 0, 1, 0x04]);
        assert_eq!(encode_to_daemon(&ToDaemon::Shutdown), [0, 0, 0, 1, 0x05]);
        assert_eq!(encode_from_daemon(&FromDaemon::Ready { iface: "t".into() }), [0, 0, 0, 2, 0x81, b't']);
        assert_eq!(encode_from_daemon(&FromDaemon::State { state: DaemonState::Failed }), [0, 0, 0, 2, 0x82, 2]);
        assert_eq!(
            encode_from_daemon(&FromDaemon::Failures { uid: 1, count: 2 }),
            [0, 0, 0, 9, 0x83, 0, 0, 0, 1, 0, 0, 0, 2]
        );
    }

    #[test]
    fn two_frames_in_one_buffer_decode_one_after_the_other() {
        let mut buf = encode_to_daemon(&ToDaemon::RefreshApp { uid: 5 });
        buf.extend(encode_to_daemon(&ToDaemon::Shutdown));
        let (first, used) = decode_to_daemon(&buf).unwrap();
        assert_eq!(first, ToDaemon::RefreshApp { uid: 5 });
        assert_eq!(decode_to_daemon(&buf[used..]), Ok((ToDaemon::Shutdown, 5)));
    }

    #[test]
    fn a_short_buffer_is_incomplete() {
        for n in 0..4 {
            assert_eq!(decode_to_daemon(&vec![0u8; n]), Err(DecodeError::Incomplete), "{n} bytes");
        }
        let full = encode_to_daemon(&ToDaemon::SetMemoryMax { bytes: 1 });
        for n in 4..full.len() {
            assert_eq!(decode_to_daemon(&full[..n]), Err(DecodeError::Incomplete), "{n} bytes");
        }
        let full = encode_from_daemon(&FromDaemon::Failures { uid: 1, count: 1 });
        for n in 0..full.len() {
            assert_eq!(decode_from_daemon(&full[..n]), Err(DecodeError::Incomplete), "{n} bytes");
        }
    }

    #[test]
    fn a_too_long_frame_is_refused_before_its_body_arrives() {
        // Only the four length bytes are present: the claimed length alone decides.
        let claimed = (MAX_FRAME + 1) as u32;
        assert_eq!(decode_to_daemon(&claimed.to_be_bytes()), Err(DecodeError::TooLong(MAX_FRAME + 1)));
        assert_eq!(decode_from_daemon(&claimed.to_be_bytes()), Err(DecodeError::TooLong(MAX_FRAME + 1)));
        assert_eq!(decode_to_daemon(&[0xff; 4]), Err(DecodeError::TooLong(u32::MAX as usize)));
    }

    #[test]
    fn a_frame_of_exactly_the_maximum_length_is_not_too_long() {
        // The limit is on tag plus payload; this one is complete but carries no such tag.
        let mut buf = (MAX_FRAME as u32).to_be_bytes().to_vec();
        buf.extend(vec![0x7f; MAX_FRAME]);
        assert_eq!(decode_to_daemon(&buf), Err(DecodeError::UnknownTag(0x7f)));
        // One byte missing is only incomplete.
        assert_eq!(decode_to_daemon(&buf[..buf.len() - 1]), Err(DecodeError::Incomplete));
    }

    #[test]
    fn an_empty_frame_has_no_tag() {
        assert_eq!(decode_to_daemon(&[0, 0, 0, 0]), Err(DecodeError::BadLength));
        assert_eq!(decode_from_daemon(&[0, 0, 0, 0]), Err(DecodeError::BadLength));
    }

    #[test]
    fn an_unknown_tag_is_refused_in_both_directions() {
        assert_eq!(decode_to_daemon(&frame(0x7f, &[])), Err(DecodeError::UnknownTag(0x7f)));
        assert_eq!(decode_from_daemon(&frame(0x7f, &[])), Err(DecodeError::UnknownTag(0x7f)));
        // A tag of the other direction is unknown here.
        assert_eq!(decode_to_daemon(&frame(0x81, b"tun0")), Err(DecodeError::UnknownTag(0x81)));
        assert_eq!(decode_from_daemon(&frame(0x01, &[1])), Err(DecodeError::UnknownTag(0x01)));
    }

    #[test]
    fn a_payload_of_the_wrong_size_is_refused() {
        assert_eq!(decode_to_daemon(&frame(0x01, &[1, 2])), Err(DecodeError::Trailing));
        assert_eq!(decode_to_daemon(&frame(0x01, &[])), Err(DecodeError::BadLength));
        assert_eq!(decode_to_daemon(&frame(0x02, &[0; 7])), Err(DecodeError::BadLength));
        assert_eq!(decode_to_daemon(&frame(0x02, &[0; 9])), Err(DecodeError::Trailing));
        assert_eq!(decode_to_daemon(&frame(0x03, &[0; 3])), Err(DecodeError::BadLength));
        assert_eq!(decode_to_daemon(&frame(0x03, &[0; 5])), Err(DecodeError::Trailing));
        assert_eq!(decode_to_daemon(&frame(0x04, &[0])), Err(DecodeError::Trailing));
        assert_eq!(decode_to_daemon(&frame(0x05, &[0])), Err(DecodeError::Trailing));
        assert_eq!(decode_from_daemon(&frame(0x82, &[])), Err(DecodeError::BadLength));
        assert_eq!(decode_from_daemon(&frame(0x82, &[1, 1])), Err(DecodeError::Trailing));
        assert_eq!(decode_from_daemon(&frame(0x83, &[0; 7])), Err(DecodeError::BadLength));
        assert_eq!(decode_from_daemon(&frame(0x83, &[0; 9])), Err(DecodeError::Trailing));
    }

    #[test]
    fn an_interface_name_is_bounded_and_plain() {
        assert_eq!(decode_from_daemon(&frame(0x81, &[b'a'; IFNAME_MAX + 1])), Err(DecodeError::BadIface));
        assert_eq!(decode_from_daemon(&frame(0x81, &[])), Err(DecodeError::BadIface));
        assert_eq!(decode_from_daemon(&frame(0x81, b"tun/0")), Err(DecodeError::BadIface));
        assert_eq!(decode_from_daemon(&frame(0x81, b"tun\0")), Err(DecodeError::BadIface));
        assert_eq!(decode_from_daemon(&frame(0x81, b"tun 0")), Err(DecodeError::BadIface));
        assert_eq!(decode_from_daemon(&frame(0x81, &[0xc3, 0xa9])), Err(DecodeError::BadIface));
        assert_eq!(decode_from_daemon(&frame(0x81, b".")), Err(DecodeError::BadIface));
        assert_eq!(decode_from_daemon(&frame(0x81, b"..")), Err(DecodeError::BadIface));
        assert_eq!(
            decode_from_daemon(&frame(0x81, b"Az09_.-")),
            Ok((FromDaemon::Ready { iface: "Az09_.-".into() }, 4 + 1 + 7))
        );
        assert_eq!(decode_from_daemon(&frame(0x81, &[b'a'; IFNAME_MAX])).map(|(_, n)| n), Ok(4 + 1 + IFNAME_MAX));
    }

    #[test]
    fn a_state_outside_the_three_is_refused() {
        assert_eq!(decode_from_daemon(&frame(0x82, &[3])), Err(DecodeError::BadValue));
        assert_eq!(decode_from_daemon(&frame(0x82, &[255])), Err(DecodeError::BadValue));
        assert_eq!(
            decode_from_daemon(&frame(0x82, &[0])),
            Ok((FromDaemon::State { state: DaemonState::Bootstrapping }, 6))
        );
    }

    #[test]
    fn the_limits_are_the_documented_values() {
        // Wire-level contract with the app: a changed limit is a protocol change, not a refactoring.
        assert_eq!(MAX_FRAME, 1024);
        assert_eq!(IFNAME_MAX, 15);
        assert_eq!(decode_from_daemon(&frame(0x81, &[b'a'; 16])), Err(DecodeError::BadIface));
        assert!(decode_from_daemon(&frame(0x81, &[b'a'; 15])).is_ok());
        let mut at_limit = 1024u32.to_be_bytes().to_vec();
        at_limit.extend(vec![0x7f; 1024]);
        assert_eq!(decode_to_daemon(&at_limit), Err(DecodeError::UnknownTag(0x7f)));
        assert_eq!(decode_to_daemon(&1025u32.to_be_bytes()), Err(DecodeError::TooLong(1025)));
    }

    #[test]
    fn no_input_makes_the_decoders_panic() {
        // Every tag byte with payloads of every size up to a few bytes, and every prefix of each frame.
        for tag in 0..=255u8 {
            for size in 0..12usize {
                let f = frame(tag, &vec![0x41; size]);
                for cut in 0..=f.len() {
                    let _ = decode_to_daemon(&f[..cut]);
                    let _ = decode_from_daemon(&f[..cut]);
                }
            }
        }
    }

    #[test]
    fn the_memory_bounds_are_32_mib_to_1_gib() {
        let mib = 1024 * 1024u64;
        assert_eq!(validate_memory_max(33 * mib), Ok(33 * mib));
        assert_eq!(validate_memory_max(32 * mib), Ok(32 * mib));
        assert_eq!(validate_memory_max(1024 * mib), Ok(1024 * mib));
        assert_eq!(validate_memory_max(31 * mib), Err(PolicyError::TooSmall));
        assert_eq!(validate_memory_max(32 * mib - 1), Err(PolicyError::TooSmall));
        assert_eq!(validate_memory_max(0), Err(PolicyError::TooSmall));
        assert_eq!(validate_memory_max(1024 * mib + 1), Err(PolicyError::TooLarge));
        assert_eq!(validate_memory_max(u64::MAX), Err(PolicyError::TooLarge));
    }
}
