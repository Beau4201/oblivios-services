// SPDX-License-Identifier: Apache-2.0
//! The Arti configuration of the daemon (ADR-184). The memory quota is always written explicitly and read back after
//! loading: Arti's default is several GiB, and a key that the loader ignores would leave it in force without an error.
//! A configuration that does not load, or that loads without the quota, stops the daemon.

use crate::control::validate_memory_max;
use arti_client::config::ConfigurationSource;
use arti_client::TorClientConfig;
use onion_tunnel::config::TunnelConfig;
use std::io::Write;
use std::os::unix::fs::OpenOptionsExt;
use std::path::{Path, PathBuf};
use tor_config::sources::MustRead;
use tor_config::ConfigurationSources;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DaemonConfig {
    pub state_dir: PathBuf,
    pub cache_dir: PathBuf,
    /// Arti's memory quota in bytes; checked against the control protocol's bounds.
    pub memory_max: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ConfigError {
    /// The quota is outside the allowed bounds, or the loaded configuration does not carry it.
    MemoryMax,
    /// The file or a path in it is not acceptable, or the loader refused it.
    Toml(String),
    Io(String),
}

/// A TOML basic string. The caller has checked that `s` holds no control character.
fn toml_string(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 2);
    out.push('"');
    for c in s.chars() {
        if c == '\\' || c == '"' {
            out.push('\\');
        }
        out.push(c);
    }
    out.push('"');
    out
}

/// The text of the Arti configuration for `c`. Path checks are `check_dirs`; the quota check is `tunnel_config`.
pub fn arti_toml(c: &DaemonConfig) -> String {
    format!(
        "[storage]\nstate_dir = {}\ncache_dir = {}\n\n[system.memory]\nmax = {}\n",
        toml_string(&c.state_dir.to_string_lossy()),
        toml_string(&c.cache_dir.to_string_lossy()),
        c.memory_max
    )
}

/// Arti expands `~` and `${VARIABLE}` in a path from a file; the daemon wants the literal directory it was given.
fn check_dir(name: &str, p: &Path) -> Result<(), ConfigError> {
    let bad = |why: &str| Err(ConfigError::Toml(format!("{name}: {why}")));
    let Some(s) = p.to_str() else { return bad("not UTF-8") };
    if !p.is_absolute() {
        return bad("not an absolute path");
    }
    if s.chars().any(|c| c.is_control()) {
        return bad("holds a control character");
    }
    if s.contains('$') || s.starts_with('~') {
        return bad("uses a character that Arti expands");
    }
    Ok(())
}

/// Loads `path` the way `TunnelConfig` does when it runs the tunnel, so the result is what the tunnel will use.
pub fn load_arti(path: &Path) -> Result<TorClientConfig, ConfigError> {
    let mut sources = ConfigurationSources::new_empty();
    sources.push_source(ConfigurationSource::from_path(path), MustRead::MustRead);
    let loaded = sources.load().map_err(|e| ConfigError::Toml(e.to_string()))?;
    tor_config::resolve::<TorClientConfig>(loaded).map_err(|e| ConfigError::Toml(e.to_string()))
}

/// The loaded configuration must carry exactly the requested quota. Neither `tor_memquota::Config` nor the field of
/// `TorClientConfig` has a public accessor (`system_memory()` exists only with arti-client's `testing` feature, which a
/// production build must not enable), so the check reads the `Debug` text; a change of that text makes this fail
/// closed, never pass, and the unit tests catch it at the next Arti upgrade.
pub fn check_quota(cfg: &TorClientConfig, bytes: u64) -> Result<(), ConfigError> {
    let text = format!("{cfg:?}");
    if text.contains(&format!("max: ByteQty({bytes})")) {
        Ok(())
    } else {
        Err(ConfigError::MemoryMax)
    }
}

fn io(e: std::io::Error) -> ConfigError {
    ConfigError::Io(e.kind().to_string())
}

/// Writes `text` to `path` with mode 0600, through a new file that is renamed into place.
fn write_private(path: &Path, text: &str) -> Result<(), ConfigError> {
    let tmp = path.with_extension("tmp");
    match std::fs::remove_file(&tmp) {
        Ok(()) => {}
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
        Err(e) => return Err(io(e)),
    }
    let mut f = std::fs::OpenOptions::new().write(true).create_new(true).mode(0o600).open(&tmp).map_err(io)?;
    f.write_all(text.as_bytes()).map_err(io)?;
    f.sync_all().map_err(io)?;
    std::fs::rename(&tmp, path).map_err(io)
}

