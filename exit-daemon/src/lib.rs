// SPDX-License-Identifier: Apache-2.0
//! The exit daemon of Compartments (ADR-184). `unsafe` is denied for the whole crate; the one module that needs it
//! (`sockdiag::sys`, the raw netlink socket and the peer credentials) opts in with `#[allow(unsafe_code)]` and is the only
//! place of this crate's own code to review for it; the vendored `tuntap.rs` has its own.
#![deny(unsafe_code)]

pub mod control;
pub mod masklist;
pub mod policy;
pub mod routes;
pub mod sockdiag;
pub mod scaffolding;
pub mod config;
pub mod server;
pub mod args;
