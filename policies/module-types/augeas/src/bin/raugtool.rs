// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

//! Provides a REPL for the languages used by the module.
//!
//! It is designed to help users interactively develop and test their configurations.
//! It is a companion to the `rudder-module-augeas` module type.

use anyhow::Result;
use gumdrop::Options;
use rudder_module_augeas::augeas::Augeas;
use rudder_module_augeas::dsl::repl;
use rudder_module_type::cfengine::log::{set_max_level, LevelFilter};
use std::env;

#[derive(Debug, Options)]
pub struct Cli {
    #[options(help = "print help message")]
    help: bool,
    #[options(help = "be verbose")]
    verbose: bool,
    /// Prefix to add.
    ///
    /// By default,
    ///
    /// * If a `path` is passed, it is used as context
    /// * If no `path` is passed, the context is empty.
    context: Option<String>,
    /// Output file path
    // used as incl
    path: Option<String>,
    /// Augeas root
    ///
    /// The root of the filesystem to use for augeas.
    ///
    /// WARNING: Should not be used in most cases.
    root: Option<String>,
    /// Additional load paths for lenses.
    ///
    /// `/var/rudder/lib/lenses` is always added.
    lens_paths: Vec<String>,
    /// A lens to use.
    ///
    /// If not passed, all lenses are loaded, and the `path` is used
    /// to detect the lens to use.
    /// Passing a lens makes the call faster as it avoids having to
    /// load all lenses.
    lens: Option<String>,
    /// Force augeas to type-check the lenses.
    ///
    /// This is slow and should only be used for debugging.
    type_check_lenses: bool,
}

impl Cli {
    pub fn run() -> Result<()> {
        let opts = Self::parse_args_default_or_exit();
        if opts.verbose {
            println!("Parsed options: {:#?}", &opts);
        }

        let mut aug = Augeas::new_aug(
            opts.root.as_deref(),
            opts.context.as_deref(),
            opts.path.as_deref(),
            "",
            None,
            false,
        )?;
        repl::start(&mut aug)
    }
}

fn main() -> Result<(), anyhow::Error> {
    env::set_var("LC_ALL", "C");
    // FIXME: allow changing the level display
    set_max_level(LevelFilter::Trace);
    Cli::run()
}
