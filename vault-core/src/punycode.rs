// SPDX-License-Identifier: Apache-2.0
//! Punycode (RFC 3492) for one hostname label, so that `bücher.de` and `xn--bcher-kva.de` are the same site.
//!
//! This is not the full IDNA mapping (no NFKC, no case folding beyond `to_lowercase`): a name written in a form the
//! browser would map differently simply does not match, which is the safe direction. Punycode is injective, so two
//! different labels never meet.

const BASE: u32 = 36;
const T_MIN: u32 = 1;
const T_MAX: u32 = 26;
const SKEW: u32 = 38;
const DAMP: u32 = 700;
const INITIAL_BIAS: u32 = 72;
const INITIAL_N: u32 = 128;

fn digit(d: u32) -> char {
    // 0..=25 -> a..z, 26..=35 -> 0..9
    if d < 26 {
        (b'a' + d as u8) as char
    } else {
        (b'0' + (d - 26) as u8) as char
    }
}

fn adapt(mut delta: u32, num_points: u32, first: bool) -> u32 {
    delta = if first { delta / DAMP } else { delta / 2 };
    delta += delta / num_points;
    let mut k = 0;
    while delta > ((BASE - T_MIN) * T_MAX) / 2 {
        delta /= BASE - T_MIN;
        k += BASE;
    }
    k + (BASE - T_MIN + 1) * delta / (delta + SKEW)
}

/// The label in ASCII: `xn--` and the punycode of `label`, or `None` when the arithmetic would overflow (a label that
/// long is refused later anyway).
pub(crate) fn to_ascii_label(label: &str) -> Option<String> {
    let input: Vec<u32> = label.chars().map(u32::from).collect();
    let mut out = String::from("xn--");
    for &c in input.iter().filter(|&&c| c < 128) {
        out.push(char::from_u32(c)?);
    }
    let basic = input.iter().filter(|&&c| c < 128).count() as u32;
    if basic > 0 {
        out.push('-');
    }
    let (mut n, mut delta, mut bias, mut handled) = (INITIAL_N, 0u32, INITIAL_BIAS, basic);
    while (handled as usize) < input.len() {
        let m = input.iter().copied().filter(|&c| c >= n).min()?;
        delta = delta.checked_add((m - n).checked_mul(handled + 1)?)?;
        n = m;
        for &c in &input {
            if c < n {
                delta = delta.checked_add(1)?;
            }
            if c == n {
                let mut q = delta;
                let mut k = BASE;
                loop {
                    let t = if k <= bias {
                        T_MIN
                    } else if k >= bias + T_MAX {
                        T_MAX
                    } else {
                        k - bias
                    };
                    if q < t {
                        break;
                    }
                    out.push(digit(t + (q - t) % (BASE - t)));
                    q = (q - t) / (BASE - t);
                    k += BASE;
                }
                out.push(digit(q));
                bias = adapt(delta, handled + 1, handled == basic);
                delta = 0;
                handled += 1;
            }
        }
        delta = delta.checked_add(1)?;
        n = n.checked_add(1)?;
    }
    Some(out)
}
