// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021 Normation SAS

//! Agent-side implementation of base module_type types

pub use rudder_commons::PolicyMode;
use std::process::exit;

use anyhow::{Error, Result};
use gumdrop::Options;
use serde::{Deserialize, Serialize};

use crate::parameters::Parameters;

pub mod cfengine;
pub mod os_release;
pub mod parameters;
pub mod runner;

pub use rudder_cli as cli;

/// Information about the module type to pass to the library.
///
/// These fields are the fields required by the library and need to be
/// implemented by all promise types.
#[derive(Debug, PartialEq, Eq, Serialize, Default, Clone)]
pub struct ModuleTypeMetadata {
    name: &'static str,
    features: Vec<&'static str>,
    agent_version: &'static str,
}

impl ModuleTypeMetadata {
    pub fn new(name: &'static str, features: Vec<&'static str>) -> Self {
        Self {
            name,
            features,
            agent_version: env!("CARGO_PKG_VERSION"),
        }
    }
}

/// Rudder module type
///
/// This is the interface to implement a generic Rudder module type.
///
/// This library provides adapters to connect it to our agents.
///
/// Protocol versioning will be handled by using different traits.
///
/// ## Metadata
///
/// Each promise type source must come with a metadata file in `YAML` format.
/// It contains the specifications of the module type inputs and outputs,
/// along with documentation.
///
/// The metadata will be included at compile time to allow distributing a unique file.
///
/// ## Run model
///
/// The module type for will be started and initialized only once for each agent run.
/// Following requests will be handled sequentially.
///
/// Implementation *must* allow concurrent run of the module type (or prevent it totally).
///
/// ## Documentation
///
/// The module is able to generate documentation from the given metadata.
pub trait ModuleType0 {
    /// Load metadata from default `rudder_module_type.yml` and `README.md` files
    fn metadata(&self) -> ModuleTypeMetadata;

    /// Executed before any promise
    ///
    /// Can be used for set-up tasks (connecting to a server, spawning a daemon, etc.)
    fn init(&mut self) -> ProtocolResult {
        ProtocolResult::Success
    }

    /// Checks parameter validity
    ///
    /// Should be used for advanced parameter's validation, additionally to
    /// validation of parameters types.
    fn validate(&self, _parameters: &Parameters) -> ValidateResult {
        Ok(())
    }

    /// Test if the policy is applied and make changes if needed
    ///
    /// Assumes validation has already been done.
    ///
    /// Does not need to be implemented for promises that should be evaluated every time
    /// (usually actions).
    ///
    /// ## Design
    ///
    /// We use a single `checkApply` method instead of separate check and apply to allow simpler
    /// implementing, especially as the two are often very similar.
    ///
    /// ## Parameters
    ///
    /// We pass a generic `serde_json::Value`. This allows the module type to chose how to treat it,
    /// either parse it completely into structs or leave some generic parts (arbitrary key value, etc.).
    fn check_apply(&mut self, mode: PolicyMode, parameters: &Parameters) -> CheckApplyResult;

    /// Run before normal executor termination,
    ///
    /// can be used for clean up tasks.
    fn terminate(&mut self) -> ProtocolResult {
        ProtocolResult::Success
    }
}

pub type ValidateResult = Result<()>;

/// We don't map detailed Rudder types here (`compliance_` vs. `result_`, _na, etc.) for two reasons:
///
/// * We want to abstract external concerns as much as possible and make the interface minimal.
///   It is up to the calling agent to map the outcome to the expected semantic.
/// * To match CFEngine's behavior
#[derive(Debug, PartialEq, Eq, Clone)]
pub enum Outcome {
    Success(Option<String>),
    Repaired(String),
}

impl Outcome {
    pub fn repaired<S: Into<String>>(message: S) -> Self {
        Self::Repaired(message.into())
    }

    pub fn success() -> Self {
        Self::Success(None)
    }

    pub fn success_with<S: Into<String>>(message: S) -> Self {
        Self::Success(Some(message.into()))
    }
}

/// Promise application result
pub type CheckApplyResult = Result<Outcome>;

