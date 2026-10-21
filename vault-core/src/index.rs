// SPDX-License-Identifier: Apache-2.0
//! The domain index: which sites and apps have an account, answered without the vault being open.
//!
//! An entry matches a site when the exact registrable domain (public suffix list, no fuzzy matching) is the same, and an
//! app when the exact package name is the same. The index stores only HMAC-SHA-256 tags (16 bytes) of those, keyed by a
//! Keystore key, so a copy of it names no site. The lookup runs inside the autofill service only.

use hmac::{Hmac, Mac};
use sha2::Sha256;

use crate::error::{fatal, VaultError};
use crate::punycode;
use crate::vault::Vault;

const MAGIC: &[u8; 4] = b"FHIX";
const VERSION: u8 = 1;
const HEADER: usize = 4 + 1 + 4;
const TAG: usize = 16;
/// 100 000 entries with 256 domains and 256 packages each is far beyond use; this bounds an index read from disk.
const MAX_TAGS: usize = 1_000_000;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind {
    Domain,
    Package,
}

/// The registrable domain of `host` in lower-case punycode form, or `None` when `host` is not a name that can own an
/// account: an IP literal (including `2130706433` and `127.1`), a bare public suffix, a name with an empty, over-long or
/// invalid label, or anything with a path, user info or space. One trailing dot and a port are dropped.
pub fn registrable_domain(host: &str) -> Option<String> {
    if host.is_empty() || host.starts_with('[') {
        return None;
    }
    let host = match host.matches(':').count() {
        0 => host,
        1 => {
            let (name, port) = host.split_once(':')?;
            if port.is_empty() || port.len() > 5 || !port.bytes().all(|b| b.is_ascii_digit()) || port.parse::<u32>().ok()? > 65_535 {
                return None;
            }
            name
        }
        _ => return None, // a bare IPv6 address
    };
    let host = host.strip_suffix('.').unwrap_or(host);
    if host.is_empty() {
        return None;
    }
    let mut labels = Vec::new();
    for label in host.to_lowercase().split('.') {
        let label = if label.is_ascii() { label.to_owned() } else { punycode::to_ascii_label(label)? };
        let valid = (1..=63).contains(&label.len())
            && label.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-')
            && !label.starts_with('-')
            && !label.ends_with('-');
        if !valid {
            return None;
        }
        labels.push(label);
    }
    // A top-level label is never all digits, so such a name is an IPv4 address in one of its short forms.
    if labels.last()?.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    let joined = labels.join(".");
    if joined.len() > 253 {
        return None;
    }
    psl::domain_str(&joined).map(str::to_owned)
}

fn tag(key: &[u8; 32], kind: Kind, subject: &[u8]) -> [u8; TAG] {
    let Ok(mut mac) = <Hmac<Sha256> as Mac>::new_from_slice(key) else { fatal() };
    mac.update(&[match kind {
        Kind::Domain => 1,
        Kind::Package => 2,
    }]);
    mac.update(subject);
    let full = mac.finalize().into_bytes();
    let mut out = [0u8; TAG];
    out.copy_from_slice(&full[..TAG]);
    out
}

/// Sorted, de-duplicated tags.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DomainIndex {
    tags: Vec<[u8; TAG]>,
}

impl DomainIndex {
    /// Indexes every domain and package of the vault under `key`. A domain that has no registrable form is skipped.
    pub fn build(key: &[u8; 32], vault: &Vault) -> DomainIndex {
        let mut tags = Vec::new();
        for e in vault.entries() {
            for d in &e.domains {
                if let Some(r) = registrable_domain(d) {
                    tags.push(tag(key, Kind::Domain, r.as_bytes()));
                }
            }
            for p in e.packages.iter().filter(|p| !p.is_empty()) {
                tags.push(tag(key, Kind::Package, p.as_bytes()));
            }
        }
        tags.sort_unstable();
        tags.dedup();
        DomainIndex { tags }
    }

    /// True when `subject` (a host as the browser reports it, or an exact package name) has an entry.
    pub fn contains(&self, key: &[u8; 32], subject: &str, kind: Kind) -> bool {
        let t = match kind {
            Kind::Domain => match registrable_domain(subject) {
                Some(r) => tag(key, kind, r.as_bytes()),
                None => return false,
            },
            Kind::Package if subject.is_empty() => return false,
            Kind::Package => tag(key, kind, subject.as_bytes()),
        };
        self.tags.binary_search(&t).is_ok()
    }

    pub fn to_bytes(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(HEADER + self.tags.len() * TAG);
        out.extend_from_slice(MAGIC);
        out.push(VERSION);
        out.extend_from_slice(&(self.tags.len() as u32).to_le_bytes());
        self.tags.iter().for_each(|t| out.extend_from_slice(t));
        out
    }

    /// Reads an index back. The tag count is checked against the limit and against the bytes present before anything
    /// is allocated, and the tags must be strictly ascending (the lookup is a binary search).
    pub fn from_bytes(bytes: &[u8]) -> Result<DomainIndex, VaultError> {
        if bytes.len() < HEADER || &bytes[..4] != MAGIC {
            return Err(VaultError::Corrupt);
        }
        if bytes[4] != VERSION {
            return Err(VaultError::UnsupportedVersion(u16::from(bytes[4])));
        }
        let count = u32::from_le_bytes([bytes[5], bytes[6], bytes[7], bytes[8]]) as usize;
        if count > MAX_TAGS {
            return Err(VaultError::TooLarge);
        }
        if count.checked_mul(TAG).and_then(|n| n.checked_add(HEADER)) != Some(bytes.len()) {
            return Err(VaultError::Corrupt);
        }
        let tags: Vec<[u8; TAG]> = bytes[HEADER..]
            .chunks_exact(TAG)
            .map(|c| {
                let mut t = [0u8; TAG];
                t.copy_from_slice(c);
                t
            })
            .collect();
        if tags.windows(2).any(|w| w[0] >= w[1]) {
            return Err(VaultError::Corrupt);
        }
        Ok(DomainIndex { tags })
    }
}
