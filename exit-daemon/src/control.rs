// SPDX-License-Identifier: Apache-2.0
//! The typed control protocol between the exit app and the daemon (ADR-184, ADR-186, ADR-188), version 4. Safe Rust only. A frame is
//! a `u32` big-endian length of tag plus payload, the tag byte, then the payload. A decoder checks the claimed length before
//! it looks at anything else and never allocates from it: the only allocations are bounded by [`MAX_FRAME`] (the interface
//! name, the class list, the company list) and the output of the encoders.
//!
//! Every request after `Hello` starts its payload with a sequence number that the reply echoes (`Ack` or `Error`), so the
//! app can tell which request an answer belongs to. The daemon never pushes: `GetState` returns the state, the failure
//! counts that are due and the companies that the speed guard passes on to an app (`Promoted`, with `fresh` set once), and
//! the app asks every few seconds. `Release` is the app's "Hide again" for one such company.

/// The largest tag-plus-payload a peer may claim.
pub const MAX_FRAME: usize = 4096;
/// The longest interface name the kernel accepts (IFNAMSIZ - 1).
pub const IFNAME_MAX: usize = 15;
/// The wire version of this module. A `Hello` of another version is answered with `Error { Unsupported }`.
pub const PROTOCOL_VERSION: u8 = 4;
/// The first uid of an ordinary app; ranges below it are never classified by the app.
pub const FIRST_APP_UID: u32 = 10000;
/// At most this many ranges in one class table.
/// The count is one byte on the wire: 256 would be written as 0 and the frame refused (found in the review of 2026-10-22).
pub const MAX_CLASSES: usize = 255;
/// At most this many companies in the override list of one uid.
pub const MAX_OVERRIDES: usize = 32;

/// Lower and upper bound of the Arti memory quota a peer may ask for (D-K decided 64 MiB, ADR-186; the bounds leave room
/// to tune it).
const MEMORY_MIN: u64 = 32 * 1024 * 1024;
const MEMORY_MAX: u64 = 1024 * 1024 * 1024;

const TAG_HELLO: u8 = 0x01;
const TAG_SET_MEMORY_MAX: u8 = 0x02;
const TAG_REFRESH_APP: u8 = 0x03;
const TAG_REFRESH_ALL: u8 = 0x04;
const TAG_SHUTDOWN: u8 = 0x05;
const TAG_GET_STATE: u8 = 0x06;
const TAG_SET_POLICY: u8 = 0x07;
const TAG_SET_OVERRIDES: u8 = 0x08;
const TAG_RELEASE: u8 = 0x09;
const TAG_SET_COUNTRY: u8 = 0x0a;
const TAG_READY: u8 = 0x81;
const TAG_STATE: u8 = 0x82;
const TAG_FAILURES: u8 = 0x83;
const TAG_ACK: u8 = 0x84;
const TAG_ERROR: u8 = 0x85;
const TAG_PROMOTED: u8 = 0x86;

/// Bytes of one class entry on the wire: start, end (`u32` each) and the kind.
const CLASS_BYTES: usize = 9;

