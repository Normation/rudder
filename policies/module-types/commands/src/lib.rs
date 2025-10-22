mod cli;
use crate::cli::Cli;
use anyhow::{Context, Result, bail};
use indexmap::IndexMap;
use rudder_module_type::rudder_info;
use rudder_module_type::{
    CheckApplyResult, ModuleType0, ModuleTypeMetadata, Outcome, PolicyMode, ValidateResult,
    cfengine::called_from_agent, parameters::Parameters, run_module,
};
use rustix::{fs::Mode, process::umask};
use serde::de::Error;
use serde::{Deserialize, Deserializer, Serialize};
use serde_json::{Value, json};
use std::io::ErrorKind;
use std::{
    fs::{self, File},
    io::Write,
    os::unix::{fs::PermissionsExt, process::CommandExt},
    path::PathBuf,
    process::{Command, Stdio},
    str::from_utf8,
    time::{Duration, Instant},
};
use uzers::{get_group_by_name, get_user_by_name};
use wait_timeout::ChildExt;

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub struct CommandsParameters {
    /// Command to be executed
    command: String,

    /// Arguments to the command
    #[serde(deserialize_with = "Commands::deserialize_args")]
    args: Vec<String>,

    /// Controls the running mode of the command
    #[serde(default)] // Default to false
    run_in_audit_mode: bool,

    /// Controls if the command is executed inside a shell
    #[serde(default)] // Default to false
    in_shell: bool,

    /// Shell path (used only in shell mode)
    #[serde(default = "Commands::default_shell_path")]
    shell_path: String,

    /// Directory from where to execute the command
    #[serde(
        skip_serializing_if = "Option::is_none",
        deserialize_with = "Commands::deserialize_option_string"
    )]
    chdir: Option<String>,

    /// Timeout for command execution
    #[serde(default = "Commands::default_timeout")]
    timeout: String, // Default to 30 seconds

    /// Input passed to the stdin of the executed command
    #[serde(
        skip_serializing_if = "Option::is_none",
        deserialize_with = "Commands::deserialize_option_string"
    )]
    stdin: Option<String>,

    /// Controls the appending of a newline to the stdin input
    #[serde(default = "Commands::default_as_true")]
    stdin_add_newline: bool,

    /// Compliant codes
    #[serde(
        skip_serializing_if = "Option::is_none",
        deserialize_with = "Commands::deserialize_option_string"
    )]
    compliant_codes: Option<String>,

    /// Repaired codes
    #[serde(default = "Commands::default_repaired_codes")]
    repaired_codes: String, // Default to "0"

    /// File to store the output of the command
    #[serde(
        skip_serializing_if = "Option::is_none",
        deserialize_with = "Commands::deserialize_option_pathbuf"
    )]
    output_to_file: Option<PathBuf>,

    // Controls the strip of the content inside the output file
    #[serde(default)] // Default to false
    strip_output: bool,

    /// UID used by the executed command
    #[serde(
        skip_serializing_if = "Option::is_none",
        deserialize_with = "Commands::deserialize_option_string"
    )]
    uid: Option<String>,

    /// User used for running the command
    #[serde(
        skip_serializing_if = "Option::is_none",
        deserialize_with = "Commands::deserialize_option_string"
    )]
    user: Option<String>,

    /// Group used for running the command
    #[serde(
        skip_serializing_if = "Option::is_none",
        deserialize_with = "Commands::deserialize_option_string"
    )]
    group: Option<String>,

    /// GID used by the executed command
    #[serde(
        skip_serializing_if = "Option::is_none",
        deserialize_with = "Commands::deserialize_option_string"
    )]
    gid: Option<String>,

    /// Umask used by the executed command
    #[serde(
        skip_serializing_if = "Option::is_none",
        deserialize_with = "Commands::deserialize_option_string"
    )]
    umask: Option<String>,

    /// Environment variables used by the executed command
    #[serde(deserialize_with = "Commands::deserialize_env_vars")]
    env_vars: IndexMap<String, String>,

    /// Controls output of diffs in the report
    #[serde(default = "Commands::default_as_true")]
    show_content: bool,
}

