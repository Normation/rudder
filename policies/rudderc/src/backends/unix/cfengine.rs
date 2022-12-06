// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

//! Generate policies for Unix agents running CFEngine.
//!
//! # Goals
//!
//! This module does not cover full CFEngine syntax but only a subset of it, which is our
//! execution target, plus some Rudder-specific metadata in comments, required for reporting.
//! In particular there is no need to handle all promises and attributes, we only need to support the ones we are
//! able to generate.
//!
//! This subset should be safe, fast and readable (in that order).
//!
//! Everything that does not have an effect on applied state
//! should follow a deterministic rendering process (attribute order, etc.)
//! This allows an easy diff between produced files.
//!
//! # Generation by `cf-promises`
//!
//! CFEngine is able to generate `.cf` policies from a JSON with a command like:
//!
//! ```shell
//! cf-promises --eval-functions=false --policy-output-format cf --file ./policy.json
//! ```
//!
//! So it could partially replace this module. But it does not store macro information
//! which could prove really useful for producing fallback modes in generation.

pub(crate) mod bundle;
pub(crate) mod promise;

pub const MIN_INT: i64 = -99_999_999_999;
pub const MAX_INT: i64 = 99_999_999_999;

// FIXME only quote when necessary + only concat when necessary
// no need to call explicitly
pub fn quoted(s: &str) -> String {
    format!("\"{}\"", s)
}

pub fn expanded(s: &str) -> String {
    format!("\"${{{}}}\"", s)
}
