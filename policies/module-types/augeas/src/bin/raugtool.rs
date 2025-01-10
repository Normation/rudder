// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

//! Provides a REPL for the languages used by the module.
//!
//! It is designed to help users interactively develop and test their configurations.
//! It is a companion to the `rudder-module-augeas` module type.

use anyhow::Result;
use gumdrop::Options;
use rudder_module_augeas::augeas::{Augeas, LoadMode};
use rudder_module_augeas::dsl::repl;
use rudder_module_augeas::dsl::script::Interpreter;
use rudder_module_augeas::{CRATE_NAME, CRATE_VERSION};
use rudder_module_type::cfengine::log::{set_max_level, LevelFilter};
use std::env;
use std::path::PathBuf;

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
    path: Option<PathBuf>,
    /// Augeas root
    ///
    /// The root of the filesystem to use for augeas.
    ///
    /// WARNING: Should not be used in most cases.
    root: Option<PathBuf>,
    /// Additional load paths for lenses.
    ///
    /// `/var/rudder/lib/lenses` is always added.
    #[options(
        short = "i",
        long = "include",
        help = "additional load paths for lenses"
    )]
    lens_paths: Vec<PathBuf>,
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
    #[options(
        short = "c",
        long = "typecheck",
        help = "force type checking of lenses"
    )]
    type_check_lenses: bool,

    #[options(
        long = "span",
        help = "load span positions for nodes related to a file"
    )]
    span: bool,

    /// Do not load any files into the tree on startup.
    #[options(
        short = "L",
        long = "noload",
        help = "do not load any files into the tree on startup"
    )]
    dont_load_tree: bool,
    /// Do not autoload modules from the search path.
    #[options(
        short = "A",
        long = "noautoload",
        help = "do not autoload modules from the search path"
    )]
    dont_load_lenses: bool,
}

impl Cli {
    pub fn run() -> Result<()> {
        let opts = Self::parse_args_default_or_exit();
        if opts.verbose {
            println!("Parsed options: {:#?}", &opts);
        }

        let load_mode = match (!opts.dont_load_lenses, !opts.dont_load_tree) {
            (false, _) => LoadMode::Nothing,
            (true, false) => LoadMode::LensesOnly,
            (true, true) => LoadMode::All,
        };

        let mut aug = Augeas::new_aug(
            opts.root.as_deref(),
            &opts.lens_paths,
            opts.type_check_lenses,
            opts.span,
            load_mode,
        )?;

        // FIXME handle other parameters

        println!("Rudder Augeas. Type 'quit' to leave.");
        println!(
            "{} {} (augeas: {})",
            CRATE_NAME,
            CRATE_VERSION,
            aug.version()?
        );
        let mut interpreter = Interpreter::new(&mut aug);
        repl::start(&mut interpreter)
    }
}

fn main() -> Result<(), anyhow::Error> {
    env::set_var("LC_ALL", "C");
    // FIXME: allow changing the level display
    set_max_level(LevelFilter::Trace);
    Cli::run()
}
