// SPDX-License-Identifier: Apache-2.0
//! Length-prefixed binary encoding for the vault plaintext. Everything read from a file is bounds-checked against the
//! bytes that remain before anything is allocated, so a header that claims four billion entries costs nothing.

use crate::error::VaultError;

/// The longest single field (a title, a password, a note): 64 KiB.
pub const MAX_FIELD: usize = 64 * 1024;

#[derive(Default)]
pub(crate) struct Writer(pub(crate) Vec<u8>);

impl Writer {
    pub(crate) fn u8(&mut self, v: u8) {
        self.0.push(v);
    }
    pub(crate) fn u32(&mut self, v: u32) {
        self.0.extend_from_slice(&v.to_le_bytes());
    }
    pub(crate) fn i64(&mut self, v: i64) {
        self.0.extend_from_slice(&v.to_le_bytes());
    }
    pub(crate) fn raw(&mut self, v: &[u8]) {
        self.0.extend_from_slice(v);
    }
    /// A string field: `u32` length, then the bytes. The caller has checked `len <= MAX_FIELD`.
    pub(crate) fn str(&mut self, s: &str) {
        self.u32(s.len() as u32);
        self.raw(s.as_bytes());
    }
}

pub(crate) struct Reader<'a> {
    buf: &'a [u8],
    pos: usize,
}

impl<'a> Reader<'a> {
    pub(crate) fn new(buf: &'a [u8]) -> Self {
        Reader { buf, pos: 0 }
    }

    fn remaining(&self) -> usize {
        self.buf.len() - self.pos
    }

    pub(crate) fn take(&mut self, n: usize) -> Result<&'a [u8], VaultError> {
        if n > self.remaining() {
            return Err(VaultError::Corrupt);
        }
        let s = &self.buf[self.pos..self.pos + n];
        self.pos += n;
        Ok(s)
    }

    pub(crate) fn u8(&mut self) -> Result<u8, VaultError> {
        Ok(self.take(1)?[0])
    }

    pub(crate) fn u32(&mut self) -> Result<u32, VaultError> {
        let b = self.take(4)?;
        Ok(u32::from_le_bytes([b[0], b[1], b[2], b[3]]))
    }

    pub(crate) fn i64(&mut self) -> Result<i64, VaultError> {
        let b = self.take(8)?;
        let mut a = [0u8; 8];
        a.copy_from_slice(b);
        Ok(i64::from_le_bytes(a))
    }

    pub(crate) fn id(&mut self) -> Result<[u8; 16], VaultError> {
        let mut a = [0u8; 16];
        a.copy_from_slice(self.take(16)?);
        Ok(a)
    }

    /// A string field. `TooLarge` above `MAX_FIELD` (checked before the bytes are looked at), `Corrupt` for a
    /// length beyond the buffer or bytes that are not UTF-8.
    pub(crate) fn str(&mut self) -> Result<String, VaultError> {
        let len = self.u32()? as usize;
        if len > MAX_FIELD {
            return Err(VaultError::TooLarge);
        }
        let bytes = self.take(len)?;
        core::str::from_utf8(bytes).map(str::to_owned).map_err(|_| VaultError::Corrupt)
    }

    /// An element count. `TooLarge` above `max`; `Corrupt` when the elements, at `min_each` bytes at least, cannot
    /// fit in what is left. The caller must not reserve more than the returned count, which is now backed by bytes.
    pub(crate) fn count(&mut self, max: usize, min_each: usize) -> Result<usize, VaultError> {
        let n = self.u32()? as usize;
        if n > max {
            return Err(VaultError::TooLarge);
        }
        match n.checked_mul(min_each) {
            Some(need) if need <= self.remaining() => Ok(n),
            _ => Err(VaultError::Corrupt),
        }
    }

    /// Everything not read yet.
    pub(crate) fn rest(self) -> &'a [u8] {
        &self.buf[self.pos..]
    }

    /// The whole buffer must have been consumed.
    pub(crate) fn finish(self) -> Result<(), VaultError> {
        if self.remaining() == 0 {
            Ok(())
        } else {
            Err(VaultError::Corrupt)
        }
    }
}
