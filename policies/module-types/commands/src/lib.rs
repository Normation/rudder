mod cli;
use crate::cli::Cli;
use std::{
    fs,
    io::Write,
    path::PathBuf,
    process::{Command, Stdio},
};

use anyhow::{Ok, Result};
use rudder_module_type::{
    CheckApplyResult, ModuleType0, ModuleTypeMetadata, Outcome, PolicyMode, ProtocolResult,
    ValidateResult, cfengine::called_from_agent, parameters::Parameters, run_module,
};
use serde::{Deserialize, Serialize};
use serde_json::Value;

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

pub fn default_shell_path() -> String {
    "/bin/sh".to_string()
}

pub fn default_timeout() -> String {
    "30".to_string()
}

fn default_as_true() -> bool {
    true
}

pub fn default_repaired_codes() -> String {
    "0".to_string()
}

struct Commands {}

impl Commands {
    fn run(p: &CommandsParameters) -> Result<()> {
        if p.run_in_audit_mode {
            // dry-run
            println!(
                "dry-run: {} {}",
                p.command,
                p.args.clone().unwrap_or("".to_string())
            );
        } else {
            let mut command = Command::new(if p.in_shell {
                &p.shell_path
            } else {
                &p.command
            });

            if p.in_shell {
                command.arg("-c");
                command.arg(&p.command);
            }

            if let Some(args) = &p.args
                && !args.is_empty()
            {
                command.args(args.split_whitespace());
            }

            if let Some(chdir) = &p.chdir
                && !chdir.is_empty()
                && fs::exists(chdir)?
            {
                command.current_dir(chdir);
            }

            // TODO: Timeout

            command.stdout(Stdio::piped());
            command.stderr(Stdio::piped());
            command.stdin(Stdio::piped());
            let mut child = command.spawn()?;
            if let Some(stdin) = &p.stdin {
                let mut child_stdin = child.stdin.take().expect("failed to get child stdin");
                // TODO: test && refactor
                write!(child_stdin, "{stdin}")?;
                if p.stdin_add_newline {
                    writeln!(child_stdin)?;
                }
            }
            let output = child.wait_with_output().expect("failed to wait on child");
            dbg!(output);
        }
        Ok(())
    }
}

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
        let _parameters: CommandsParameters =
            serde_json::from_value(Value::Object(parameters.data.clone()))?;

        Ok(())
    }

    fn check_apply(&mut self, _mode: PolicyMode, parameters: &Parameters) -> CheckApplyResult {
        assert!(self.validate(parameters).is_ok());
        let _p: CommandsParameters =
            serde_json::from_value(Value::Object(parameters.data.clone()))?;
        Ok(Outcome::success())
    }
}

pub fn entry() -> Result<(), anyhow::Error> {
    let promise_type = Commands {};

    if called_from_agent() {
        run_module(promise_type)
    } else {
        Cli::run()
    }
}
