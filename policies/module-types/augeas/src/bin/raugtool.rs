// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

//! Provides a REPL for the languages used by the module.
//!
//! It is designed to help users interactively develop and test their configurations.
//! It is a companion to the `rudder-module-augeas` module type.

use anyhow::Result;
use gumdrop::Options;
use raugeas::Flags;
use rudder_module_augeas::augeas::{Augeas, LoadMode};
use rudder_module_augeas::dsl::repl;
use rudder_module_augeas::dsl::script::Interpreter;
use rudder_module_augeas::{CRATE_NAME, CRATE_VERSION};
use rudder_module_type::cfengine::log::{set_max_level, LevelFilter};
use rudder_module_type::rudder_debug;
use std::env;
use std::path::PathBuf;
/*
TODO: implement

 -b, --backup           preserve originals of modified files with
                        extension '.augsave'
 -n, --new              save changes in files with extension '.augnew',
                        leave original unchanged
 -i, --interactive      run an interactive shell after evaluating
                        the commands in STDIN and FILE
 --timing               after executing each command, show how long it took
*/

#[derive(Debug, Options)]
pub struct Cli {
    #[options(help = "print help message")]
    help: bool,
    #[options(help = "be verbose")]
    verbose: bool,
    #[options(no_short, help = "print version information and exit")]
    version: bool,
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
    #[options(help = "use root as the root of the filesystem")]
    root: Option<PathBuf>,

    #[options(
        meta = "PATH",
        short = "f",
        long = "file",
        help = "read commands from file"
    )]
    file: Option<PathBuf>,

    #[options(help = "echo commands when reading from a file")]
    echo: bool,

    #[options(
        short = "l",
        long = "load-file",
        meta = "PATH",
        help = "load individual file in the tree"
    )]
    load_file: Option<PathBuf>,

    #[options(
        short = "s",
        long = "autosave",
        help = "automatically save at the end of instructions"
    )]
    auto_save: bool,

    /// Additional load paths for lenses.
    ///
    /// `/var/rudder/lib/lenses` is always added.
    #[options(
        short = "i",
        long = "include",
        meta = "PATH",
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

    #[options(no_short, help = "load span positions for nodes related to a file")]
    span: bool,

    /// Do not load any files into the tree on startup.
    #[options(
        short = "L",
        long = "noload",
        help = "do not load any files into the tree on startup"
    )]
    dont_load_tree: bool,

    /// Do not load any files into the tree on startup.
    #[options(
        short = "S",
        long = "nostdinc",
        help = "do not search the builtin default directories for modules"
    )]
    no_std_includes: bool,

    #[options(
        meta = "XFM",
        help = "add a file transform; uses the 'transform' command syntax, e.g. -t 'Fstab incl /etc/fstab.bak'"
    )]
    transform: Option<String>,

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

        let mut flags = Flags::NONE;
        match load_mode {
            LoadMode::All => {
                rudder_debug!("Loading all files into the tree on startup");
            }
            LoadMode::LensesOnly => {
                rudder_debug!("Loading lenses on startup");
                flags.insert(Flags::NO_LOAD);
            }
            LoadMode::Nothing => {
                rudder_debug!("Not loading lenses on startup");
                flags.insert(Flags::NO_MODULE_AUTOLOAD);
            }
        }

        if opts.span {
            rudder_debug!("Enabling span tracking");
            flags.insert(Flags::ENABLE_SPAN);
        }

        if opts.type_check_lenses {
            rudder_debug!("Type checking lenses");
            flags.insert(Flags::TYPE_CHECK);
        }

        let mut aug = Augeas::new_aug(opts.root.as_deref(), &opts.lens_paths, flags)?;

        let version = format!(
            "{} {} (augeas: {})",
            CRATE_NAME,
            CRATE_VERSION,
            aug.version()?
        );

        if opts.version {
            // FIXME load minimal aug
            println!("{}", version);
            return Ok(());
        }

        // FIXME handle other parameters

        println!("Rudder Augeas. Type 'quit' to leave.");
        println!("{}", version);
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
