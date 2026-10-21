// SPDX-License-Identifier: Apache-2.0
//! The control protocol of the route helper (ADR-186), same framing as the daemon's (`control`): a `u32` big-endian length
//! of tag plus payload, the tag, the payload. Structure is checked here; whether a specification is acceptable is the
//! business of [`super::spec::plan`]. Safe Rust only.

use super::spec::{RouteSpec, UidRange, MAX_RANGES};
use crate::control::{fixed, frame, split, DecodeError, ErrorCode};

/// The wire version of the helper protocol.
pub const ROUTE_PROTOCOL_VERSION: u8 = 1;

const TAG_HELLO: u8 = 0x01;
const TAG_APPLY: u8 = 0x02;
const TAG_CLEAR: u8 = 0x03;
const TAG_GET_APPLIED: u8 = 0x04;
const TAG_READY: u8 = 0x81;
const TAG_ACK: u8 = 0x84;
const TAG_ERROR: u8 = 0x85;
const TAG_APPLIED: u8 = 0x86;

const RANGE_BYTES: usize = 8;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RouteMsg {
    Hello { version: u8 },
    Apply { seq: u8, spec: RouteSpec },
    Clear { seq: u8 },
    GetApplied { seq: u8 },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RouteReply {
    Ready { version: u8 },
    Ack { seq: u8 },
    /// The number of rules in the band.
    Applied { seq: u8, rules: u16 },
    Error { seq: u8, code: ErrorCode },
}

fn put_ranges(p: &mut Vec<u8>, list: &[UidRange]) {
    // An encoder never emits more than the helper accepts.
    let list = &list[..list.len().min(MAX_RANGES)];
    p.push(list.len() as u8);
    for r in list {
        p.extend_from_slice(&r.start.to_be_bytes());
        p.extend_from_slice(&r.end.to_be_bytes());
    }
}

pub fn encode_msg(m: &RouteMsg) -> Vec<u8> {
    match m {
        RouteMsg::Hello { version } => frame(TAG_HELLO, &[*version]),
        RouteMsg::Apply { seq, spec } => {
            let name = spec.iface.as_bytes();
            let name = &name[..name.len().min(255)];
            let mut p = vec![*seq, name.len() as u8];
            p.extend_from_slice(name);
            p.extend_from_slice(&spec.oem_netid.unwrap_or(0).to_be_bytes());
            put_ranges(&mut p, &spec.steer);
            put_ranges(&mut p, &spec.tor_guard);
            frame(TAG_APPLY, &p)
        }
        RouteMsg::Clear { seq } => frame(TAG_CLEAR, &[*seq]),
        RouteMsg::GetApplied { seq } => frame(TAG_GET_APPLIED, &[*seq]),
    }
}

pub fn encode_reply(r: &RouteReply) -> Vec<u8> {
    match r {
        RouteReply::Ready { version } => frame(TAG_READY, &[*version]),
        RouteReply::Ack { seq } => frame(TAG_ACK, &[*seq]),
        RouteReply::Applied { seq, rules } => {
            let b = rules.to_be_bytes();
            frame(TAG_APPLIED, &[*seq, b[0], b[1]])
        }
        RouteReply::Error { seq, code } => frame(TAG_ERROR, &[*seq, *code as u8]),
    }
}

/// Reads a count byte and that many ranges from the front of `b`; returns the ranges and the rest.
fn take_ranges(b: &[u8]) -> Result<(Vec<UidRange>, &[u8]), DecodeError> {
    let (n, rest) = b.split_first().ok_or(DecodeError::BadLength)?;
    let want = usize::from(*n) * RANGE_BYTES;
    let body = rest.get(..want).ok_or(DecodeError::BadLength)?;
    let ranges = body
        .chunks_exact(RANGE_BYTES)
        .map(|c| UidRange {
            start: u32::from_be_bytes([c[0], c[1], c[2], c[3]]),
            end: u32::from_be_bytes([c[4], c[5], c[6], c[7]]),
        })
        .collect();
    Ok((ranges, &rest[want..]))
}

pub fn decode_msg(buf: &[u8]) -> Result<(RouteMsg, usize), DecodeError> {
    let (tag, payload, used) = split(buf)?;
    let msg = match tag {
        TAG_HELLO => RouteMsg::Hello { version: fixed::<1>(payload)?[0] },
        TAG_CLEAR => RouteMsg::Clear { seq: fixed::<1>(payload)?[0] },
        TAG_GET_APPLIED => RouteMsg::GetApplied { seq: fixed::<1>(payload)?[0] },
        TAG_APPLY => {
            let (seq, rest) = payload.split_first().ok_or(DecodeError::BadLength)?;
            let (name_len, rest) = rest.split_first().ok_or(DecodeError::BadLength)?;
            let name = rest.get(..usize::from(*name_len)).ok_or(DecodeError::BadLength)?;
            let rest = &rest[usize::from(*name_len)..];
            let iface = std::str::from_utf8(name).map_err(|_| DecodeError::BadIface)?.to_owned();
            let id: [u8; 4] = rest.get(..4).and_then(|b| <[u8; 4]>::try_from(b).ok()).ok_or(DecodeError::BadLength)?;
            let (steer, rest) = take_ranges(&rest[4..])?;
            let (tor_guard, rest) = take_ranges(rest)?;
            if !rest.is_empty() {
                return Err(DecodeError::Trailing);
            }
            let id = u32::from_be_bytes(id);
            RouteMsg::Apply { seq: *seq, spec: RouteSpec { iface, steer, tor_guard, oem_netid: (id != 0).then_some(id) } }
        }
        other => return Err(DecodeError::UnknownTag(other)),
    };
    Ok((msg, used))
}

pub fn decode_reply(buf: &[u8]) -> Result<(RouteReply, usize), DecodeError> {
    let (tag, payload, used) = split(buf)?;
    let msg = match tag {
        TAG_READY => RouteReply::Ready { version: fixed::<1>(payload)?[0] },
        TAG_ACK => RouteReply::Ack { seq: fixed::<1>(payload)?[0] },
        TAG_APPLIED => {
            let p = fixed::<3>(payload)?;
            RouteReply::Applied { seq: p[0], rules: u16::from_be_bytes([p[1], p[2]]) }
        }
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
            RouteReply::Error { seq: p[0], code }
        }
        other => return Err(DecodeError::UnknownTag(other)),
    };
    Ok((msg, used))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::control::MAX_FRAME;

    fn frm(tag: u8, payload: &[u8]) -> Vec<u8> {
        let mut v = ((payload.len() + 1) as u32).to_be_bytes().to_vec();
        v.push(tag);
        v.extend_from_slice(payload);
        v
    }

    fn r(start: u32, end: u32) -> UidRange {
        UidRange { start, end }
    }

    fn spec(steer: Vec<UidRange>, guard: Vec<UidRange>, id: Option<u32>) -> RouteSpec {
        RouteSpec { iface: "exit0".into(), steer, tor_guard: guard, oem_netid: id }
    }

    fn all_msgs() -> Vec<RouteMsg> {
        vec![
            RouteMsg::Hello { version: 1 },
            RouteMsg::Clear { seq: 3 },
            RouteMsg::GetApplied { seq: 4 },
            RouteMsg::Apply { seq: 5, spec: spec(vec![], vec![], None) },
            RouteMsg::Apply { seq: 6, spec: spec(vec![r(10000, 10230)], vec![r(10231, 10231), r(10300, 10399)], Some(101)) },
            RouteMsg::Apply { seq: 7, spec: spec((0..MAX_RANGES as u32).map(|i| r(10000 + 2 * i, 10000 + 2 * i)).collect(), vec![], None) },
        ]
    }

    fn all_replies() -> Vec<RouteReply> {
        let mut v = vec![
            RouteReply::Ready { version: 1 },
            RouteReply::Ack { seq: 9 },
            RouteReply::Applied { seq: 9, rules: 0 },
            RouteReply::Applied { seq: 9, rules: 0xabcd },
        ];
        for code in [ErrorCode::BadValue, ErrorCode::TooMany, ErrorCode::Busy, ErrorCode::Unsupported, ErrorCode::Failed] {
            v.push(RouteReply::Error { seq: 2, code });
        }
        v
    }

    #[test]
    fn every_message_and_reply_round_trips_with_its_exact_length() {
        for m in all_msgs() {
            let b = encode_msg(&m);
            assert_eq!(decode_msg(&b), Ok((m.clone(), b.len())), "{m:?}");
            assert!(b.len() - 4 <= MAX_FRAME, "the largest specification fits one frame");
        }
        for m in all_replies() {
            let b = encode_reply(&m);
            assert_eq!(decode_reply(&b), Ok((m.clone(), b.len())), "{m:?}");
        }
    }

    #[test]
    fn wire_layout_is_fixed() {
        assert_eq!(encode_msg(&RouteMsg::Hello { version: 1 }), [0, 0, 0, 2, 1, 1]);
        assert_eq!(encode_msg(&RouteMsg::Clear { seq: 3 }), [0, 0, 0, 2, 3, 3]);
        assert_eq!(encode_msg(&RouteMsg::GetApplied { seq: 4 }), [0, 0, 0, 2, 4, 4]);
        assert_eq!(
            encode_msg(&RouteMsg::Apply { seq: 5, spec: spec(vec![r(10000, 10230)], vec![r(10231, 10231)], Some(0x0102_0304)) }),
            [
                0, 0, 0, 30, 2, // frame: the tag and 29 bytes
                5, 5, b'e', b'x', b'i', b't', b'0', // seq, name
                1, 2, 3, 4, // network id
                1, 0, 0, 0x27, 0x10, 0, 0, 0x27, 0xf6, // one steer range
                1, 0, 0, 0x27, 0xf7, 0, 0, 0x27, 0xf7, // one guard range
            ]
        );
        assert_eq!(encode_reply(&RouteReply::Ready { version: 1 }), [0, 0, 0, 2, 0x81, 1]);
        assert_eq!(encode_reply(&RouteReply::Ack { seq: 9 }), [0, 0, 0, 2, 0x84, 9]);
        assert_eq!(encode_reply(&RouteReply::Applied { seq: 9, rules: 0x0102 }), [0, 0, 0, 4, 0x86, 9, 1, 2]);
        assert_eq!(encode_reply(&RouteReply::Error { seq: 9, code: ErrorCode::Failed }), [0, 0, 0, 3, 0x85, 9, 5]);
    }

    #[test]
    fn no_network_id_is_zero_on_the_wire_and_none_when_read() {
        let b = encode_msg(&RouteMsg::Apply { seq: 1, spec: spec(vec![], vec![], None) });
        assert_eq!(&b[b.len() - 6..b.len() - 2], &[0, 0, 0, 0]);
        assert!(matches!(decode_msg(&b), Ok((RouteMsg::Apply { spec: RouteSpec { oem_netid: None, .. }, .. }, _))));
    }

    #[test]
    fn a_truncated_frame_is_incomplete_at_every_cut() {
        for m in all_msgs() {
            let b = encode_msg(&m);
            for n in 0..b.len() {
                assert_eq!(decode_msg(&b[..n]), Err(DecodeError::Incomplete), "{m:?} cut {n}");
            }
        }
        for m in all_replies() {
            let b = encode_reply(&m);
            for n in 0..b.len() {
                assert_eq!(decode_reply(&b[..n]), Err(DecodeError::Incomplete));
            }
        }
    }

    #[test]
    fn a_wrong_size_or_tag_is_refused() {
        assert_eq!(decode_msg(&frm(1, &[])), Err(DecodeError::BadLength));
        assert_eq!(decode_msg(&frm(1, &[1, 2])), Err(DecodeError::Trailing));
        assert_eq!(decode_msg(&frm(3, &[1, 2])), Err(DecodeError::Trailing));
        assert_eq!(decode_msg(&frm(4, &[])), Err(DecodeError::BadLength));
        assert_eq!(decode_msg(&frm(0x7f, &[])), Err(DecodeError::UnknownTag(0x7f)));
        assert_eq!(decode_msg(&frm(0x81, &[1])), Err(DecodeError::UnknownTag(0x81)), "a reply tag is unknown to the helper");
        assert_eq!(decode_msg(&frm(5, &[1])), Err(DecodeError::UnknownTag(5)));
        assert_eq!(decode_reply(&frm(2, &[1])), Err(DecodeError::UnknownTag(2)));
        assert_eq!(decode_reply(&frm(0x86, &[1, 2])), Err(DecodeError::BadLength));
        assert_eq!(decode_reply(&frm(0x86, &[1, 2, 3, 4])), Err(DecodeError::Trailing));
        assert_eq!(decode_reply(&frm(0x85, &[1, 0])), Err(DecodeError::BadValue));
        assert_eq!(decode_reply(&frm(0x85, &[1, 6])), Err(DecodeError::BadValue));
        assert_eq!(decode_reply(&((MAX_FRAME + 1) as u32).to_be_bytes()), Err(DecodeError::TooLong(MAX_FRAME + 1)));
    }

    #[test]
    fn an_apply_must_match_its_counts_exactly() {
        let good = encode_msg(&RouteMsg::Apply { seq: 1, spec: spec(vec![r(10000, 10001)], vec![r(10010, 10011)], Some(5)) });
        let body = good[5..].to_vec();
        // One byte too many.
        let mut extra = body.clone();
        extra.push(0);
        assert_eq!(decode_msg(&frm(2, &extra)), Err(DecodeError::Trailing));
        // One byte too few at every position of the tail.
        for cut in 1..body.len() {
            let r = decode_msg(&frm(2, &body[..cut]));
            assert!(matches!(r, Err(DecodeError::BadLength)), "cut {cut}: {r:?}");
        }
        // A name length that runs past the payload.
        assert_eq!(decode_msg(&frm(2, &[1, 200, b'a'])), Err(DecodeError::BadLength));
        // A range count that claims more than the payload holds.
        let mut lie = body.clone();
        let steer_count_at = 2 + 5 + 4;
        lie[steer_count_at] = 200;
        assert_eq!(decode_msg(&frm(2, &lie)), Err(DecodeError::BadLength));
        // A name that is not text.
        assert_eq!(decode_msg(&frm(2, &[1, 2, 0xff, 0xfe, 0, 0, 0, 0, 0, 0])), Err(DecodeError::BadIface));
    }

    #[test]
    fn the_encoder_never_emits_more_ranges_than_the_helper_accepts() {
        let many: Vec<UidRange> = (0..300u32).map(|i| r(10000 + 2 * i, 10000 + 2 * i)).collect();
        let b = encode_msg(&RouteMsg::Apply { seq: 1, spec: spec(many.clone(), many, None) });
        let (m, used) = decode_msg(&b).unwrap();
        assert_eq!(used, b.len());
        let RouteMsg::Apply { spec, .. } = m else { panic!() };
        assert_eq!((spec.steer.len(), spec.tor_guard.len()), (MAX_RANGES, MAX_RANGES));
    }

    #[test]
    fn no_input_makes_the_decoders_panic() {
        for tag in 0..=255u8 {
            for size in 0..40usize {
                let f = frm(tag, &vec![0x41; size]);
                for cut in 0..=f.len() {
                    let _ = decode_msg(&f[..cut]);
                    let _ = decode_reply(&f[..cut]);
                }
                for count in [0u8, 1, 2, 255] {
                    let mut p = vec![0x41; size];
                    for at in [1usize, 2, 7, 8, 11, 12, 20] {
                        if let Some(b) = p.get_mut(at) {
                            *b = count;
                        }
                    }
                    let _ = decode_msg(&frm(tag, &p));
                }
            }
        }
    }
}
