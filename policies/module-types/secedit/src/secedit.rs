use anyhow::{Context, Result, anyhow, bail};
use ini::{EscapePolicy, Ini, ParseOption, WriteOption};
use rudder_module_type::utf16_file::{read_utf16_file, write_utf16_file};
use serde_json::{Map, Value};
use std::{
    collections::HashMap,
    path::Path,
    process::{Command, Stdio},
};
use tempdir::TempDir;

#[derive(Debug, Default)]
pub enum Outcome {
    #[default]
    Success,
    NonCompliant,
    Failure,
}

#[derive(Debug, Default)]
pub struct Report {
    status: Outcome,
    errors: Vec<String>,
    data: HashMap<String, ConfigValue>,
}

#[derive(Debug)]
#[allow(dead_code)]
struct ConfigValue {
    old: String,
    new: String,
}

impl ConfigValue {
    fn new(old: String, new: String) -> Self {
        Self { old, new }
    }
}

pub struct Secedit {
    tmp_dir: TempDir,
}

impl Secedit {
    pub fn new(tmpdir: &str) -> Result<Self> {
        Ok(Self {
            tmp_dir: TempDir::new_in(tmpdir, "rudder-module-secedit")?,
        })
    }

    pub fn run(&self, data: Map<String, Value>, audit: bool) -> Result<Outcome> {
        let mut config = self.export()?;
        let log = config_search_and_replace(&mut config, &data)?;
        println!("{log:?}");
        if audit {
            println!("Audit DONE");
        } else {
            self.apply_config(&config)?;
            println!("DONE");
        }

        Ok(Outcome::Success)
    }

    fn apply_config(&self, config: &Ini) -> Result<()> {
        let opt = WriteOption {
            escape_policy: EscapePolicy::Nothing,
            ..Default::default()
        };
        let mut buf: Vec<u8> = Vec::new();
        config.write_to_opt(&mut buf, opt)?;
        let data = String::from_utf8(buf)?;

        let config = self.tmp_dir.path().join("config.ini");
        write_utf16_file(&config, &data)?;

        let db = self
            .tmp_dir
            .path()
            .join("tmp.db")
            .as_path()
            .display()
            .to_string();

        let cmd = format!("/import /db {} /cfg {}", db, config.as_path().display());
        invoke_with_args(&cmd)?;

        let cmd = format!("/configure /db {}", db);
        invoke_with_args(&cmd)?;

        Ok(())
    }

    fn export(&self) -> Result<Ini> {
        let template_config = self.tmp_dir.path().join("template.ini");

        let cmd = format!("/export /cfg {}", template_config.as_path().display());
        invoke_with_args(&cmd)?;

        let data = read_utf16_file(&template_config)?;
        let opt = ParseOption {
            enabled_escape: false,
            ..Default::default()
        };
        let template = Ini::load_from_str_opt(&data, opt).with_context(|| {
            format!(
                "Failed to read template file '{}'",
                template_config.as_path().display()
            )
        })?;

        Ok(template)
    }
}

fn invoke_with_args(args: &str) -> Result<()> {
    const COMMAND: &str = "secedit.exe";
    let mut cmd = Command::new(COMMAND);
    cmd.stdin(Stdio::piped());
    cmd.stdout(Stdio::piped());
    cmd.stderr(Stdio::piped());

    if !args.is_empty() {
        cmd.args(args.split_whitespace());
    }

    let output = match cmd.spawn() {
        Ok(task) => task,
        Err(e) => bail!("Failed to execute command '{COMMAND} {args}' {e}"),
    }
    .wait_with_output()?;

    if !output.status.success() {
        let msg = String::from_utf8_lossy(&output.stdout);
        let log_file = Path::new("C:\\Windows\\security\\logs\\scesrv.log");
        let log = read_utf16_file(log_file)?;
        bail!("{msg}\nlog:\n{log}");
    }

    Ok(())
}

fn config_search_and_replace(config: &mut Ini, data: &Map<String, Value>) -> Result<Report> {
    let mut report = Report::default();

    for (section, section_data) in data {
        let props = config
            .section_mut(Some(section))
            .ok_or_else(|| anyhow!("section '{section}' does not exist"))?;

        let entries = match section_data.as_object() {
            Some(m) => m,
            None => {
                report.errors.push(format!(
                    "Invalid data '{section_data:?}', expected JSON object"
                ));
                report.status = Outcome::Failure;
                continue;
            }
        };

        for (key, value) in entries {
            if let Some(old) = props.clone().get(key) {
                props.insert(key, value.to_string());
                report.data.insert(
                    key.to_string(),
                    ConfigValue::new(old.to_string(), value.to_string()),
                );
            } else {
                report
                    .errors
                    .push(format!("key '{key}' in section '{section}' does not exist"))
            }
        }
    }

    if !report.errors.is_empty() {
        report.status = Outcome::NonCompliant;
    }

    Ok(report)
}

#[cfg(test)]
mod test {
    use serde_json::json;

    use super::*;

    #[test]
    fn test_config_search_and_replace() {
        let opt = ParseOption {
            enabled_escape: false,
            ..Default::default()
        };
        let mut config = Ini::load_from_str_opt(
            r"[User]
            name = Ferris
            value = Pi
            [Settings]
            abc = 21
            [Registry Values]
            MACHINE\Software\Microsoft\Windows NT\CurrentVersion\Setup\RecoveryConsole\SecurityLevel=4,0",
            opt
        )
        .unwrap();

        let data = json!(
        {
            "User": {
                "name": "Ferris",
                "value": 42
            },
            "Settings": {
                "abc": 12
            },
            "Registry Values": {
                "MACHINE\\Software\\Microsoft\\Windows NT\\CurrentVersion\\Setup\\RecoveryConsole\\SecurityLevel": "4,0"
            }
        });
        let data = data.as_object().unwrap();

        let res = config_search_and_replace(&mut config, &data);

        assert!(res.is_ok());
        assert_eq!(config.get_from(Some("User"), "name"), Some("\"Ferris\""));
        assert_eq!(config.get_from(Some("User"), "value"), Some("42"));
        assert_eq!(config.get_from(Some("Settings"), "abc"), Some("12"));
    }
}
