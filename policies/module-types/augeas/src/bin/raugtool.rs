// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

//! Provides a REPL for the languages used by the module.
//!
//! It is designed to help users interactively develop and test their configurations.
//! It is a companion to the `rudder-module-augeas` module type.

use anyhow::Result;
use gumdrop::Options;
use raugeas::Flags;
use rudder_module_augeas::{
    CRATE_NAME, CRATE_VERSION,
    augeas::Augeas,
    dsl::{interpreter::Interpreter, repl},
};
use rudder_module_type::cfengine::log::{LevelFilter, set_max_level};
use std::{env, fs, path::PathBuf};

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

    #[options(help = "preserve originals of modified files with extension '.augsave'")]
    backup: bool,

    #[options(help = "save changes in files with extension '.augnew', leave original unchanged")]
    new: bool,

    // Enable span as it is used for our error messages.
    #[options(no_short, help = "always enabled, this option does nothing")]
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

    #[options(help = "run an interactive shell after evaluating the commands in STDIN and FILE")]
    interactive: bool,

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

/// Version string for stdout
///
/// ```
/// rudder-module-augeas 8.3.0 (augeas: 1.14.1)
/// ```
fn version(augeas_version: String) -> String {
    format!(
        "{CRATE_NAME} {CRATE_VERSION} (augeas: {augeas_version})"
    )
}

impl Cli {
    pub fn run() -> Result<()> {
        let opts = Self::parse_args_default_or_exit();
        if opts.verbose {
            set_max_level(LevelFilter::Debug);
            println!("Parsed options: {:#?}", &opts);
        }
        if opts.version {
            // load minimal augeas
            let flags = Flags::NO_MODULE_AUTOLOAD;
            let aug = Augeas::new_aug::<&str>(None, &[], flags)?;
            let version = version(aug.version()?);
            println!("{version}");
            return Ok(());
        }

        let mut flags = Flags::NONE;
        if opts.dont_load_lenses {
            flags.insert(Flags::NO_MODULE_AUTOLOAD);
        }
        if opts.dont_load_tree {
            flags.insert(Flags::NO_LOAD);
        }
        flags.insert(Flags::ENABLE_SPAN);
        if opts.type_check_lenses {
            flags.insert(Flags::TYPE_CHECK);
        }
        if opts.no_std_includes {
            flags.insert(Flags::NO_STD_INCLUDE);
        }
        if opts.new {
            flags.insert(Flags::SAVE_NEW_FILE);
        }
        if opts.backup {
            flags.insert(Flags::SAVE_BACKUP);
        }

        let mut aug = Augeas::new_aug(opts.root.as_deref(), &opts.lens_paths, flags)?;

        // FIXME read from stdin

        // Apply transforms
        if let Some(t) = opts.transform {
            aug.srun("transform ".to_owned() + t.as_str())?;
        }

        if let Some(f) = opts.file {
            let script = fs::read_to_string(f)?;
            // FIXME handle echo
            aug.srun(script)?;

            if !opts.interactive {
                return Ok(());
            }
        }

        let current_content = opts.load_file.map(fs::read_to_string).transpose()?;

        println!("Rudder Augeas. Type 'quit' to leave.");
        let mut interpreter = Interpreter::new(&mut aug, current_content.as_deref());
        repl::start(&mut interpreter)
    }
}

fn main() -> Result<(), anyhow::Error> {
    // SAFETY: The module is single-threaded.
    unsafe {
        env::set_var("LC_ALL", "C");
    }
    Cli::run()
}
