// SPDX-License-Identifier: Apache-2.0
//! The exit daemon of Compartments (ADR-184). `unsafe` is denied for the whole crate; the one module that needs it
//! (`sockdiag::sys`, the raw netlink socket) opts in with `#[allow(unsafe_code)]` and is the only place to review for
//! it.
#![deny(unsafe_code)]

pub mod control;
pub mod sockdiag;
