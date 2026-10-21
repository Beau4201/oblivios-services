// SPDX-License-Identifier: Apache-2.0
//! The route helper (`exitd --routes`, ADR-186): the only part of the exit service that writes rules and routes, and it
//! can write nothing but the closed language of [`spec`]. It has no internet socket and no Tor; it takes typed messages
//! from the exit app over a control socket that `init` created.

pub mod helper;
pub mod netlink;
pub mod proto;
pub mod reconcile;
pub mod spec;
pub mod sys;

