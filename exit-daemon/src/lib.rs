// SPDX-License-Identifier: Apache-2.0
//! The exit daemon of Compartments (ADR-184). `unsafe` is denied for the whole crate; the two modules that need it
//! (`sockdiag::sys`, the raw `sock_diag` netlink socket and the peer credentials, and `routes::sys`, the raw `NETLINK_ROUTE`
//! socket of the route helper) opt in with `#[allow(unsafe_code)]` and are the only places of this crate's own code to
//! review for it (a test of `scaffolding.rs` also uses it, to make a socket pair); the vendored `tuntap.rs` has its own.
#![deny(unsafe_code)]

pub mod control;
#[cfg(test)]
mod fuzz;
pub mod guard;
pub mod ladder;
pub mod masklist;
pub mod ratelimit;
pub mod policy;
pub mod routes;
pub mod sockdiag;
pub mod scaffolding;
pub mod config;
pub mod server;
pub mod args;