/// Init/Terminate result
#[derive(Debug, PartialEq, Eq, Serialize, Deserialize, Clone)]
pub enum ProtocolResult {
    /// Success
    Success,
    /// Error
    ///
    /// Parameter will be logged at error level
    Failure(String),
    /// Unexpected error
    ///
    /// Parameter will be logged at error level
    Error(String),
}

/// Represents a connector able to run the given module_type implementation.
///
/// Version 0 is for CFEngine custom promise types.
pub trait Runner0 {
    fn run<T: ModuleType0>(&self, module_type: T) -> Result<(), Error>;
}

/// Automatically select the right runner for the target platform and run it with
/// default settings.
pub fn run_module<T: ModuleType0>(module_type: T) -> Result<(), Error> {
    let cli_cfg = CliConfiguration::parse_args_default_or_exit();

    if cli_cfg.version {
        println!("{} {}", env!("CARGO_PKG_NAME"), env!("CARGO_PKG_VERSION"));
        exit(0)
    } else if cli_cfg.info {
        let metadata = module_type.metadata();
        println!("{}", serde_json::to_string(&metadata)?);
        exit(0)
    }

    use crate::cfengine::CfengineRunner;
    CfengineRunner::new().run(module_type)?;
    Ok(())
}

#[derive(Debug, Options)]
// version and description are taken from Cargo.toml
pub struct CliConfiguration {
    #[options(help = "print JSON information about the module type")]
    pub info: bool,
    /// Automatically used by the help flag
    #[options(help = "print help message")]
    help: bool,
    #[options(help = "print version", short = "V")]
    pub version: bool,
    #[options(help = "verbose", short = "v")]
    pub verbose: bool,
    #[options(help = "noop option")]
    pub cfengine: bool,
}

/// Provide facts about the system
///
/// Some of these are also provided by the agent, but we need to be able to run in standalone mode.
pub mod inventory {
    use anyhow::{Result, bail};
    use rudder_commons::NODE_ID_PATH;
    use std::fs;
    use std::path::Path;

    /// Only works on a system managed by a Rudder agent
    ///
    /// Should not be used for development or testing purposes.
    pub fn system_node_id() -> Result<String> {
        Ok(if Path::new(NODE_ID_PATH).exists() {
            fs::read_to_string(NODE_ID_PATH)?
        } else {
            bail!("Could not find node id file {}", NODE_ID_PATH)
        })
    }
}

#[cfg(target_family = "unix")]
pub fn ensure_root_user() -> Result<()> {
    use anyhow::bail;
    use std::os::unix::fs::MetadataExt;

    let uid = std::fs::metadata("/proc/self")
        .map(|m| m.uid())
        .unwrap_or(0);
    if uid != 0 {
        bail!("This program needs to run as root, aborting.");
    }
    Ok(())
}

#[cfg(feature = "backup")]
pub mod backup {
    //! Helper to produce Rudder-compatible backup files
    //!
    //! The output filename format is taken from our Unix agent.
    //!
    //! Dates are all localtime.

    use chrono::Locale;
    use chrono::prelude::*;
    use rudder_commons::canonify;
    use std::{
        fmt,
        path::{Path, PathBuf},
    };

    #[derive(Debug, PartialEq, Eq, Clone, Copy)]
    pub enum Backup {
        BeforeEdit,
        Moved,
    }

    impl fmt::Display for Backup {
        fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
            write!(
                f,
                "{}",
                match self {
                    // TODO remove _cf_ prefix?
                    Self::BeforeEdit => "cf_before_edit",
                    Self::Moved => "cf_moved",
                }
            )
        }
    }

    impl Backup {
        pub fn backup_file(self, source: &Path) -> PathBuf {
            let now: DateTime<Utc> = Utc::now();
            self.backup_file_timestamp(source, now.timestamp())
        }

        pub fn backup_file_timestamp(self, source: &Path, timestamp: i64) -> PathBuf {
            let now: DateTime<Utc> = Utc.timestamp_opt(timestamp, 0).unwrap();
            let file = format!(
                "{}_{}_{}_{}",
                source.to_string_lossy(),
                now.timestamp(),
                //now.to_rfc3339(),
                // ctime as used by CFEngine, but it is locale-dependant
                now.format_localized("%c", Locale::POSIX),
                self
            );
            PathBuf::from(canonify(&file))
        }
    }

    #[cfg(test)]
    mod tests {
        use pretty_assertions::assert_eq;

        use super::*;

        #[test]
        fn it_generates_backup_file_names() {
            let backup = Backup::BeforeEdit
                .backup_file_timestamp(Path::new("/opt/rudder/etc/relayd/main.conf"), 1653943305);
            // CFEngine format
            assert_eq!(
                backup.to_string_lossy(),
                "_opt_rudder_etc_relayd_main_conf_1653943305_Mon_May_30_20_41_45_2022_cf_before_edit"
            );
            // RFC3339 format
            //assert_eq!(backup.to_string_lossy(), "_opt_rudder_etc_relayd_main_conf_1653943305_2022_05_30T20_41_45_00_00_cf_before_edit");
        }
    }
}

