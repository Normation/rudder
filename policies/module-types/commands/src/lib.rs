mod cli;
use crate::cli::Cli;
use rudder_module_type::rudder_info;
use std::{
    collections::HashMap,
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
    CheckApplyResult, ModuleType0, ModuleTypeMetadata, Outcome, PolicyMode, ValidateResult,
    cfengine::called_from_agent, parameters::Parameters, run_module,
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
        deserialize_with = "Commands::deserialize_option_string"
    )]
    args: Option<String>,

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
    #[serde(skip_serializing_if = "Option::is_none")]
    env_vars: Option<String>,

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

        if let Some(args) = &p.args
            && !args.is_empty()
        {
            command.args(args.split_whitespace());
        }

        if let Some(chdir) = &p.chdir
            && !chdir.is_empty()
        {
            if !fs::exists(chdir)? {
                bail!("The chdir directory '{}' does not exist", chdir);
            }
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

        if let Some(env) = &p.env_vars
            && !env.is_empty()
        {
            let env_map: HashMap<String, String> = env
                .lines()
                .filter_map(|line| {
                    let mut tokens = line.splitn(2, '=');
                    match (tokens.next(), tokens.next()) {
                        (Some(key), Some(value)) => Some((key.to_string(), value.to_string())),
                        _ => {
                            println!("Invalid env variable: '{line}'");
                            None
                        }
                    }
                })
                .collect();

            command.envs(env_map);
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
            (false, e, r, _) if r.contains(&e) => "repaired",
            (true, e, _, Some(c)) if c.contains(&e) => "compliant",
            (true, _, _, None) => "compliant",
            _ => "failed",
        };

        let report = json!({
            "exit_code": exit_code,
            "status": status,
            "stdout": stdout,
            "stderr": stderr,
            "running_time": format!("{}.{}", start_time.elapsed().as_secs(),
                start_time.elapsed().subsec_millis())
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
    let cmd_args = match &p.args {
        Some(args) => format!("{command} {args}"),
        None => command.clone(),
    };

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

mod tests {
    #[test]
    #[cfg(target_family = "unix")]
    fn test_get_used_cmd_echo_ok() {
        use super::*;

        let cmd = CommandsParameters {
            command: "echo".to_string(),
            args: Some("OK".to_string()),
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
            umask: None,
            env_vars: None,
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
            args: Some("OK".to_string()),
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
            umask: None,
            env_vars: None,
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
            args: Some("OK".to_string()),
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
            umask: None,
            env_vars: None,
            show_content: true,
        };

        let s = Commands::run(&cmd, false);
        assert!(s.is_ok());

        let out = s.unwrap();

        let exit_code = out.get("exit_code").unwrap().to_string();
        assert_eq!(exit_code, "127");
    }

    #[test]
    #[cfg(target_family = "unix")]
    fn test_get_used_cmd_in_default_shell() {
        use super::*;

        let cmd = CommandsParameters {
            command: "echo".to_string(),
            args: Some("OK".to_string()),
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
            umask: None,
            env_vars: None,
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
            args: None,
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
            umask: None,
            env_vars: None,
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
    fn test_run_ls_on_root() {
        use super::*;

        let cmd = CommandsParameters {
            command: "ls".to_string(),
            args: Some("/".to_string()),
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
            umask: None,
            env_vars: None,
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
            args: None,
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
            umask: None,
            env_vars: None,
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
            args: None,
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
            umask: None,
            env_vars: None,
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
            args: Some("--".to_string()),
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
            umask: None,
            env_vars: None,
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
            args: None,
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
            umask: None,
            env_vars: None,
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
            args: Some("OK".to_string()),
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
            umask: None,
            env_vars: None,
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
            args: Some("OK".to_string()),
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
            umask: None,
            env_vars: None,
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
            args: None,
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
            umask: None,
            env_vars: Some("SUPER_ENV_TEST=MY_VAR\nMY_SECOND_VAR=MY_SECOND_VALUE".to_string()),
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
            args: None,
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
            umask: None,
            env_vars: Some(
                "SUPER_ENV_TEST=MY_VAR\nMY_SECOND_VAR=MY_SECOND_VALUE\nBOUM3=PAF3".to_string(),
            ),
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
