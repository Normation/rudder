mod cli;
use crate::cli::Cli;
use std::{
    fs::{self, File},
    io::Write,
    os::unix::{fs::PermissionsExt, process::CommandExt},
    path::PathBuf,
    process::{Command, Stdio},
    str::from_utf8,
    time::{Duration, Instant},
};

use anyhow::{Context, Result, bail};
use rudder_module_type::{
    CheckApplyResult, ModuleType0, ModuleTypeMetadata, Outcome, PolicyMode, ProtocolResult,
    ValidateResult, cfengine::called_from_agent, parameters::Parameters, run_module,
};
use rustix::{fs::Mode, process::umask};
use serde::{Deserialize, Deserializer, Serialize};
use serde_json::{Value, json};
use wait_timeout::ChildExt;

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub struct CommandsParameters {
    /// Command to be executed
    command: String,

    /// Arguments to the command
    #[serde(
        skip_serializing_if = "Option::is_none",
        deserialize_with = "deserialize_option_string"
    )]
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
    #[serde(
        skip_serializing_if = "Option::is_none",
        deserialize_with = "deserialize_option_string"
    )]
    chdir: Option<String>,

    /// Timeout for command execution
    #[serde(default = "default_timeout")]
    timeout: String, // Default to 30 seconds

    /// Input passed to the stdin of the executed command
    #[serde(
        skip_serializing_if = "Option::is_none",
        deserialize_with = "deserialize_option_string"
    )]
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
    #[serde(
        skip_serializing_if = "Option::is_none",
        deserialize_with = "deserialize_option_pathbuf"
    )]
    output_to_file: Option<PathBuf>,

    // Controls the strip of the content inside the output file
    #[serde(default)] // Default to false
    strip_output: bool,

    /// UID used by the executed command
    #[serde(
        skip_serializing_if = "Option::is_none",
        deserialize_with = "deserialize_option_string"
    )]
    uid: Option<String>,

    /// GID used by the executed command
    #[serde(
        skip_serializing_if = "Option::is_none",
        deserialize_with = "deserialize_option_string"
    )]
    gid: Option<String>,

    /// Umask used by the executed command
    #[serde(
        skip_serializing_if = "Option::is_none",
        deserialize_with = "deserialize_option_string"
    )]
    umask: Option<String>,

    /// Environment variables used by the executed command
    #[serde(
        skip_serializing_if = "Option::is_none",
        deserialize_with = "deserialize_option_string"
    )]
    env_vars: Option<String>,

    /// Controls output of diffs in the report
    #[serde(default = "default_as_true")]
    show_content: bool,
}

fn deserialize_option_pathbuf<'de, D>(deserializer: D) -> Result<Option<PathBuf>, D::Error>
where
    D: Deserializer<'de>,
{
    let value: Option<PathBuf> = Option::deserialize(deserializer)?;
    Ok(value.and_then(|p| {
        if p.as_path().to_str().is_none_or(|s| s.is_empty()) {
            None
        } else {
            Some(p)
        }
    }))
}

fn deserialize_option_string<'de, D>(deserializer: D) -> Result<Option<String>, D::Error>
where
    D: Deserializer<'de>,
{
    let value: Option<String> = Option::deserialize(deserializer)?;
    Ok(value.and_then(|s| if s.is_empty() { None } else { Some(s) }))
}

pub fn default_shell_path() -> String {
    "/bin/sh".to_string()
}

pub fn default_timeout() -> String {
    "30".to_string()
}

pub fn default_as_true() -> bool {
    true
}

pub fn default_repaired_codes() -> String {
    "0".to_string()
}

struct Commands {}

impl Commands {
    fn run(p: &CommandsParameters, _audit: bool) -> Result<Value> {
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

        if let Some(uid) = &p.uid {
            let uid = uid
                .parse::<u32>()
                .with_context(|| format!("'{uid}' is not a valid uid"))?;

            command.uid(uid);
        }

        if let Some(gid) = &p.gid {
            let gid = gid
                .parse::<u32>()
                .with_context(|| format!("'{gid}' is not a valid gid"))?;

            command.gid(gid);
        }

        if let Some(mask_str) = &p.umask {
            let mask = u32::from_str_radix(mask_str, 8)
                .with_context(|| format!("Invalid umask '{mask_str}'"))?;
            umask(Mode::from(mask));
        }

        command.stdout(Stdio::piped());
        command.stderr(Stdio::piped());
        command.stdin(Stdio::piped());

        let start_time = Instant::now();

        let mut child = command.spawn()?;
        if let Some(stdin) = &p.stdin {
            let mut child_stdin = child.stdin.take().expect("Failed to get child stdin");
            if p.stdin_add_newline {
                writeln!(child_stdin, "{stdin}")?;
            } else {
                write!(child_stdin, "{stdin}")?;
            }
        }

        let sec = p
            .timeout
            .parse::<u64>()
            .with_context(|| format!("Invalid timeout '{}'", p.timeout))?;

        let timeout = Duration::from_secs(sec);
        if child.wait_timeout(timeout)?.is_none() {
            child.kill().context("Failed to kill child")?;
            let cmd = get_used_cmd(p);
            bail!("Command '{cmd}' exceeded the {sec}s timeout");
        }

        let output = child
            .wait_with_output()
            .context("Failed to wait on child")?;

        let stdout = from_utf8(&output.stdout)?;
        let stderr = from_utf8(&output.stderr)?;
        let report = json!({
            "exit_code": output.status.code(),
            "stdout": stdout,
            "stderr": stderr,
            "running_time": start_time.elapsed().as_secs(),
        });

        if let Some(output_file) = &p.output_to_file {
            let mut f = File::create(output_file).with_context(|| {
                format!("Could not create output file '{}'", output_file.display())
            })?;
            let mut perm = f.metadata()?.permissions();
            perm.set_mode(0o600);
            f.set_permissions(perm)?;
            write!(f, "{report}")?;
        }
        Ok(report)
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
        let _p: CommandsParameters =
            serde_json::from_value(Value::Object(parameters.data.clone()))?;

        Ok(())
    }

    fn check_apply(&mut self, mode: PolicyMode, parameters: &Parameters) -> CheckApplyResult {
        assert!(self.validate(parameters).is_ok());
        let p: CommandsParameters = serde_json::from_value(Value::Object(parameters.data.clone()))?;

        let output = match mode {
            PolicyMode::Enforce => Commands::run(&p, false)?,
            PolicyMode::Audit if p.run_in_audit_mode => Commands::run(&p, true)?,
            PolicyMode::Audit => {
                let cmd = get_used_cmd(&p);
                println!("dry-run: {cmd}");
                return Ok(Outcome::success());
            }
        };

        let exit_code = output.get("exit_code").unwrap().to_string();
        let res = match exit_code {
            code if code == p.compliant_codes => Outcome::success_with(output.to_string()),
            code if code == p.repaired_codes => Outcome::repaired(output.to_string()),
            _ => bail!("{}", output.to_string()),
        };

        Ok(res)
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

pub fn get_used_cmd(p: &CommandsParameters) -> String {
    let command = &p.command;
    let cmd_args = match &p.args {
        Some(args) => format!("{command} {args}"),
        None => command.clone(),
    };

    let shell = if p.in_shell {
        format!("{} -c ", p.shell_path)
    } else {
        String::new()
    };
    format!("{shell}{cmd_args}")
}
