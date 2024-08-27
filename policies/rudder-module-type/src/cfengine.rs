// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021 Normation SAS

//! Implementation of CFEngine's custom promise protocol
//!
//! This library is a Rust implementation of the
//! [custom promise protocol](https://github.com/cfengine/core/blob/master/docs/custom_promise_types/modules.md),
//! added in CFEngine 3.17.
//!
//! It targets CFEngine 3.18.3 LTS or later (for proper `action_policy` support), and uses the JSON variant of the
//! protocol, and allows implementing promise types in Rust with a type-safe and idiomatic interface.
//!
//! ## Design
//!
//! Design is inspired by the [reference Python and shell implementations](https://github.com/cfengine/core/blob/master/docs/custom_promise_types).
//!
//! The main goal is to provide a reliable interface, by checking as much stuff as we can
//! (including parameter types, etc.) to allow implementing safe and fast promise types.
//! Note that we do not try to stick too close to the underlying protocol, and prefer
//! an idiomatic way when possible.
//!
//! This lib is done with Rudder use cases in mind, so we have a special focus on the audit mode (warn only).
//! In this order, we split the *evaluate* step into *check* and *apply*
//! to handle warn-only mode at executor level and avoid having to implement it in every promise.
//!
//! The library is built around a trait describing a promise type's interface, and an executor
//! that handles the stdin/stdout communication and protocol serialization.

pub use runner::CfengineRunner;
use std::env;

/// Passed in Rudder agent to all modules
/// to indicate that the module is running in CFEngine mode.
///
/// Note: This is a Rudder-specific behavior, and it is not passed by vanilla CFEngine.
const CFENGINE_MODE_ARG: &str = "--cfengine";

pub mod header;
pub mod protocol;
pub mod runner;
#[macro_use]
pub mod log;

/// Is the current program run from a CFEngine agent?
pub fn called_from_agent() -> bool {
    env::args().any(|x| x == CFENGINE_MODE_ARG)
}