#[cfg(feature = "diff")]
pub mod diff {
    use similar::{Algorithm, udiff::unified_diff};

    /// Compute the unified diff string between two strings.
    ///
    /// Uses three lines of context.
    pub fn diff(a: &str, b: &str) -> String {
        let diff_1 = unified_diff(Algorithm::Myers, a, b, 1, None);

        // Give more context if the diff is short enough.
        if diff_1.len() > 100 {
            diff_1
        } else {
            unified_diff(Algorithm::Myers, a, b, 3, None)
        }
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        #[test]
        fn test_diff() {
            let a = "foo\nbar\nbaz\n";
            let b = "foo\nbaz\nbar\n";
            let diff = diff(a, b);
            assert_eq!(diff, "@@ -1,3 +1,3 @@\n foo\n+baz\n bar\n-baz\n");
        }
    }
}

/// We could use <https://crates.io/crates/atomic-write-file> for more advanced use-cases
/// (larger files, std-like interface, etc.), but this is enough for our current needs.
pub mod atomic_file_write {
    use std::fs;
    use std::fs::OpenOptions;
    use std::io::Write;
    use std::path::Path;
    use std::path::PathBuf;

    pub fn atomic_write(dst: &Path, content: &[u8]) -> Result<(), std::io::Error> {
        // Write to a temporary file first
        let mut tmp_path = PathBuf::from(dst);
        tmp_path.set_extension("rudder_tmp");
        {
            let mut tmp_file = fs::File::create(&tmp_path)?;
            tmp_file.write_all(content)?;
            tmp_file.sync_all()?;
        }

        // Rename the temporary file to the destination
        fs::rename(&tmp_path, dst)?;

        // Ensure the directory entry is flushed to disk
        if let Some(parent) = dst.parent() {
            let dir = OpenOptions::new().read(true).open(parent)?;
            dir.sync_all()?;
        }

        Ok(())
    }

    #[cfg(test)]
    mod tests {
        use super::*;
        use std::fs;

        #[test]
        fn test_diff() {
            let temp_dir = tempfile::tempdir().unwrap();
            let file_path = temp_dir.path().join("test_file.txt");
            let content = b"Hello, world!";
            atomic_write(&file_path, content).unwrap();
            let read_content = fs::read(&file_path).unwrap();
            assert_eq!(read_content, content);
        }
    }
}

pub mod utf16_file {
    use anyhow::Result;
    use std::{
        fs::{File, write},
        io::{Read, Seek, SeekFrom, Write},
        path::Path,
    };

    pub fn read_utf16_file(path: &Path) -> Result<String> {
        let mut file = File::open(path)?;
        let mut buffer = Vec::new();

        file.seek(SeekFrom::Start(2))?;
        file.read_to_end(&mut buffer)?;
        let data = String::from_utf16(
            &buffer
                .chunks(2)
                .map(|chunk| u16::from_ne_bytes([chunk[0], chunk.get(1).copied().unwrap_or(0)]))
                .collect::<Vec<u16>>(),
        )?;

        Ok(data)
    }

    pub fn write_utf16_file(path: &Path, buffer: &str) -> Result<()> {
        let mut utf8_buf = Vec::new();
        for b in buffer.encode_utf16() {
            utf8_buf.write_all(&b.to_le_bytes())?;
        }
        write(path, utf8_buf)?;

        Ok(())
    }
}