struct Commands {}

impl Commands {
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

    fn parse_env_vars_value(value: Value) -> Result<IndexMap<String, String>> {
        match value {
            Value::String(s) if s.is_empty() => Ok(IndexMap::new()),
            Value::String(s) => {
                if let Ok(parsed_value) = serde_json::from_str::<Value>(&s) {
                    Self::parse_env_vars_value(parsed_value)
                } else {
                    bail!("Env vars must be a JSON map of strings, got {}", s);
                }
            }
            Value::Object(map) => {
                let mut result = IndexMap::new();
                for (k, v) in map {
                    match v {
                        Value::String(s) => {
                            result.insert(k, s);
                        }
                        _ => bail!("Env vars must be a JSON map of strings, got {} => {}", k, v),
                    }
                }
                Ok(result)
            }
            Value::Null => Ok(IndexMap::new()),
            _ => bail!("Env vars must be a JSON map of strings, got {}", value),
        }
    }
    fn deserialize_env_vars<'de, D>(deserializer: D) -> Result<IndexMap<String, String>, D::Error>
    where
        D: Deserializer<'de>,
    {
        let value = Value::deserialize(deserializer)?;
        Self::parse_env_vars_value(value).map_err(serde::de::Error::custom)
    }

    // Can handle both JSON or serialized JSON inputs
    // IE:
    // "{"foo":"bar"}" or "{\"foo\":\"bar\"}" to ensure potential future raw
    // YAML inputs to the module
    fn parse_args_value(value: Value) -> Result<Vec<String>> {
        match value {
            Value::String(s) if s.is_empty() => Ok(Vec::new()),
            Value::String(s) => {
                if let Ok(parsed_value) = serde_json::from_str::<Value>(&s) {
                    Self::parse_args_value(parsed_value)
                } else {
                    bail!("Args must be a JSON array of strings, got {}", s);
                }
            }
            Value::Array(arr) => {
                let args: Result<Vec<String>, _> = arr
                    .into_iter()
                    .map(|v| match v {
                        Value::String(s) => Ok(s),
                        _ => bail!("The args parameter must be:\n- be empty\n- a string without whitespaces\n- a JSON array of strings"),
                    }).collect();
                Ok(args?)
            }
            Value::Null => Ok(Vec::new()),
            _ => bail!(
                "The args parameter must be:\n- be empty\n- a string without whitespaces\n- a JSON array of strings"
            ),
        }
    }
    fn deserialize_args<'de, D>(deserializer: D) -> Result<Vec<String>, D::Error>
    where
        D: Deserializer<'de>,
    {
        let value = Value::deserialize(deserializer)?;
        Self::parse_args_value(value).map_err(D::Error::custom)
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

    fn run(p: &CommandsParameters, audit: bool) -> Result<Value> {
        let mut command = Command::new(if p.in_shell {
            &p.shell_path
        } else {
            &p.command
        });

        if p.in_shell {
            command.arg("-c");
            command.arg(&p.command);
        }

        if !p.args.is_empty() {
            if p.in_shell {
                bail!("The parameters 'in_shell' and 'args' should not be used at the same time")
            }
            p.args.iter().for_each(|a| {
                command.arg(a);
            });
        }

        if let Some(chdir) = &p.chdir
            && !chdir.is_empty()
        {
            if !fs::exists(chdir)? {
                bail!("The chdir directory '{}' does not exist", chdir);
            }
            command.current_dir(chdir);
        }

        if let Some(user) = &p.user {
            let uid = match get_user_by_name(user) {
                Some(user) => user.uid(),
                None => bail!("User '{user}' not found"),
            };

            command.uid(uid);
        }

        if let Some(uid) = &p.uid {
            let uid = uid
                .parse::<u32>()
                .with_context(|| format!("'{uid}' is not a valid uid"))?;

            command.uid(uid);
        }

        if let Some(group) = &p.group {
            let gid = match get_group_by_name(group) {
                Some(group) => group.gid(),
                None => bail!("Group '{group}' not found"),
            };

            command.gid(gid);
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

        if !p.env_vars.is_empty() {
            command.envs(&p.env_vars);
        }

        command.stdout(Stdio::piped());
        command.stderr(Stdio::piped());
        command.stdin(Stdio::piped());

        let start_time = Instant::now();

        let mut child = match command.spawn() {
            Ok(child) => child,
            Err(e) => match e.kind() {
                ErrorKind::NotFound => {
                    bail!(
                        "Command '{}' not found. Please check if it's installed and in PATH",
                        p.command
                    );
                }
                ErrorKind::PermissionDenied => {
                    bail!("Permission denied. Could not execute '{}'", p.command);
                }
                _ => {
                    bail!("Failed to spawn process: {}", e);
                }
            },
        };
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

        let (stdout, stderr) = if p.strip_output {
            (
                strip_trailing_newline(stdout),
                strip_trailing_newline(stderr),
            )
        } else {
            (stdout, stderr)
        };

        let exit_code = match output.status.code() {
            Some(code) => code,
            None => {
                println!("Process terminated by signal");
                128
            }
        };

        let repaired_codes: Vec<i32> = p
            .repaired_codes
            .split(' ')
            .map(|s| {
                s.trim()
                    .parse::<i32>()
                    .with_context(|| format!("Invalid repaired code '{}'", s))
            })
            .collect::<Result<Vec<i32>>>()?;

        let compliant_codes = if let Some(cc) = &p.compliant_codes {
            Some(
                cc.split_whitespace()
                    .map(|s| {
                        s.trim()
                            .parse::<i32>()
                            .with_context(|| format!("Invalid compliant code '{}'", s))
                    })
                    .collect::<Result<Vec<i32>>>()?,
            )
        } else {
            None
        };

        let status = match (audit, exit_code, repaired_codes, compliant_codes) {
            // The first case is to report a compliant status in audit when a repaired
            // would be logical, as a repaired status in audit would break the agent run
            (true, e, r, _) if r.contains(&e) => "compliant",
            (false, e, r, _) if r.contains(&e) => "repaired",
            (_, e, _, Some(c)) if c.contains(&e) => "compliant",
            _ => "failed",
        };

        let elapsed = start_time.elapsed();
        let report = json!({
            "command": format!("{:?}", command),
            "exit_code": exit_code,
            "status": status,
            "stdout": stdout,
            "stderr": stderr,
            "running_time": format!("{}.{:03}s", elapsed.as_secs(),
                elapsed.subsec_millis())
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

    fn validate(&self, parameters: &Parameters) -> ValidateResult {
        let p: CommandsParameters = serde_json::from_value(Value::Object(parameters.data.clone()))?;
        if p.command.is_empty() {
            bail!("the command provided is empty!");
        }

        if p.uid.is_some() && p.user.is_some() {
            bail!(
                "Mutually exclusive argument provided: the argument 'uid' cannot be used with 'user'"
            );
        }

        if p.gid.is_some() && p.group.is_some() {
            bail!(
                "Mutually exclusive argument provided: the argument 'gid' cannot be used with 'group'"
            );
        }

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
                rudder_info!("Skipped command in audit mode: {cmd}");
                return Ok(Outcome::success());
            }
        };

        let status = output.get("status").unwrap().to_string();
        let report_data = if p.show_content {
            output.to_string()
        } else {
            "Report data is not available (show_content is disabled)".to_string()
        };

        let res = match status.as_str() {
            "\"compliant\"" => Outcome::success_with(report_data),
            "\"repaired\"" => Outcome::repaired(report_data),
            _ => bail!("{}", report_data),
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
    let cmd_args = format!("{command} {}", p.args.join(" "));

    if p.in_shell {
        format!("{} -c '{}'", p.shell_path, cmd_args)
    } else {
        cmd_args
    }
}

fn strip_trailing_newline(input: &str) -> &str {
    input
        .strip_suffix("\r\n")
        .or(input.strip_suffix("\n"))
        .unwrap_or(input)
}

#[cfg(test)]
mod tests {
    use super::*;
    use indexmap::indexmap;

    #[test]
    fn test_env_vars_deserialization_of_json_inputs() {
        let value: Value =
            serde_json::from_str(r#"{"USER": "admin", "SHELL": "/bin/bash"}"#).unwrap();
        let result = Commands::parse_env_vars_value(value).unwrap();
        assert_eq!(result.get("USER"), Some(&"admin".to_string()));
        assert_eq!(result.get("SHELL"), Some(&"/bin/bash".to_string()));

        // Order should be preserved
        let keys: Vec<String> = result.into_keys().collect();
        assert_eq!(keys, vec!["USER".to_string(), "SHELL".to_string()]);

        let value: Value = Value::Null;
        let result = Commands::parse_env_vars_value(value).unwrap();
        assert!(result.is_empty());

        let value: Value = serde_json::from_str(r#""plouf""#).unwrap();
        let result = Commands::parse_env_vars_value(value);
        assert!(result.is_err());
    }
    #[test]
    fn test_env_vars_deserialization_of_escaped_json_inputs() {
        let value: Value =
            serde_json::from_str(r#""{\"USER\": \"admin\", \"SHELL\": \"/bin/bash\"}""#).unwrap();
        let result = Commands::parse_env_vars_value(value).unwrap();
        assert_eq!(result.get("USER"), Some(&"admin".to_string()));
        assert_eq!(result.get("SHELL"), Some(&"/bin/bash".to_string()));

        // Order should be preserved
        let keys: Vec<String> = result.into_keys().collect();
        assert_eq!(keys, vec!["USER".to_string(), "SHELL".to_string()]);

        let value: Value = Value::Null;
        let result = Commands::parse_env_vars_value(value).unwrap();
        assert!(result.is_empty());
    }

    #[test]
    fn test_args_deserialization_of_json_inputs() {
        let value: Value = serde_json::from_str(r#"["-ns", "/foo/bar.txt"]"#).unwrap();
        let result = Commands::parse_args_value(value).unwrap();
        assert_eq!(result[0], "-ns".to_string());
        assert_eq!(result[1], "/foo/bar.txt".to_string());
        assert_eq!(result.len(), 2);

        let value: Value = serde_json::from_str(r#""plouf""#).unwrap();
        let result = Commands::parse_args_value(value);
        assert!(result.is_err());

        let value: Value = serde_json::from_str(r#""""#).unwrap();
        let result = Commands::parse_args_value(value).unwrap();
        assert_eq!(result.len(), 0);

        let value: Value = Value::Null;
        let result = Commands::parse_args_value(value).unwrap();
        assert!(result.is_empty());
    }

    #[test]
    fn test_args_deserialization_of_escaped_json_inputs() {
        let value: Value = serde_json::from_str(r#""[\"-ns\", \"/foo/bar.txt\"]""#).unwrap();
        let result = Commands::parse_args_value(value).unwrap();
        assert_eq!(result[0], "-ns".to_string());
        assert_eq!(result[1], "/foo/bar.txt".to_string());
        assert_eq!(result.len(), 2);
    }

    #[test]
    #[cfg(target_family = "unix")]
    fn test_get_used_cmd_echo_ok() {
        use super::*;

        let cmd = CommandsParameters {
            command: "echo".to_string(),
            args: vec!["OK".to_string()],
            run_in_audit_mode: false,
            in_shell: false,
            shell_path: "/bin/sh".to_string(),
            chdir: None,
            timeout: "30".to_string(),
            stdin: None,
            stdin_add_newline: true,
            compliant_codes: None,
            repaired_codes: "0".to_string(),
            output_to_file: None,
            strip_output: false,
            uid: None,
            gid: None,
            user: None,
            group: None,
            umask: None,
            env_vars: IndexMap::default(),
            show_content: true,
        };

        let cmd_string = get_used_cmd(&cmd);
        assert_eq!(cmd_string, "echo OK");
    }

    #[test]
    #[cfg(target_family = "unix")]
    fn test_wrong_cmd() {
        use super::*;

        let cmd = CommandsParameters {
            command: "/bin/eho".to_string(),
            args: vec!["OK".to_string()],
            run_in_audit_mode: false,
            in_shell: false,
            shell_path: "/bin/sh".to_string(),
            chdir: None,
            timeout: "30".to_string(),
            stdin: None,
            stdin_add_newline: true,
            compliant_codes: None,
            repaired_codes: "0".to_string(),
            output_to_file: None,
            strip_output: false,
            uid: None,
            gid: None,
            user: None,
            group: None,
            umask: None,
            env_vars: IndexMap::default(),
            show_content: true,
        };

        let s = Commands::run(&cmd, false);
        assert!(s.is_err());
    }

    #[test]
    #[cfg(target_family = "unix")]
    fn test_wrong_cmd_in_shell() {
        use super::*;

        let cmd = CommandsParameters {
            command: "eho".to_string(),
            args: vec!["OK".to_string()],
            run_in_audit_mode: false,
            in_shell: false,
            shell_path: "/bin/sh".to_string(),
            chdir: None,
            timeout: "30".to_string(),
            stdin: None,
            stdin_add_newline: true,
            compliant_codes: None,
            repaired_codes: "0".to_string(),
            output_to_file: None,
            strip_output: false,
            uid: None,
            gid: None,
            user: None,
            group: None,
            umask: None,
            env_vars: IndexMap::default(),
            show_content: true,
        };

        let s = Commands::run(&cmd, false);
        assert!(s.is_err());
    }

    #[test]
    #[cfg(target_family = "unix")]
    fn test_get_used_cmd_in_default_shell() {
        use super::*;

        let cmd = CommandsParameters {
            command: "echo".to_string(),
            args: vec!["OK".to_string()],
            run_in_audit_mode: false,
            in_shell: true,
            shell_path: "/bin/sh".to_string(),
            chdir: None,
            timeout: "30".to_string(),
            stdin: None,
            stdin_add_newline: true,
            compliant_codes: None,
            repaired_codes: "0".to_string(),
            output_to_file: None,
            strip_output: false,
            uid: None,
            gid: None,
            user: None,
            group: None,
            umask: None,
            env_vars: IndexMap::default(),
            show_content: true,
        };

        let cmd_string = get_used_cmd(&cmd);
        assert_eq!(cmd_string, "/bin/sh -c 'echo OK'");
    }

    #[test]
    #[cfg(target_family = "unix")]
    fn test_strip_trailing_newline_r_n() {
        use super::*;

        let s = strip_trailing_newline("A string\r\n");
        assert_eq!(s, "A string");
    }

    #[test]
    #[cfg(target_family = "unix")]
    fn test_strip_trailing_newline_n() {
        use super::*;

        let s = strip_trailing_newline("A string\n");
        assert_eq!(s, "A string");
    }

    #[test]
    #[cfg(target_family = "unix")]
    fn test_run_cd_in_shell() {
        use super::*;

        let cmd = CommandsParameters {
            command: "cd /tmp && pwd".to_string(),
            args: Vec::new(),
            run_in_audit_mode: false,
            in_shell: true,
            shell_path: "/bin/sh".to_string(),
            chdir: None,
            timeout: "30".to_string(),
            stdin: None,
            stdin_add_newline: true,
            compliant_codes: None,
            repaired_codes: "0".to_string(),
            output_to_file: None,
            strip_output: true,
            uid: None,
            gid: None,
            user: None,
            group: None,
            umask: None,
            env_vars: IndexMap::default(),
            show_content: true,
        };

        let s = Commands::run(&cmd, false);
        assert!(s.is_ok());

        let out = s.unwrap();

        let exit_code = out.get("exit_code").unwrap().to_string();
        assert_eq!(exit_code, "0");

        let stdout = out.get("stdout").unwrap().to_string();
        assert_eq!(stdout, "\"/tmp\"");
    }

    #[test]
    #[cfg(target_family = "unix")]
    fn test_cmd_with_args() {
        use super::*;

        let cmd = CommandsParameters {
            command: "echo".to_string(),
            args: vec![
                "OK1".to_string(),
                "--".to_string(),
                "OK2".to_string(),
                "--".to_string(),
                "OK3".to_string(),
            ],
            run_in_audit_mode: false,
            in_shell: false,
            shell_path: "/bin/sh".to_string(),
            chdir: None,
            timeout: "30".to_string(),
            stdin: None,
            stdin_add_newline: true,
            compliant_codes: None,
            repaired_codes: "0".to_string(),
            output_to_file: None,
            strip_output: true,
            uid: None,
            gid: None,
            user: None,
            group: None,
            umask: None,
            env_vars: IndexMap::default(),
            show_content: true,
        };

        let s = Commands::run(&cmd, false);
        assert!(s.is_ok());

        let out = s.unwrap();

        let exit_code = out.get("exit_code").unwrap().to_string();
        assert_eq!(exit_code, "0");

        let stdout = out.get("stdout").unwrap().to_string();
        assert_eq!(stdout, "\"OK1 -- OK2 -- OK3\"");
    }

    #[test]
    #[cfg(target_family = "unix")]
    fn test_run_ls_on_root() {
        use super::*;

        let cmd = CommandsParameters {
            command: "ls".to_string(),
            args: vec!["/".to_string()],
            run_in_audit_mode: false,
            in_shell: false,
            shell_path: "/bin/sh".to_string(),
            chdir: None,
            timeout: "30".to_string(),
            stdin: None,
            stdin_add_newline: true,
            compliant_codes: None,
            repaired_codes: "0".to_string(),
            output_to_file: None,
            strip_output: true,
            uid: None,
            gid: None,
            user: None,
            group: None,
            umask: None,
            env_vars: IndexMap::default(),
            show_content: true,
        };

        let s = Commands::run(&cmd, false);
        assert!(s.is_ok());

        let out = s.unwrap();

        let exit_code = out.get("exit_code").unwrap().to_string();
        assert_eq!(exit_code, "0");

        let stderr = out.get("stderr").unwrap().to_string();
        assert_eq!(stderr, "\"\"");
    }

    #[test]
    #[cfg(target_family = "unix")]
    fn test_run_timeout_30_and_sleep_3_in_shell() {
        use super::*;

        let cmd = CommandsParameters {
            command: "sleep 3".to_string(),
            args: Vec::new(),
            run_in_audit_mode: false,
            in_shell: true,
            shell_path: "/bin/sh".to_string(),
            chdir: None,
            timeout: "30".to_string(),
            stdin: None,
            stdin_add_newline: true,
            compliant_codes: None,
            repaired_codes: "0".to_string(),
            output_to_file: None,
            strip_output: true,
            uid: None,
            gid: None,
            user: None,
            group: None,
            umask: None,
            env_vars: IndexMap::default(),
            show_content: true,
        };

        let s = Commands::run(&cmd, false);
        assert!(s.is_ok());

        let out = s.unwrap();

        let exit_code = out.get("exit_code").unwrap().to_string();
        assert_eq!(exit_code, "0");

        let stderr = out.get("stderr").unwrap().to_string();
        assert_eq!(stderr, "\"\"");
    }

    #[test]
    #[cfg(target_family = "unix")]
    fn test_run_timeout_2_and_sleep_3_in_shell() {
        use super::*;

        let cmd = CommandsParameters {
            command: "sleep 3".to_string(),
            args: Vec::new(),
            run_in_audit_mode: false,
            in_shell: true,
            shell_path: "/bin/sh".to_string(),
            chdir: None,
            timeout: "2".to_string(),
            stdin: None,
            stdin_add_newline: true,
            compliant_codes: None,
            repaired_codes: "0".to_string(),
            output_to_file: None,
            strip_output: true,
            uid: None,
            gid: None,
            user: None,
            group: None,
            umask: None,
            env_vars: IndexMap::default(),
            show_content: true,
        };

        let s = Commands::run(&cmd, false);
        assert!(s.is_err());
    }

    #[test]
    #[cfg(target_family = "unix")]
    fn test_run_stdin() {
        use super::*;

        let cmd = CommandsParameters {
            command: "cat".to_string(),
            args: vec!["--".to_string()],
            run_in_audit_mode: false,
            in_shell: false,
            shell_path: "/bin/sh".to_string(),
            chdir: None,
            timeout: "30".to_string(),
            stdin: Some("OK".to_string()),
            stdin_add_newline: true,
            compliant_codes: None,
            repaired_codes: "0".to_string(),
            output_to_file: None,
            strip_output: true,
            uid: None,
            gid: None,
            user: None,
            group: None,
            umask: None,
            env_vars: IndexMap::default(),
            show_content: true,
        };

        let s = Commands::run(&cmd, false);
        assert!(s.is_ok());

        let out = s.unwrap();

        let exit_code = out.get("exit_code").unwrap().to_string();
        assert_eq!(exit_code, "0");

        let stdout = out.get("stdout").unwrap().to_string();
        assert_eq!(stdout, "\"OK\"");
    }

    #[test]
    #[cfg(target_family = "unix")]
    fn test_run_chdir_pwd() {
        use super::*;

        let cmd = CommandsParameters {
            command: "pwd".to_string(),
            args: Vec::new(),
            run_in_audit_mode: false,
            in_shell: false,
            shell_path: "/bin/sh".to_string(),
            chdir: Some("/tmp".to_string()),
            timeout: "30".to_string(),
            stdin: None,
            stdin_add_newline: true,
            compliant_codes: None,
            repaired_codes: "0".to_string(),
            output_to_file: None,
            strip_output: true,
            uid: None,
            gid: None,
            user: None,
            group: None,
            umask: None,
            env_vars: IndexMap::default(),
            show_content: true,
        };

        let s = Commands::run(&cmd, false);
        assert!(s.is_ok());

        let out = s.unwrap();

        let exit_code = out.get("exit_code").unwrap().to_string();
        assert_eq!(exit_code, "0");

        let stdout = out.get("stdout").unwrap().to_string();
        assert_eq!(stdout, "\"/tmp\"");
    }

    #[test]
    #[cfg(target_family = "unix")]
    fn test_run_strip_output_false() {
        use super::*;

        let cmd = CommandsParameters {
            command: "echo".to_string(),
            args: vec!["OK".to_string()],
            run_in_audit_mode: false,
            in_shell: false,
            shell_path: "/bin/sh".to_string(),
            chdir: None,
            timeout: "30".to_string(),
            stdin: None,
            stdin_add_newline: true,
            compliant_codes: None,
            repaired_codes: "0".to_string(),
            output_to_file: None,
            strip_output: false,
            uid: None,
            gid: None,
            user: None,
            group: None,
            umask: None,
            env_vars: IndexMap::default(),
            show_content: true,
        };

        let s = Commands::run(&cmd, false);
        assert!(s.is_ok());

        let out = s.unwrap();

        let exit_code = out.get("exit_code").unwrap().to_string();
        assert_eq!(exit_code, "0");

        let stdout = out.get("stdout").unwrap().to_string();
        assert_eq!(stdout, "\"OK\\n\"");
    }

    #[test]
    #[cfg(target_family = "unix")]
    fn test_repaired_codes() {
        use super::*;

        let cmd = CommandsParameters {
            command: "echo".to_string(),
            args: vec!["OK".to_string()],
            run_in_audit_mode: false,
            in_shell: false,
            shell_path: "/bin/sh".to_string(),
            chdir: None,
            timeout: "30".to_string(),
            stdin: None,
            stdin_add_newline: true,
            compliant_codes: None,
            repaired_codes: "0".to_string(),
            output_to_file: None,
            strip_output: false,
            uid: None,
            gid: None,
            user: None,
            group: None,
            umask: None,
            env_vars: IndexMap::default(),
            show_content: true,
        };

        let s = Commands::run(&cmd, false);
        assert!(s.is_ok());

        let out = s.unwrap();

        let exit_code = out.get("exit_code").unwrap().to_string();
        assert_eq!(exit_code, "0");

        let status = out.get("status").unwrap().to_string();
        assert_eq!(status, "\"repaired\"");
    }

    #[test]
    #[cfg(target_family = "unix")]
    fn test_env() {
        use super::*;

        let cmd = CommandsParameters {
            command: "env".to_string(),
            args: Vec::new(),
            run_in_audit_mode: false,
            in_shell: false,
            shell_path: "/bin/sh".to_string(),
            chdir: None,
            timeout: "30".to_string(),
            stdin: None,
            stdin_add_newline: true,
            compliant_codes: None,
            repaired_codes: "0".to_string(),
            output_to_file: None,
            strip_output: false,
            uid: None,
            gid: None,
            user: None,
            group: None,
            umask: None,
            env_vars: indexmap! {
                "SUPER_ENV_TEST".to_string() => "MY_VAR".to_string(),
                "MY_SECOND_VAR".to_string() => "MY_SECOND_VALUE".to_string(),
            },
            show_content: true,
        };

        let s = Commands::run(&cmd, false);
        assert!(s.is_ok());

        let out = s.unwrap();

        let exit_code = out.get("exit_code").unwrap().to_string();
        assert_eq!(exit_code, "0");

        let stdout = out.get("stdout").unwrap().to_string();
        assert!(stdout.contains("SUPER_ENV_TEST=MY_VAR"));
        assert!(stdout.contains("MY_SECOND_VAR=MY_SECOND_VALUE"));
    }

    #[test]
    #[cfg(target_family = "unix")]
    fn test_env_in_shell() {
        use super::*;

        let cmd = CommandsParameters {
            command: "env".to_string(),
            args: Vec::new(),
            run_in_audit_mode: false,
            in_shell: true,
            shell_path: "/bin/sh".to_string(),
            chdir: None,
            timeout: "30".to_string(),
            stdin: None,
            stdin_add_newline: true,
            compliant_codes: None,
            repaired_codes: "0".to_string(),
            output_to_file: None,
            strip_output: false,
            uid: None,
            gid: None,
            user: None,
            group: None,
            umask: None,
            env_vars: indexmap! {
               "SUPER_ENV_TEST".to_string() => "MY_VAR".to_string(),
               "MY_SECOND_VAR".to_string() => "MY_SECOND_VALUE".to_string(),
               "BOUM3".to_string() => "PAF3".to_string(),
            },
            show_content: true,
        };

        let s = Commands::run(&cmd, false);
        assert!(s.is_ok());

        let out = s.unwrap();

        let exit_code = out.get("exit_code").unwrap().to_string();
        assert_eq!(exit_code, "0");

        let stdout = out.get("stdout").unwrap().to_string();
        assert!(stdout.contains("SUPER_ENV_TEST=MY_VAR"));
        assert!(stdout.contains("MY_SECOND_VAR=MY_SECOND_VALUE"));
        assert!(stdout.contains("BOUM3=PAF3"));
    }
}
