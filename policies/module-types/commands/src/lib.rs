use std::path::PathBuf;

use anyhow::{Result, bail};
use rudder_module_type::{
    CheckApplyResult, ModuleType0, ModuleTypeMetadata, PolicyMode, ProtocolResult, ValidateResult,
    cfengine::called_from_agent, parameters::Parameters, run_module,
};
use serde::{Deserialize, Serialize};

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub struct CommandsParameters {
    /// Command to be executed
    command: String,
    /// Arguments to the command
    #[serde(skip_serializing_if = "Option::is_none")]
    args: Option<String>,
    /// Controls the running mode of the command
    #[serde(default)] // Default to false
    run_in_audit_mode: bool,
    /// Controls if the command is executed inside a shell
    #[serde(default)] // Default to false
    in_shell: bool,
    /// Shell path (used only in shell mode)
    #[serde(default = "default_shell_path")]
    shell_path: String,
    /// Directory from where to execute the command
    #[serde(skip_serializing_if = "Option::is_none")]
    chdir: Option<String>,
    /// Timeout for command execution
    #[serde(default = "default_timeout")]
    timeout: String, // Default to 30 seconds
    /// Input passed to the stdin of the executed command
    #[serde(skip_serializing_if = "Option::is_none")]
    stdin: Option<String>,
    /// Controls the appending of a newline to the stdin input
    #[serde(default = "default_as_true")]
    stdin_add_newline: bool,
    /// Compliant codes
    #[serde(default)] // Default to ""
    compliant_codes: String,
    /// Repaired codes
    #[serde(default = "default_repaired_codes")]
    repaired_codes: String, // Default to "0"
    /// File to store the output of the command
    output_to_file: Option<PathBuf>,
    // Controls the strip of the content inside the output file
    #[serde(default)] // Default to false
    strip_output: bool,
    /// UID used by the executed command
    #[serde(skip_serializing_if = "Option::is_none")]
    uid: Option<String>,
    /// GID used by the executed command
    #[serde(skip_serializing_if = "Option::is_none")]
    gid: Option<String>,
    /// Umask used by the executed command
    #[serde(skip_serializing_if = "Option::is_none")]
    umask: Option<String>,
    /// Environment variables used by the executed command
    #[serde(skip_serializing_if = "Option::is_none")]
    env_vars: Option<String>,
    /// Controls output of diffs in the report
    #[serde(default = "default_as_true")]
    show_content: bool,
}

fn default_shell_path() -> String {
    "/bin/sh".to_string()
}

fn default_timeout() -> String {
    "30".to_string()
}

fn default_as_true() -> bool {
    true
}

fn default_repaired_codes() -> String {
    "0".to_string()
}

struct Commands {}

impl ModuleType0 for Commands {
    fn metadata(&self) -> ModuleTypeMetadata {
        let meta = include_str!("../rudder_module_type.yml");
        let docs = include_str!("../README.md");
        ModuleTypeMetadata::from_metadata(meta)
            .expect("invalid metadata")
            .documentation(docs)
    }

    fn init(&mut self) -> rudder_module_type::ProtocolResult {
        ProtocolResult::Success
    }

    fn validate(&self, parameters: &Parameters) -> ValidateResult {
        Ok(())
    }

    fn check_apply(&mut self, mode: PolicyMode, parameters: &Parameters) -> CheckApplyResult {
        bail!("apply")
    }
}

pub fn entry() -> Result<(), anyhow::Error> {
    let promise_type = Commands {};

    if called_from_agent() {
        run_module(promise_type)
    } else {
        bail!("There is currently no CLI")
    }
}
