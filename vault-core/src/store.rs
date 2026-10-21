// SPDX-License-Identifier: Apache-2.0
//! The vault file on disk: replaced atomically, read with a size bound.

use std::fs::{self, File, OpenOptions};
use std::io::{self, Read, Write};
use std::os::unix::fs::OpenOptionsExt;
use std::path::Path;

use crate::envelope::MAX_BLOB;
use crate::error::VaultError;

/// The points of [`write_atomic`] where a crash can happen. The hook runs after each step; tests make it fail to stand
/// for a crash.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum WriteStep {
    TempCreated,
    TempSynced,
    Renamed,
    DirSynced,
}

/// Replaces `path` with `bytes` so that a crash at any point leaves the old file or the new one, never a mix: write a
/// temp file in the same directory (mode 0600), fsync it, rename it over `path`, fsync the directory. A temp file left
/// by an earlier crash is removed first.
pub fn write_atomic(path: &Path, bytes: &[u8], hook: &mut dyn FnMut(WriteStep) -> io::Result<()>) -> io::Result<()> {
    let dir = match path.parent() {
        Some(p) if !p.as_os_str().is_empty() => p,
        _ => Path::new("."),
    };
    let name = path.file_name().ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "no file name"))?;
    let tmp = dir.join(format!(".{}.tmp", name.to_string_lossy()));
    match fs::remove_file(&tmp) {
        Err(e) if e.kind() != io::ErrorKind::NotFound => return Err(e),
        _ => {}
    }
    let mut f = OpenOptions::new().write(true).create_new(true).mode(0o600).open(&tmp)?;
    hook(WriteStep::TempCreated)?;
    f.write_all(bytes)?;
    f.sync_all()?;
    drop(f);
    hook(WriteStep::TempSynced)?;
    fs::rename(&tmp, path)?;
    hook(WriteStep::Renamed)?;
    File::open(dir)?.sync_all()?;
    hook(WriteStep::DirSynced)
}

/// Reads a vault file of at most [`MAX_BLOB`] bytes. `TooLarge` is decided from the size of the open file before a
/// byte is read; `Io` for any failure to open or read.
pub fn read_bounded(path: &Path) -> Result<Vec<u8>, VaultError> {
    let f = File::open(path).map_err(|_| VaultError::Io)?;
    let len = f.metadata().map_err(|_| VaultError::Io)?.len();
    if len > MAX_BLOB as u64 {
        return Err(VaultError::TooLarge);
    }
    let mut buf = Vec::with_capacity(len as usize);
    // One byte more than the limit: a file that grew after the size check is still caught.
    f.take(MAX_BLOB as u64 + 1).read_to_end(&mut buf).map_err(|_| VaultError::Io)?;
    if buf.len() > MAX_BLOB {
        return Err(VaultError::TooLarge);
    }
    Ok(buf)
}