/// Writes the Arti configuration to `toml_path` (0600) and returns the tunnel configuration that points at it. The
/// permission checks of Arti stay on.
pub fn tunnel_config(c: &DaemonConfig, toml_path: &Path) -> Result<TunnelConfig, ConfigError> {
    validate_memory_max(c.memory_max).map_err(|_| ConfigError::MemoryMax)?;
    check_dir("state_dir", &c.state_dir)?;
    check_dir("cache_dir", &c.cache_dir)?;
    write_private(toml_path, &arti_toml(c))?;
    check_quota(&load_arti(toml_path)?, c.memory_max)?;
    let mut t = TunnelConfig::default();
    t.arti_cfg_path = Some(toml_path.to_path_buf());
    Ok(t)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::unix::fs::{DirBuilderExt, PermissionsExt};
    use std::path::{Path, PathBuf};

    const MIB: u64 = 1024 * 1024;

    /// A fresh 0700 directory under the temp dir, with the state and cache directories inside it.
    fn workdir(tag: &str) -> (PathBuf, DaemonConfig) {
        let nanos = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos();
        let root = std::env::temp_dir().join(format!("exit-daemon-{tag}-{}-{nanos}", std::process::id()));
        std::fs::DirBuilder::new().mode(0o700).create(&root).unwrap();
        let state = root.join("state");
        let cache = root.join("cache");
        for d in [&state, &cache] {
            std::fs::DirBuilder::new().mode(0o700).create(d).unwrap();
        }
        (root, DaemonConfig { state_dir: state, cache_dir: cache, memory_max: 64 * MIB })
    }

    #[test]
    fn loads_memory_max_and_directories() {
        let (root, c) = workdir("load");
        let toml = root.join("arti.toml");
        let cfg = tunnel_config(&c, &toml).unwrap();
        assert_eq!(cfg.arti_cfg_path.as_deref(), Some(toml.as_path()));
        assert!(!cfg.disable_fs_permission_checks, "the permission checks stay on");
        // Loaded the way TunnelConfig does it: this is what the tunnel will run with.
        let tcc = load_arti(&toml).unwrap();
        let all = format!("{tcc:?}");
        assert!(all.contains(&format!("max: ByteQty({})", 64 * MIB)), "quota not applied: {all}");
        assert!(all.contains(c.state_dir.to_str().unwrap()), "state dir not applied: {all}");
        assert!(all.contains(c.cache_dir.to_str().unwrap()), "cache dir not applied: {all}");
        std::fs::remove_dir_all(&root).unwrap();
    }

    #[test]
    fn the_file_is_private() {
        let (root, c) = workdir("mode");
        let toml = root.join("arti.toml");
        tunnel_config(&c, &toml).unwrap();
        assert_eq!(std::fs::metadata(&toml).unwrap().permissions().mode() & 0o777, 0o600);
        // Written again over an existing file: still private, and no temporary file is left behind.
        tunnel_config(&c, &toml).unwrap();
        assert_eq!(std::fs::metadata(&toml).unwrap().permissions().mode() & 0o777, 0o600);
        assert_eq!(std::fs::read_dir(&root).unwrap().count(), 3, "state, cache and arti.toml only");
        std::fs::remove_dir_all(&root).unwrap();
    }

    #[test]
    fn default_quota_is_not_accepted() {
        let (root, mut c) = workdir("zero");
        c.memory_max = 0;
        assert_eq!(tunnel_config(&c, &root.join("arti.toml")).unwrap_err(), ConfigError::MemoryMax);
        c.memory_max = 1024 * MIB + 1;
        assert_eq!(tunnel_config(&c, &root.join("arti.toml")).unwrap_err(), ConfigError::MemoryMax);
        c.memory_max = u64::MAX;
        assert_eq!(tunnel_config(&c, &root.join("arti.toml")).unwrap_err(), ConfigError::MemoryMax);
        assert!(!root.join("arti.toml").exists(), "nothing is written for a refused quota");
        std::fs::remove_dir_all(&root).unwrap();
    }

    #[test]
    fn a_configuration_that_does_not_apply_the_quota_is_refused() {
        // A key the loader ignores would leave the library default (several GiB) in force without an error.
        let (root, c) = workdir("ignored");
        let toml = root.join("arti.toml");
        std::fs::write(&toml, format!("[storage]\nstate_dir = {:?}\ncache_dir = {:?}\n", c.state_dir, c.cache_dir)).unwrap();
        std::fs::set_permissions(&toml, std::fs::Permissions::from_mode(0o600)).unwrap();
        let tcc = load_arti(&toml).unwrap();
        assert_eq!(check_quota(&tcc, 64 * MIB), Err(ConfigError::MemoryMax));
        std::fs::remove_dir_all(&root).unwrap();
    }

    #[test]
    fn a_file_that_cannot_be_loaded_is_an_error() {
        let (root, _) = workdir("missing");
        assert!(matches!(load_arti(&root.join("nope.toml")), Err(ConfigError::Toml(_))));
        let bad = root.join("bad.toml");
        std::fs::write(&bad, "this is = = not toml").unwrap();
        std::fs::set_permissions(&bad, std::fs::Permissions::from_mode(0o600)).unwrap();
        assert!(matches!(load_arti(&bad), Err(ConfigError::Toml(_))));
        std::fs::remove_dir_all(&root).unwrap();
    }

    #[test]
    fn paths_that_arti_would_expand_or_that_toml_cannot_hold_are_refused() {
        let (root, mut c) = workdir("paths");
        for bad in ["relative/state", "/x/${HOME}/state", "~/state", "/x/line\nbreak"] {
            c.state_dir = Path::new(bad).to_path_buf();
            assert!(matches!(tunnel_config(&c, &root.join("arti.toml")), Err(ConfigError::Toml(_))), "{bad:?}");
        }
        std::fs::remove_dir_all(&root).unwrap();
    }

    #[test]
    fn quotes_and_backslashes_in_a_path_survive() {
        let (root, mut c) = workdir("escape");
        let odd = root.join("sta\"te\\x");
        std::fs::DirBuilder::new().mode(0o700).create(&odd).unwrap();
        c.state_dir = odd.clone();
        let toml = root.join("arti.toml");
        tunnel_config(&c, &toml).unwrap();
        let all = format!("{:?}", load_arti(&toml).unwrap());
        assert!(all.contains(&format!("{odd:?}")), "state dir with quote and backslash not applied: {all}");
        std::fs::remove_dir_all(&root).unwrap();
    }
}