/// What the daemon does with the flows of an app uid.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum Kind {
    /// All flows of the app leave through Tor.
    Tor = 1,
    /// Only flows to listed hosts leave through Tor (masked destinations).
    Masked = 2,
    /// The daemon does nothing for the app.
    Exempt = 3,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct UidClass {
    pub start: u32,
    pub end: u32,
    pub kind: Kind,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ToDaemon {
    Hello { version: u8 },
    SetMemoryMax { seq: u8, bytes: u64 },
    RefreshApp { seq: u8, uid: u32 },
    RefreshAll { seq: u8 },
    Shutdown { seq: u8 },
    GetState { seq: u8 },
    SetPolicy { seq: u8, classes: Vec<UidClass> },
    SetOverrides { seq: u8, uid: u32, companies: Vec<u16> },
    /// "Hide again" (ADR-188): ends the speed guard's promotion of `company` for `uid` and keeps it hidden until the daemon
    /// restarts.
    Release { seq: u8, uid: u32, company: u16 },
    /// The country whose exit relays the connections of `uid` must use (ADR-208: the place of an Isolated app); `None`
    /// removes the choice. Two capital ASCII letters, checked by [`valid_country`] on both sides.
    SetCountry { seq: u8, uid: u32, country: Option<[u8; 2]> },
}

/// A country code the daemon accepts: two capital ASCII letters (the exit app writes the persona's country this way).
pub fn valid_country(c: [u8; 2]) -> bool {
    c.iter().all(u8::is_ascii_uppercase)
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum DaemonState {
    Bootstrapping = 0,
    Up = 1,
    Failed = 2,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum ErrorCode {
    BadValue = 1,
    TooMany = 2,
    Busy = 3,
    Unsupported = 4,
    /// The request was well formed and allowed, and the kernel refused it (the route helper).
    Failed = 5,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum FromDaemon {
    Ready { version: u8, iface: String },
    State { state: DaemonState },
    /// A company id, never a host name (capability-boundaries.md).
    Failures { uid: u32, company: u16, count: u32 },
    /// The speed guard passes the names of `company` on to the app `uid` for now (ADR-188); `fresh` is true the first time it
    /// is reported, so the app notifies once. A company id, never a host name.
    Promoted { uid: u32, company: u16, fresh: bool },
    Ack { seq: u8 },
    Error { seq: u8, code: ErrorCode },
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

pub(crate) fn frame(tag: u8, payload: &[u8]) -> Vec<u8> {
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
        ToDaemon::SetMemoryMax { seq, bytes } => {
            let mut p = vec![*seq];
            p.extend_from_slice(&bytes.to_be_bytes());
            frame(TAG_SET_MEMORY_MAX, &p)
        }
        ToDaemon::RefreshApp { seq, uid } => {
            let mut p = vec![*seq];
            p.extend_from_slice(&uid.to_be_bytes());
            frame(TAG_REFRESH_APP, &p)
        }
        ToDaemon::RefreshAll { seq } => frame(TAG_REFRESH_ALL, &[*seq]),
        ToDaemon::Shutdown { seq } => frame(TAG_SHUTDOWN, &[*seq]),
        ToDaemon::GetState { seq } => frame(TAG_GET_STATE, &[*seq]),
        ToDaemon::SetPolicy { seq, classes } => {
            // An encoder never emits more than the daemon accepts; a caller validates the table first.
            let classes = &classes[..classes.len().min(MAX_CLASSES)];
            let mut p = vec![*seq, classes.len() as u8];
            for c in classes {
                p.extend_from_slice(&c.start.to_be_bytes());
                p.extend_from_slice(&c.end.to_be_bytes());
                p.push(c.kind as u8);
            }
            frame(TAG_SET_POLICY, &p)
        }
        ToDaemon::SetOverrides { seq, uid, companies } => {
            let companies = &companies[..companies.len().min(MAX_OVERRIDES)];
            let mut p = vec![*seq];
            p.extend_from_slice(&uid.to_be_bytes());
            p.push(companies.len() as u8);
            for c in companies {
                p.extend_from_slice(&c.to_be_bytes());
            }
            frame(TAG_SET_OVERRIDES, &p)
        }
        ToDaemon::Release { seq, uid, company } => {
            let mut p = vec![*seq];
            p.extend_from_slice(&uid.to_be_bytes());
            p.extend_from_slice(&company.to_be_bytes());
            frame(TAG_RELEASE, &p)
        }
        ToDaemon::SetCountry { seq, uid, country } => {
            debug_assert!(country.is_none_or(valid_country), "the caller validates the country");
            let mut p = vec![*seq];
            p.extend_from_slice(&uid.to_be_bytes());
            match country {
                Some(c) => {
                    p.push(2);
                    p.extend_from_slice(c);
                }
                None => p.push(0),
            }
            frame(TAG_SET_COUNTRY, &p)
        }
    }
}

pub fn encode_from_daemon(m: &FromDaemon) -> Vec<u8> {
    match m {
        FromDaemon::Ready { version, iface } => {
            debug_assert!(valid_iface(iface.as_bytes()), "the caller validates the interface name");
            let mut p = vec![*version];
            p.extend_from_slice(iface.as_bytes());
            frame(TAG_READY, &p)
        }
        FromDaemon::State { state } => frame(TAG_STATE, &[*state as u8]),
        FromDaemon::Failures { uid, company, count } => {
            let mut p = [0u8; 10];
            p[..4].copy_from_slice(&uid.to_be_bytes());
            p[4..6].copy_from_slice(&company.to_be_bytes());
            p[6..].copy_from_slice(&count.to_be_bytes());
            frame(TAG_FAILURES, &p)
        }
        FromDaemon::Promoted { uid, company, fresh } => {
            let mut p = [0u8; 7];
            p[..4].copy_from_slice(&uid.to_be_bytes());
            p[4..6].copy_from_slice(&company.to_be_bytes());
            p[6] = u8::from(*fresh);
            frame(TAG_PROMOTED, &p)
        }
        FromDaemon::Ack { seq } => frame(TAG_ACK, &[*seq]),
        FromDaemon::Error { seq, code } => frame(TAG_ERROR, &[*seq, *code as u8]),
    }
}

/// Splits one frame off the front of `buf`: the tag, the payload and the number of bytes the frame uses.
pub(crate) fn split(buf: &[u8]) -> Result<(u8, &[u8], usize), DecodeError> {
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

pub(crate) fn fixed<const N: usize>(payload: &[u8]) -> Result<[u8; N], DecodeError> {
    if payload.len() > N {
        return Err(DecodeError::Trailing);
    }
    <[u8; N]>::try_from(payload).map_err(|_| DecodeError::BadLength)
}

fn be32(b: &[u8]) -> Result<u32, DecodeError> {
    <[u8; 4]>::try_from(b).map(u32::from_be_bytes).map_err(|_| DecodeError::BadLength)
}

fn be16(b: &[u8]) -> Result<u16, DecodeError> {
    <[u8; 2]>::try_from(b).map(u16::from_be_bytes).map_err(|_| DecodeError::BadLength)
}

fn kind_from(b: u8) -> Result<Kind, DecodeError> {
    match b {
        1 => Ok(Kind::Tor),
        2 => Ok(Kind::Masked),
        3 => Ok(Kind::Exempt),
        _ => Err(DecodeError::BadValue),
    }
}

/// A payload that must be `head` bytes plus `count * unit` bytes, where the count byte sits at `count_at`.
fn counted(payload: &[u8], count_at: usize, head: usize, unit: usize) -> Result<(usize, &[u8]), DecodeError> {
    let count = usize::from(*payload.get(count_at).ok_or(DecodeError::BadLength)?);
    let want = head + count * unit;
    if payload.len() < want {
        return Err(DecodeError::BadLength);
    }
    if payload.len() > want {
        return Err(DecodeError::Trailing);
    }
    Ok((count, &payload[head..]))
}

pub fn decode_to_daemon(buf: &[u8]) -> Result<(ToDaemon, usize), DecodeError> {
    let (tag, payload, used) = split(buf)?;
    let msg = match tag {
        TAG_HELLO => ToDaemon::Hello { version: fixed::<1>(payload)?[0] },
        TAG_SET_MEMORY_MAX => {
            let p = fixed::<9>(payload)?;
            let mut b = [0u8; 8];
            b.copy_from_slice(&p[1..]);
            ToDaemon::SetMemoryMax { seq: p[0], bytes: u64::from_be_bytes(b) }
        }
        TAG_REFRESH_APP => {
            let p = fixed::<5>(payload)?;
            ToDaemon::RefreshApp { seq: p[0], uid: be32(&p[1..])? }
        }
        TAG_REFRESH_ALL => ToDaemon::RefreshAll { seq: fixed::<1>(payload)?[0] },
        TAG_SHUTDOWN => ToDaemon::Shutdown { seq: fixed::<1>(payload)?[0] },
        TAG_GET_STATE => ToDaemon::GetState { seq: fixed::<1>(payload)?[0] },
        TAG_SET_POLICY => {
            let (_, entries) = counted(payload, 1, 2, CLASS_BYTES)?;
            let mut classes = Vec::with_capacity(entries.len() / CLASS_BYTES);
            for e in entries.chunks_exact(CLASS_BYTES) {
                classes.push(UidClass { start: be32(&e[0..4])?, end: be32(&e[4..8])?, kind: kind_from(e[8])? });
            }
            ToDaemon::SetPolicy { seq: payload[0], classes }
        }
        TAG_SET_OVERRIDES => {
            let (_, entries) = counted(payload, 5, 6, 2)?;
            let companies = entries.chunks_exact(2).map(be16).collect::<Result<Vec<u16>, _>>()?;
            ToDaemon::SetOverrides { seq: payload[0], uid: be32(&payload[1..5])?, companies }
        }
        TAG_RELEASE => {
            let p = fixed::<7>(payload)?;
            ToDaemon::Release { seq: p[0], uid: be32(&p[1..5])?, company: be16(&p[5..7])? }
        }
        TAG_SET_COUNTRY => {
            // The count byte is the sixth byte; a country is nothing (no choice) or exactly two capital letters.
            let (_, letters) = counted(payload, 5, 6, 1)?;
            let country = match letters {
                [] => None,
                [a, b] if valid_country([*a, *b]) => Some([*a, *b]),
                _ => return Err(DecodeError::BadValue),
            };
            ToDaemon::SetCountry { seq: payload[0], uid: be32(&payload[1..5])?, country }
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
            let (version, name) = payload.split_first().ok_or(DecodeError::BadIface)?;
            if !valid_iface(name) {
                return Err(DecodeError::BadIface);
            }
            let iface = String::from_utf8(name.to_vec()).map_err(|_| DecodeError::BadIface)?;
            FromDaemon::Ready { version: *version, iface }
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
            let p = fixed::<10>(payload)?;
            FromDaemon::Failures { uid: be32(&p[0..4])?, company: be16(&p[4..6])?, count: be32(&p[6..10])? }
        }
        TAG_PROMOTED => {
            let p = fixed::<7>(payload)?;
            let fresh = match p[6] {
                0 => false,
                1 => true,
                _ => return Err(DecodeError::BadValue),
            };
            FromDaemon::Promoted { uid: be32(&p[0..4])?, company: be16(&p[4..6])?, fresh }
        }
        TAG_ACK => FromDaemon::Ack { seq: fixed::<1>(payload)?[0] },
        TAG_ERROR => {
            let p = fixed::<2>(payload)?;
            let code = match p[1] {
                1 => ErrorCode::BadValue,
                2 => ErrorCode::TooMany,
                3 => ErrorCode::Busy,
                4 => ErrorCode::Unsupported,
                5 => ErrorCode::Failed,
                _ => return Err(DecodeError::BadValue),
            };
            FromDaemon::Error { seq: p[0], code }
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

    fn frm(tag: u8, payload: &[u8]) -> Vec<u8> {
        let mut v = ((payload.len() + 1) as u32).to_be_bytes().to_vec();
        v.push(tag);
        v.extend_from_slice(payload);
        v
    }

    fn cls(start: u32, end: u32, kind: Kind) -> UidClass {
        UidClass { start, end, kind }
    }

    fn all_to_daemon() -> Vec<(ToDaemon, usize)> {
        vec![
            (ToDaemon::Hello { version: 2 }, 1),
            (ToDaemon::SetMemoryMax { seq: 1, bytes: 64 * 1024 * 1024 }, 9),
            (ToDaemon::RefreshApp { seq: 2, uid: 10231 }, 5),
            (ToDaemon::RefreshAll { seq: 3 }, 1),
            (ToDaemon::Shutdown { seq: 4 }, 1),
            (ToDaemon::GetState { seq: 5 }, 1),
            (ToDaemon::SetPolicy { seq: 6, classes: vec![] }, 2),
            (
                ToDaemon::SetPolicy {
                    seq: 7,
                    classes: vec![cls(10000, 10230, Kind::Masked), cls(10231, 10231, Kind::Tor), cls(10232, 19999, Kind::Exempt)],
                },
                2 + 3 * CLASS_BYTES,
            ),
            (ToDaemon::SetOverrides { seq: 8, uid: 10231, companies: vec![] }, 6),
            (ToDaemon::SetOverrides { seq: 9, uid: 10231, companies: vec![3, 9, 0xffff] }, 6 + 6),
            (ToDaemon::Release { seq: 10, uid: 10231, company: 12 }, 7),
            (ToDaemon::SetCountry { seq: 11, uid: 10231, country: Some(*b"ES") }, 8),
            (ToDaemon::SetCountry { seq: 12, uid: 10231, country: None }, 6),
        ]
    }

    fn all_from_daemon() -> Vec<(FromDaemon, usize)> {
        vec![
            (FromDaemon::Ready { version: 2, iface: "tun0".into() }, 1 + 4),
            (FromDaemon::Ready { version: 2, iface: "a".repeat(IFNAME_MAX) }, 1 + IFNAME_MAX),
            (FromDaemon::State { state: DaemonState::Bootstrapping }, 1),
            (FromDaemon::State { state: DaemonState::Up }, 1),
            (FromDaemon::State { state: DaemonState::Failed }, 1),
            (FromDaemon::Failures { uid: 10231, company: 12, count: 7 }, 10),
            (FromDaemon::Promoted { uid: 10231, company: 12, fresh: true }, 7),
            (FromDaemon::Promoted { uid: 10231, company: 12, fresh: false }, 7),
            (FromDaemon::Ack { seq: 200 }, 1),
            (FromDaemon::Error { seq: 3, code: ErrorCode::BadValue }, 2),
            (FromDaemon::Error { seq: 3, code: ErrorCode::TooMany }, 2),
            (FromDaemon::Error { seq: 3, code: ErrorCode::Busy }, 2),
            (FromDaemon::Error { seq: 3, code: ErrorCode::Unsupported }, 2),
            (FromDaemon::Error { seq: 3, code: ErrorCode::Failed }, 2),
        ]
    }

    #[test]
    fn every_message_to_the_daemon_round_trips() {
        for (msg, payload) in all_to_daemon() {
            let bytes = encode_to_daemon(&msg);
            assert_eq!(bytes.len(), 4 + 1 + payload, "{msg:?}");
            assert_eq!(decode_to_daemon(&bytes), Ok((msg, 4 + 1 + payload)));
        }
    }

    #[test]
    fn every_message_from_the_daemon_round_trips() {
        for (msg, payload) in all_from_daemon() {
            let bytes = encode_from_daemon(&msg);
            assert_eq!(bytes.len(), 4 + 1 + payload, "{msg:?}");
            assert_eq!(decode_from_daemon(&bytes), Ok((msg, 4 + 1 + payload)));
        }
    }

    #[test]
    fn wire_layout_is_fixed() {
        // Wire-level contract with the exit app (its codec has the same vectors): a change here is a protocol change.
        assert_eq!(encode_to_daemon(&ToDaemon::Hello { version: 2 }), [0, 0, 0, 2, 0x01, 2]);
        assert_eq!(
            encode_to_daemon(&ToDaemon::SetMemoryMax { seq: 9, bytes: 0x0102_0304_0506_0708 }),
            [0, 0, 0, 10, 0x02, 9, 1, 2, 3, 4, 5, 6, 7, 8]
        );
        assert_eq!(encode_to_daemon(&ToDaemon::RefreshApp { seq: 1, uid: 0x0a0b_0c0d }), [0, 0, 0, 6, 0x03, 1, 10, 11, 12, 13]);
        assert_eq!(encode_to_daemon(&ToDaemon::RefreshAll { seq: 4 }), [0, 0, 0, 2, 0x04, 4]);
        assert_eq!(encode_to_daemon(&ToDaemon::Shutdown { seq: 5 }), [0, 0, 0, 2, 0x05, 5]);
        assert_eq!(encode_to_daemon(&ToDaemon::GetState { seq: 6 }), [0, 0, 0, 2, 0x06, 6]);
        assert_eq!(
            encode_to_daemon(&ToDaemon::SetPolicy { seq: 7, classes: vec![cls(10000, 10230, Kind::Masked)] }),
            [0, 0, 0, 12, 0x07, 7, 1, 0, 0, 0x27, 0x10, 0, 0, 0x27, 0xf6, 2]
        );
        assert_eq!(
            encode_to_daemon(&ToDaemon::SetOverrides { seq: 8, uid: 10231, companies: vec![0x0102, 3] }),
            [0, 0, 0, 11, 0x08, 8, 0, 0, 0x27, 0xf7, 2, 1, 2, 0, 3]
        );
        assert_eq!(encode_from_daemon(&FromDaemon::Ready { version: 2, iface: "t".into() }), [0, 0, 0, 3, 0x81, 2, b't']);
        assert_eq!(encode_from_daemon(&FromDaemon::State { state: DaemonState::Failed }), [0, 0, 0, 2, 0x82, 2]);
        assert_eq!(
            encode_from_daemon(&FromDaemon::Failures { uid: 1, company: 0x0203, count: 4 }),
            [0, 0, 0, 11, 0x83, 0, 0, 0, 1, 2, 3, 0, 0, 0, 4]
        );
        assert_eq!(
            encode_to_daemon(&ToDaemon::Release { seq: 10, uid: 10231, company: 0x0102 }),
            [0, 0, 0, 8, 0x09, 10, 0, 0, 0x27, 0xf7, 1, 2]
        );
        assert_eq!(
            encode_to_daemon(&ToDaemon::SetCountry { seq: 12, uid: 10231, country: Some(*b"ES") }),
            [0, 0, 0, 9, 0x0a, 12, 0, 0, 0x27, 0xf7, 2, b'E', b'S']
        );
        assert_eq!(
            encode_to_daemon(&ToDaemon::SetCountry { seq: 12, uid: 10231, country: None }),
            [0, 0, 0, 7, 0x0a, 12, 0, 0, 0x27, 0xf7, 0]
        );
        assert_eq!(
            encode_from_daemon(&FromDaemon::Promoted { uid: 1, company: 0x0203, fresh: true }),
            [0, 0, 0, 8, 0x86, 0, 0, 0, 1, 2, 3, 1]
        );
        assert_eq!(
            encode_from_daemon(&FromDaemon::Promoted { uid: 1, company: 0x0203, fresh: false }),
            [0, 0, 0, 8, 0x86, 0, 0, 0, 1, 2, 3, 0]
        );
        assert_eq!(encode_from_daemon(&FromDaemon::Ack { seq: 9 }), [0, 0, 0, 2, 0x84, 9]);
        assert_eq!(encode_from_daemon(&FromDaemon::Error { seq: 9, code: ErrorCode::Unsupported }), [0, 0, 0, 3, 0x85, 9, 4]);
    }

    #[test]
    fn two_frames_in_one_buffer_decode_one_after_the_other() {
        let mut buf = encode_to_daemon(&ToDaemon::RefreshApp { seq: 1, uid: 5 });
        buf.extend(encode_to_daemon(&ToDaemon::Shutdown { seq: 2 }));
        let (first, used) = decode_to_daemon(&buf).unwrap();
        assert_eq!(first, ToDaemon::RefreshApp { seq: 1, uid: 5 });
        assert_eq!(decode_to_daemon(&buf[used..]), Ok((ToDaemon::Shutdown { seq: 2 }, 6)));
    }

    #[test]
    fn a_short_buffer_is_incomplete_at_every_cut() {
        for (msg, _) in all_to_daemon() {
            let full = encode_to_daemon(&msg);
            for n in 0..full.len() {
                assert_eq!(decode_to_daemon(&full[..n]), Err(DecodeError::Incomplete), "{msg:?} cut at {n}");
            }
        }
        for (msg, _) in all_from_daemon() {
            let full = encode_from_daemon(&msg);
            for n in 0..full.len() {
                assert_eq!(decode_from_daemon(&full[..n]), Err(DecodeError::Incomplete), "{msg:?} cut at {n}");
            }
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
        assert_eq!(decode_to_daemon(&frm(0x7f, &[])), Err(DecodeError::UnknownTag(0x7f)));
        assert_eq!(decode_from_daemon(&frm(0x7f, &[])), Err(DecodeError::UnknownTag(0x7f)));
        // A tag of the other direction is unknown here, and the next tag after each range is too.
        assert_eq!(decode_to_daemon(&frm(0x81, b"tun0")), Err(DecodeError::UnknownTag(0x81)));
        assert_eq!(decode_from_daemon(&frm(0x01, &[1])), Err(DecodeError::UnknownTag(0x01)));
        assert_eq!(decode_to_daemon(&frm(0x0b, &[1])), Err(DecodeError::UnknownTag(0x0b)));
        assert_eq!(decode_to_daemon(&frm(0x00, &[1])), Err(DecodeError::UnknownTag(0x00)));
        assert_eq!(decode_from_daemon(&frm(0x87, &[1])), Err(DecodeError::UnknownTag(0x87)));
    }

    #[test]
    fn a_payload_of_the_wrong_size_is_refused() {
        assert_eq!(decode_to_daemon(&frm(0x01, &[1, 2])), Err(DecodeError::Trailing));
        assert_eq!(decode_to_daemon(&frm(0x01, &[])), Err(DecodeError::BadLength));
        assert_eq!(decode_to_daemon(&frm(0x02, &[0; 8])), Err(DecodeError::BadLength));
        assert_eq!(decode_to_daemon(&frm(0x02, &[0; 10])), Err(DecodeError::Trailing));
        assert_eq!(decode_to_daemon(&frm(0x03, &[0; 4])), Err(DecodeError::BadLength));
        assert_eq!(decode_to_daemon(&frm(0x03, &[0; 6])), Err(DecodeError::Trailing));
        for tag in [0x04, 0x05, 0x06] {
            assert_eq!(decode_to_daemon(&frm(tag, &[])), Err(DecodeError::BadLength), "tag {tag}");
            assert_eq!(decode_to_daemon(&frm(tag, &[0, 0])), Err(DecodeError::Trailing), "tag {tag}");
        }
        assert_eq!(decode_from_daemon(&frm(0x82, &[])), Err(DecodeError::BadLength));
        assert_eq!(decode_from_daemon(&frm(0x82, &[1, 1])), Err(DecodeError::Trailing));
        assert_eq!(decode_from_daemon(&frm(0x83, &[0; 9])), Err(DecodeError::BadLength));
        assert_eq!(decode_from_daemon(&frm(0x83, &[0; 11])), Err(DecodeError::Trailing));
        assert_eq!(decode_from_daemon(&frm(0x84, &[])), Err(DecodeError::BadLength));
        assert_eq!(decode_from_daemon(&frm(0x84, &[1, 1])), Err(DecodeError::Trailing));
        assert_eq!(decode_from_daemon(&frm(0x85, &[1])), Err(DecodeError::BadLength));
        assert_eq!(decode_from_daemon(&frm(0x85, &[1, 1, 1])), Err(DecodeError::Trailing));
        assert_eq!(decode_to_daemon(&frm(0x09, &[0; 6])), Err(DecodeError::BadLength));
        assert_eq!(decode_to_daemon(&frm(0x09, &[0; 8])), Err(DecodeError::Trailing));
        assert_eq!(decode_from_daemon(&frm(0x86, &[0; 6])), Err(DecodeError::BadLength));
        assert_eq!(decode_from_daemon(&frm(0x86, &[0; 8])), Err(DecodeError::Trailing));
    }

    #[test]
    fn the_fresh_flag_of_a_promotion_is_zero_or_one() {
        let promoted = |fresh: u8| frm(0x86, &[0, 0, 0x27, 0xf7, 0, 12, fresh]);
        assert_eq!(
            decode_from_daemon(&promoted(0)).map(|(m, _)| m),
            Ok(FromDaemon::Promoted { uid: 10231, company: 12, fresh: false })
        );
        assert_eq!(
            decode_from_daemon(&promoted(1)).map(|(m, _)| m),
            Ok(FromDaemon::Promoted { uid: 10231, company: 12, fresh: true })
        );
        for bad in [2u8, 255] {
            assert_eq!(decode_from_daemon(&promoted(bad)), Err(DecodeError::BadValue), "fresh {bad}");
        }
    }

    #[test]
    fn a_counted_list_must_match_its_count_exactly() {
        let one = |extra: usize| {
            let mut p = vec![7, 1];
            p.extend_from_slice(&[0, 0, 0x27, 0x10, 0, 0, 0x27, 0x11, 1]);
            p.extend(vec![0; extra]);
            p
        };
        assert!(decode_to_daemon(&frm(0x07, &one(0))).is_ok());
        assert_eq!(decode_to_daemon(&frm(0x07, &one(1))), Err(DecodeError::Trailing));
        let mut short = one(0);
        short.pop();
        assert_eq!(decode_to_daemon(&frm(0x07, &short)), Err(DecodeError::BadLength));
        // A count that claims more than the frame can hold is a length error, not an allocation.
        assert_eq!(decode_to_daemon(&frm(0x07, &[7, 255])), Err(DecodeError::BadLength));
        assert_eq!(decode_to_daemon(&frm(0x07, &[7])), Err(DecodeError::BadLength));
        assert_eq!(decode_to_daemon(&frm(0x07, &[])), Err(DecodeError::BadLength));
        // Overrides: the count byte is the sixth byte.
        assert_eq!(decode_to_daemon(&frm(0x08, &[1, 0, 0, 0, 5])), Err(DecodeError::BadLength));
        assert_eq!(decode_to_daemon(&frm(0x08, &[1, 0, 0, 0, 5, 1, 0])), Err(DecodeError::BadLength));
        assert_eq!(decode_to_daemon(&frm(0x08, &[1, 0, 0, 0, 5, 1, 0, 1, 9])), Err(DecodeError::Trailing));
        assert_eq!(
            decode_to_daemon(&frm(0x08, &[1, 0, 0, 0, 5, 1, 0, 9])),
            Ok((ToDaemon::SetOverrides { seq: 1, uid: 5, companies: vec![9] }, 4 + 1 + 8))
        );
    }

    #[test]
    fn a_country_is_two_capital_letters_or_nothing() {
        let country = |letters: &[u8]| {
            let mut p = vec![1, 0, 0, 0x27, 0xf7, letters.len() as u8];
            p.extend_from_slice(letters);
            frm(0x0a, &p)
        };
        for bad in [&b"E"[..], b"ESP", b"es", b"eS", b"E1", b"1E", b"E ", b"\xc3\xa9", b"\0\0"] {
            assert_eq!(decode_to_daemon(&country(bad)), Err(DecodeError::BadValue), "{bad:?}");
        }
        assert_eq!(
            decode_to_daemon(&country(b"NL")).map(|(m, _)| m),
            Ok(ToDaemon::SetCountry { seq: 1, uid: 10231, country: Some(*b"NL") })
        );
        assert_eq!(decode_to_daemon(&country(b"")).map(|(m, _)| m), Ok(ToDaemon::SetCountry { seq: 1, uid: 10231, country: None }));
        // The letters must match the count byte exactly.
        assert_eq!(decode_to_daemon(&frm(0x0a, &[1, 0, 0, 0x27, 0xf7, 2, b'E'])), Err(DecodeError::BadLength));
        assert_eq!(decode_to_daemon(&frm(0x0a, &[1, 0, 0, 0x27, 0xf7, 0, b'E'])), Err(DecodeError::Trailing));
        assert_eq!(decode_to_daemon(&frm(0x0a, &[1, 0, 0, 0x27, 0xf7])), Err(DecodeError::BadLength));
        assert!(valid_country(*b"AZ") && !valid_country(*b"A@") && !valid_country(*b"A[") && !valid_country(*b"a\x41"));
    }

    #[test]
    fn a_class_kind_outside_the_three_is_refused() {
        let entry = |k: u8| frm(0x07, &[1, 1, 0, 0, 0x27, 0x10, 0, 0, 0x27, 0x10, k]);
        for bad in [0u8, 4, 255] {
            assert_eq!(decode_to_daemon(&entry(bad)), Err(DecodeError::BadValue), "kind {bad}");
        }
        for (good, kind) in [(1u8, Kind::Tor), (2, Kind::Masked), (3, Kind::Exempt)] {
            assert_eq!(
                decode_to_daemon(&entry(good)).map(|(m, _)| m),
                Ok(ToDaemon::SetPolicy { seq: 1, classes: vec![cls(10000, 10000, kind)] })
            );
        }
    }

    #[test]
    fn an_interface_name_is_bounded_and_plain() {
        let ready = |name: &[u8]| {
            let mut p = vec![2];
            p.extend_from_slice(name);
            frm(0x81, &p)
        };
        assert_eq!(decode_from_daemon(&ready(&[b'a'; IFNAME_MAX + 1])), Err(DecodeError::BadIface));
        assert_eq!(decode_from_daemon(&ready(&[])), Err(DecodeError::BadIface));
        assert_eq!(decode_from_daemon(&frm(0x81, &[])), Err(DecodeError::BadIface));
        assert_eq!(decode_from_daemon(&ready(b"tun/0")), Err(DecodeError::BadIface));
        assert_eq!(decode_from_daemon(&ready(b"tun\0")), Err(DecodeError::BadIface));
        assert_eq!(decode_from_daemon(&ready(b"tun 0")), Err(DecodeError::BadIface));
        assert_eq!(decode_from_daemon(&ready(&[0xc3, 0xa9])), Err(DecodeError::BadIface));
        assert_eq!(decode_from_daemon(&ready(b".")), Err(DecodeError::BadIface));
        assert_eq!(decode_from_daemon(&ready(b"..")), Err(DecodeError::BadIface));
        assert_eq!(
            decode_from_daemon(&ready(b"Az09_.-")),
            Ok((FromDaemon::Ready { version: 2, iface: "Az09_.-".into() }, 4 + 1 + 1 + 7))
        );
        assert_eq!(decode_from_daemon(&ready(&[b'a'; IFNAME_MAX])).map(|(_, n)| n), Ok(4 + 1 + 1 + IFNAME_MAX));
    }

    #[test]
    fn a_state_or_error_code_outside_the_defined_ones_is_refused() {
        assert_eq!(decode_from_daemon(&frm(0x82, &[3])), Err(DecodeError::BadValue));
        assert_eq!(decode_from_daemon(&frm(0x82, &[255])), Err(DecodeError::BadValue));
        assert_eq!(
            decode_from_daemon(&frm(0x82, &[0])),
            Ok((FromDaemon::State { state: DaemonState::Bootstrapping }, 6))
        );
        assert_eq!(decode_from_daemon(&frm(0x85, &[1, 0])), Err(DecodeError::BadValue));
        assert_eq!(decode_from_daemon(&frm(0x85, &[1, 6])), Err(DecodeError::BadValue));
        assert_eq!(decode_from_daemon(&frm(0x85, &[1, 255])), Err(DecodeError::BadValue));
    }

    #[test]
    fn the_limits_are_the_documented_values() {
        // Wire-level contract with the app: a changed limit is a protocol change, not a refactoring.
        assert_eq!(MAX_FRAME, 4096);
        assert_eq!(IFNAME_MAX, 15);
        assert_eq!(PROTOCOL_VERSION, 4);
        assert_eq!(FIRST_APP_UID, 10000);
        assert_eq!(MAX_CLASSES, 255, "the count is one byte");
        assert!(MAX_CLASSES <= usize::from(u8::MAX));
        assert_eq!(MAX_OVERRIDES, 32);
        let ready = |n: usize| {
            let mut p = vec![2];
            p.extend(vec![b'a'; n]);
            frm(0x81, &p)
        };
        assert_eq!(decode_from_daemon(&ready(16)), Err(DecodeError::BadIface));
        assert!(decode_from_daemon(&ready(15)).is_ok());
        let mut at_limit = 4096u32.to_be_bytes().to_vec();
        at_limit.extend(vec![0x7f; 4096]);
        assert_eq!(decode_to_daemon(&at_limit), Err(DecodeError::UnknownTag(0x7f)));
        assert_eq!(decode_to_daemon(&4097u32.to_be_bytes()), Err(DecodeError::TooLong(4097)));
    }

    #[test]
    fn the_encoder_never_emits_more_than_the_daemon_accepts() {
        let many: Vec<UidClass> = (0..400u32).map(|i| cls(10000 + 2 * i, 10000 + 2 * i, Kind::Tor)).collect();
        let bytes = encode_to_daemon(&ToDaemon::SetPolicy { seq: 1, classes: many });
        assert_eq!(bytes.len(), 4 + 1 + 2 + MAX_CLASSES * CLASS_BYTES);
        assert!(bytes.len() - 4 <= MAX_FRAME);
        let companies: Vec<u16> = (0..100).collect();
        let bytes = encode_to_daemon(&ToDaemon::SetOverrides { seq: 1, uid: 10000, companies });
        assert_eq!(bytes.len(), 4 + 1 + 6 + MAX_OVERRIDES * 2);
    }

    #[test]
    fn a_full_class_table_survives_the_wire() {
        // The count is one byte: a table at the limit must come back whole, not as 0 entries with bytes left over.
        let full: Vec<UidClass> = (0..MAX_CLASSES as u32).map(|i| cls(10000 + 2 * i, 10000 + 2 * i, Kind::Tor)).collect();
        let bytes = encode_to_daemon(&ToDaemon::SetPolicy { seq: 9, classes: full.clone() });
        match decode_to_daemon(&bytes) {
            Ok((ToDaemon::SetPolicy { seq: 9, classes }, used)) => {
                assert_eq!(classes, full);
                assert_eq!(used, bytes.len());
            }
            other => panic!("{other:?}"),
        }
    }

    #[test]
    fn no_input_makes_the_decoders_panic() {
        // Every tag byte with payloads of every size up to a few bytes, and every prefix of each frame.
        for tag in 0..=255u8 {
            for size in 0..24usize {
                let f = frm(tag, &vec![0x41; size]);
                for cut in 0..=f.len() {
                    let _ = decode_to_daemon(&f[..cut]);
                    let _ = decode_from_daemon(&f[..cut]);
                }
                // The same with a count byte that is zero, small and large where a list is expected.
                for count in [0u8, 1, 2, 255] {
                    let mut p = vec![0x41; size];
                    if let Some(b) = p.get_mut(1) {
                        *b = count;
                    }
                    if let Some(b) = p.get_mut(5) {
                        *b = count;
                    }
                    let _ = decode_to_daemon(&frm(tag, &p));
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
