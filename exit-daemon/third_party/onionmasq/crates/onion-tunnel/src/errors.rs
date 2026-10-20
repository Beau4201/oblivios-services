//! Error handling.

use arti_client::config::{BridgeParseError, CfgPathError, ConfigBuildError};
pub use arti_client::Error as ArtiError;
pub use arti_client::ErrorKind as ArtiErrorKind;
pub use arti_client::HasKind;
use std::io;
use std::path::PathBuf;
use thiserror::Error;
use tor_config::load::ConfigResolveError;
use tor_config::ConfigError;

#[derive(Error, Debug)]
#[non_exhaustive]
pub enum TunnelError {
    #[error("Failed to set up Arti client: {0}")]
    ArtiSetup(#[source] ArtiError),
    #[error("TUN interface setup failed: {0}")]
    TunSetup(#[source] io::Error),
    #[cfg(feature = "pcap")]
    #[error("Failed to open pcap dump file: {0}")]
    PcapSetup(#[source] io::Error),
    #[error("Failed to set up pluggable transports: {0}")]
    PtSetup(#[source] io::Error),
    #[error("Failed to set up pluggable transports: {0}")]
    PtSetupGo(String),
    #[error("create_dir_all({dir}) failed: {err}")]
    Directory { dir: PathBuf, err: io::Error },
    #[error("Failed to figure out arti's default state directory: {0}")]
    ArtiDefaultState(#[source] CfgPathError),
    #[error("TUN interface I/O error: {0}")]
    TunIo(#[source] io::Error),
    #[error("Bad bridge line: {0}")]
    BadBridgeLine(#[source] BridgeParseError),
    #[error("Failed to parse Arti configuration: {0}")]
    ArtiConfig(#[source] ConfigError),
    #[error("Failed to resolve Arti configuration: {0}")]
    ArtiConfigResolve(#[source] ConfigResolveError),
    #[error("Failed to build Arti configuration: {0}")]
    ArtiConfigBuild(#[source] ConfigBuildError),
    #[error("Failed to get isolation from scaffolding: {0}")]
    ScaffoldingIsolation(#[source] io::Error),
    #[error("Failed to connect to Tor network: {0}")]
    BootstrapFailure(#[source] arti_client::Error),
    #[error("Tor network is censored: {0}")]
    BootstrapBlocked(tor_chanmgr::ConnBlockage),
    #[error("Arti bootstrap failure: {0}")]
    ArtiBootstrapFailure(String),
}

pub type TunnelResult<T> = Result<T, TunnelError>;
