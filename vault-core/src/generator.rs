// SPDX-License-Identifier: Apache-2.0
//! The password generator: uniform choices by rejection sampling, never a bare modulo.

use rand::RngCore;
use zeroize::Zeroizing;

use crate::error::VaultError;

const LOWER: &[u8] = b"abcdefghijklmnopqrstuvwxyz";
const UPPER: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZ";
const DIGITS: &[u8] = b"0123456789";
// ASCII punctuation without the quote characters, the backslash and the backtick, which break in too many forms.
const SYMBOLS: &[u8] = b"!#$%&()*+,-./:;<=>?@[]^_{|}~";
const MAX_LENGTH: usize = 1024;
/// With every class on and length 4 about one draw in fourteen qualifies; this cap only ends a broken random source.
const MAX_ATTEMPTS: usize = 100_000;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct GenOpts {
    pub length: usize,
    pub lower: bool,
    pub upper: bool,
    pub digits: bool,
    pub symbols: bool,
}

/// A uniform number in `0..n` (`n >= 1`). Values from the incomplete last block of the 32-bit range are discarded:
/// taking them modulo `n` would make the first `2^32 mod n` symbols more likely than the rest.
pub fn uniform_below(rng: &mut dyn RngCore, n: usize) -> usize {
    let n = n.max(1) as u64;
    let zone = (1u64 << 32) / n * n;
    loop {
        let x = u64::from(rng.next_u32());
        if x < zone {
            return (x % n) as usize;
        }
    }
}

/// A password of `opts.length` characters in which every enabled class occurs at least once. The whole string is drawn
/// uniformly and redrawn until it qualifies, so no class is favoured by the guarantee.
/// `Corrupt` when no class is on or the length is below the number of classes, `TooLarge` above 1024.
pub fn generate(rng: &mut dyn RngCore, opts: &GenOpts) -> Result<String, VaultError> {
    let classes: Vec<&[u8]> = [(opts.lower, LOWER), (opts.upper, UPPER), (opts.digits, DIGITS), (opts.symbols, SYMBOLS)]
        .into_iter()
        .filter(|(on, _)| *on)
        .map(|(_, set)| set)
        .collect();
    if opts.length > MAX_LENGTH {
        return Err(VaultError::TooLarge);
    }
    if classes.is_empty() || opts.length < classes.len() {
        return Err(VaultError::Corrupt);
    }
    let alphabet: Vec<u8> = classes.concat();
    for _ in 0..MAX_ATTEMPTS {
        let candidate: Zeroizing<Vec<u8>> =
            Zeroizing::new((0..opts.length).map(|_| alphabet[uniform_below(rng, alphabet.len())]).collect());
        if classes.iter().all(|set| candidate.iter().any(|c| set.contains(c))) {
            return String::from_utf8(candidate.to_vec()).map_err(|_| VaultError::Corrupt);
        }
    }
    Err(VaultError::Corrupt)
}
