use anyhow::{Context, Result, bail};
use ini::{EscapePolicy, Ini, ParseOption, WriteOption};
use rudder_module_type::utf16_file::{read_utf16_file, write_utf16_file};
use serde_json::{Map, Value};
use std::{collections::HashMap, path::Path, process::Command};
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
    changes: HashMap<String, ConfigValue>,
}

#[derive(Debug)]
struct ConfigValue {
    old: String,
    new: String,
}

impl ConfigValue {
    fn new(old: String, new: String) -> Self {
        Self { old, new }
    }

    fn equal(&self) -> bool {
        self.old == self.new
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
        let report = config_search_and_replace(&mut config, &data)?;
        println!("{report:?}");
        if audit {
            println!("Audit DONE");
        } else {
            // enforce
            self.apply_config(&config)?;
            println!("DONE");
        }

        Ok(report.status)
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

        parse_config(&template_config)
    }
}

fn parse_config(path: &Path) -> Result<Ini> {
    let data = read_utf16_file(path)?;
    let opt = ParseOption {
        enabled_escape: false,
        enabled_quote: false,
        ..Default::default()
    };
    let template = Ini::load_from_str_opt(&data, opt)
        .with_context(|| format!("Failed to read template file '{}'", path.display()))?;

    Ok(template)
}

fn invoke_with_args(args: &str) -> Result<()> {
    const COMMAND: &str = "secedit.exe";
    let mut cmd = Command::new(COMMAND);

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
        if section.as_str() == "Registry Values" {
            report
                .errors
                .push("Registry values are not supported".to_string());
            report.status = Outcome::Failure;
            continue;
        }

        let props = match config.section_mut(Some(section)) {
            Some(m) => m,
            None => {
                report
                    .errors
                    .push(format!("section '{section}' does not exist"));
                report.status = Outcome::Failure;
                continue;
            }
        };

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
            match props.get(key) {
                Some(old) => {
                    let new = value.to_string().replace("\"", "");
                    let v = ConfigValue::new(old.replace("\"", ""), new.clone());
                    if !v.equal() {
                        report.changes.insert(key.to_string(), v);
                        props.insert(key, new);
                    }
                }
                None => report
                    .errors
                    .push(format!("key '{key}' in section '{section}' does not exist")),
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
            }
        });
        let data = data.as_object().unwrap();

        let res = config_search_and_replace(&mut config, data);

        assert!(res.is_ok());
        assert_eq!(config.get_from(Some("User"), "name"), Some("Ferris"));
        assert_eq!(config.get_from(Some("User"), "value"), Some("42"));
        assert_eq!(config.get_from(Some("Settings"), "abc"), Some("12"));
    }

    #[test]
    fn test_parse_config() {
        let path = Path::new(env!("CARGO_MANIFEST_DIR")).join("test/config.ini");
        let mut config = parse_config(&path).unwrap();

        let data = json!({
          "System Access": {
            "MinimumPasswordLength": 12,
            "MinimumPasswordAge": 0,
            "NewAdministratorName": "Ferris"
          }
        });
        let data = data.as_object().unwrap();
        let _ = config_search_and_replace(&mut config, data);
        assert_eq!(
            config
                .get_from(Some("System Access"), "MaximumPasswordAge")
                .unwrap(),
            "42"
        );
        assert_eq!(config.get_from(Some("Unicode"), "Unicode").unwrap(), "yes");

        assert_eq!(
            config
                .get_from(Some("System Access"), "NewAdministratorName")
                .unwrap(),
            "Ferris"
        );

        assert_eq!(
            config
                .get_from(Some("Privilege Rights"), "SeNetworkLogonRight")
                .unwrap(),
            "*S-1-1-0,*S-1-5-32-544,*S-1-5-32-545,*S-1-5-32-551"
        );

        assert_eq!(
            config
                .get_from(Some("Event Audit"), "AuditSystemEvents")
                .unwrap(),
            "0"
        );

        assert_eq!(config.get_from(Some("Version"), "Revision").unwrap(), "1");
    }

    #[test]
    fn test_config_value_equal() {
        let v = ConfigValue::new("test".to_string(), "test".to_string()).equal();
        assert!(v)
    }
}
