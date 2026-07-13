use anyhow::{Context, Result, anyhow, bail};
use ini::{EscapePolicy, Ini, ParseOption, WriteOption};
use rudder_module_type::parameters::Parameters;
use rudder_module_type::utf16_file::{read_utf16_file, write_utf16_file};
use rudder_module_type::{
    CheckApplyResult, ModuleType0, ModuleTypeMetadata, Outcome, PolicyMode, ProtocolResult,
    ValidateResult,
};
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};
use std::fmt::Display;
use std::{
    path::Path,
    process::{Command, Stdio},
};
use tempfile::{TempDir, tempdir_in};

pub const MODULE_NAME: &str = env!("CARGO_PKG_NAME");
pub const MODULE_FEATURES: [&str; 0] = [];

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub struct SeceditParameters {
    #[serde(flatten)]
    data: Map<String, Value>,
}

#[derive(Debug, Default, Serialize)]
pub enum SeceditOutcome {
    #[default]
    Success,
    Repaired,
    NonCompliant,
}
#[derive(Debug, Default, Serialize)]
pub struct SeceditReport {
    pub status: SeceditOutcome,
    errors: Vec<String>,
    diff: String,
}

impl Display for SeceditReport {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match &self.status {
            SeceditOutcome::Success => {
                write!(f, "Configuration was already correct. Nothing to do.")
            }
            SeceditOutcome::Repaired => {
                write!(f, "Configuration was repaired.\nChanged:{}", self.diff)
            }
            SeceditOutcome::NonCompliant => {
                write!(
                    f,
                    "Configuration was not correct.\n{}{}",
                    self.diff,
                    self.errors.join("\n")
                )
            }
        }
    }
}

impl From<SeceditReport> for CheckApplyResult {
    fn from(s: SeceditReport) -> Self {
        match s.status {
            SeceditOutcome::Success => Ok(Outcome::success()),
            SeceditOutcome::Repaired => Ok(Outcome::repaired(s.to_string())),
            SeceditOutcome::NonCompliant => Err(anyhow!(s.to_string())),
        }
    }
}
pub struct Secedit {}

impl Secedit {
    pub fn new() -> Self {
        Secedit {}
    }

    pub fn run(
        &self,
        mode: PolicyMode,
        parameters: SeceditParameters,
        temp_dir: &TempDir,
    ) -> Result<SeceditReport> {
        let mut config = self.export(temp_dir)?;
        let default_config = get_default_config()?;

        let report = config_search_and_replace(&mut config, &default_config, &parameters.data)?;
        if mode != PolicyMode::Audit {
            self.apply_config(&config, temp_dir)?;
            // Check if the configuration was applied
            let mut new_config = self.export(temp_dir)?;
            let new_report =
                config_search_and_replace(&mut new_config, &default_config, &parameters.data)?;
            if !new_report.diff.is_empty() {
                let data = serde_json::to_string(&parameters.data)?;
                bail!("Could not apply configuration:\n{data}")
            }
        }
        Ok(report)
    }

    fn apply_config(&self, config: &Ini, temp_dir: &TempDir) -> Result<()> {
        let opt = WriteOption {
            escape_policy: EscapePolicy::Nothing,
            ..Default::default()
        };
        let mut buf: Vec<u8> = Vec::new();
        config.write_to_opt(&mut buf, opt)?;
        let data = String::from_utf8(buf)?;

        let config = temp_dir.path().join("config.ini");
        write_utf16_file(&config, &data)?;

        let db = temp_dir
            .path()
            .join("tmp.db")
            .as_path()
            .display()
            .to_string();

        let config = config.as_path().display().to_string();
        let cmd = vec!["/import", "/db", &db, "/cfg", &config];
        invoke_with_args(cmd)?;

        let cmd = vec!["/configure", "/db", &db];
        invoke_with_args(cmd)?;

        Ok(())
    }

    fn export(&self, temp_dir: &TempDir) -> Result<Ini> {
        let template_config = temp_dir.path().join("template.ini");
        let config = template_config.as_path().display().to_string();

        let cmd = vec!["/export", "/cfg", &config];
        invoke_with_args(cmd)?;

        parse_config(&template_config)
    }
}

impl ModuleType0 for Secedit {
    fn metadata(&self) -> ModuleTypeMetadata {
        ModuleTypeMetadata::new(MODULE_NAME, Vec::from(MODULE_FEATURES))
    }

    fn init(&mut self) -> ProtocolResult {
        ProtocolResult::Success
    }

    fn validate(&self, parameters: &Parameters) -> ValidateResult {
        // Parse as parameters type
        let _: SeceditParameters = serde_json::from_value(Value::Object(parameters.data.clone()))?;
        if !parameters.temporary_dir.as_path().exists() {
            bail!(
                "Temporary directory '{}' does not exist",
                parameters.temporary_dir.display()
            );
        }
        Ok(())
    }
    fn check_apply(&mut self, mode: PolicyMode, parameters: &Parameters) -> CheckApplyResult {
        self.validate(parameters)?;
        let p: SeceditParameters = serde_json::from_value(Value::Object(parameters.data.clone()))?;
        let temp_dir = tempdir_in(&parameters.temporary_dir)?;
        self.run(mode, p, &temp_dir)?.into()
    }
}

fn get_default_config() -> Result<Ini> {
    let opt = ParseOption {
        enabled_escape: false,
        ..Default::default()
    };
    let defltbase = Path::new("C:\\Windows\\inf\\defltbase.inf");

    // TODO: update to new utf16 handling implementation.
    let utf8_file = read_utf16_file(defltbase)?;

    let cleaned = utf8_file.trim_end_matches(['\r', '\n', '\x1A']);
    let default = Ini::load_from_str_opt(cleaned, opt)?;

    Ok(default)
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

fn invoke_with_args(args: Vec<&str>) -> Result<()> {
    const COMMAND: &str = "secedit.exe";
    let mut cmd = Command::new(COMMAND);

    if !args.is_empty() {
        cmd.args(&args);
    }

    cmd.stdout(Stdio::piped());
    cmd.stderr(Stdio::piped());
    let output = match cmd.spawn() {
        Ok(task) => task,
        Err(e) => bail!(
            "Failed to execute command '{COMMAND} {}' {e}",
            args.join(" ")
        ),
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

fn validate_property(key: &str, section: &str, value: &Value) -> Result<String> {
    match value {
        Value::String(s) if property_is_string(key, section) => Ok(s.clone()),
        Value::String(s) => Err(anyhow!(
            "Invalid value '{s}' for property '{key}'. String values are not supported for this property."
        )),
        Value::Number(n) if property_is_string(key, section) => Err(anyhow!(
            "Invalid value '{n}' for property '{key}'. Integer values are not supported for this property."
        )),
        Value::Number(n) => Ok(n.to_string()),
        Value::Null => Ok(String::default()),
        _ => Err(anyhow!(
            "Invalid value '{value}'. Only strings and numbers are supported."
        )),
    }
}

fn config_search_and_replace(
    config: &mut Ini,
    default: &Ini,
    data: &Map<String, Value>,
) -> Result<SeceditReport> {
    let mut report = SeceditReport::default();

    for (section, section_data) in data {
        if section.as_str() == "Registry Values" {
            report
                .errors
                .push("The 'Registry Values' section is not supported".to_string());
            continue;
        }

        let properties = match config.section_mut(Some(section)) {
            Some(m) => m,
            None => {
                report
                    .errors
                    .push(format!("section '{section}' does not exist"));
                continue;
            }
        };

        let entries = match section_data.as_object() {
            Some(m) => m,
            None => {
                report.errors.push(format!(
                    "Invalid data '{section_data:?}', expected JSON object"
                ));
                continue;
            }
        };

        let mut section_diff: Vec<String> = vec![];
        for (key, new_value) in entries {
            let new_value = validate_property(key, section, new_value)?.replace("\"", "");
            let new_value = if new_value.is_empty() {
                // set to default value if the provided property is empty

                let default_properties = match default.section(Some(section)) {
                    Some(m) => m,
                    None => {
                        report.errors.push(format!(
                            "section '{section}' does not exist in the default configuration"
                        ));
                        continue;
                    }
                };
                match default_properties.get(key) {
                    Some(default_value) => default_value.replace("\"", ""),
                    None => {
                        report
                            .errors
                            .push(format!("Property '{key}' does not have a default value"));
                        continue;
                    }
                }
            } else {
                new_value
            };
            let old_value = match properties.get(key) {
                Some(old_value) => old_value.replace("\"", ""),
                None => {
                    let default_properties = match default.section(Some(section)) {
                        Some(m) => m,
                        None => {
                            report.errors.push(format!(
                                "section '{section}' does not exist in the default configuration"
                            ));
                            continue;
                        }
                    };
                    match default_properties.get(key) {
                        Some(default_value) => {
                            let default_value = default_value.replace("\"", "");
                            // Insert default value
                            properties.insert(key, &default_value);
                            default_value
                        }
                        None => {
                            report
                                .errors
                                .push(format!("key '{key}' in section '{section}' does not exist"));
                            continue;
                        }
                    }
                }
            };

            if !key_equal(&new_value, &old_value) {
                let diffline = format!("\n    - {key}={old_value}\n    + {key}={new_value}");
                section_diff.push(diffline);
                properties.insert(key, &new_value);
            }
        }
        if !section_diff.is_empty() {
            report
                .diff
                .push_str(&format!("\n {section}:{}", section_diff.join("\n")));
        }
    }

    if !report.errors.is_empty() {
        report.status = SeceditOutcome::NonCompliant;
    }
    if !report.diff.is_empty() {
        report.status = SeceditOutcome::Repaired;
    }
    Ok(report)
}

fn property_is_string(key: &str, section: &str) -> bool {
    match (key, section) {
        // The "NewAdministratorName" and "NewGuestName" properties in the "System Access" section are expected to be strings.
        ("NewAdministratorName" | "NewGuestName", "System Access") => true,
        // The "Unicode" property in the "Unicode" section is expected to be a string.
        ("Unicode", "Unicode") => true,
        // All properties in the "Privilege Rights" section are expected to be strings.
        (_, "Privilege Rights") => true,
        // else must be an Integer
        _ => false,
    }
}

fn key_equal(a: &str, b: &str) -> bool {
    if a.contains(",") {
        let mut sids_a: Vec<&str> = a.split(',').map(|p| p.trim()).collect();
        let mut sids_b: Vec<&str> = b.split(',').map(|p| p.trim()).collect();
        sids_a.sort();
        sids_b.sort();

        sids_a == sids_b
    } else {
        a == b
    }
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
            "[System Access]
            MinimumPasswordAge = 0
            MaximumPasswordAge = 42
            NewAdministratorName = \"Administrator\"
            NewGuestName = \"Guest\"
            LockoutBadCount = 42
            [Unicode]
            Unicode = \"yes\"",
            opt,
        )
        .unwrap();

        let opt = ParseOption {
            enabled_escape: false,
            ..Default::default()
        };
        let default = Ini::load_from_str_opt(
            "[System Access]
            ResetLockoutCount = 10
            ",
            opt,
        )
        .unwrap();

        let data = json!(
        {
            "System Access": {
                "MinimumPasswordAge": 0,
                "MaximumPasswordAge": 21,
                "NewAdministratorName": "Administrator2",
                "NewGuestName": "Guest",
                "LockoutBadCount": 42,
                "ResetLockoutCount": 10
            }
        });
        let data = data.as_object().unwrap();

        let res = config_search_and_replace(&mut config, &default, data);

        assert!(res.is_ok());
        assert_eq!(
            config.get_from(Some("System Access"), "NewAdministratorName"),
            Some("Administrator2")
        );
        assert_eq!(
            config.get_from(Some("System Access"), "NewGuestName"),
            Some("Guest")
        );
        assert_eq!(
            config.get_from(Some("System Access"), "MinimumPasswordAge"),
            Some("0")
        );
        assert_eq!(
            config.get_from(Some("System Access"), "MaximumPasswordAge"),
            Some("21")
        );
        assert_eq!(
            config.get_from(Some("System Access"), "LockoutBadCount"),
            Some("42")
        );
        assert_eq!(
            config.get_from(Some("System Access"), "ResetLockoutCount"),
            Some("10")
        );
    }

    #[test]
    fn test_search_and_replace_with_unordered_sid() {
        let path = Path::new(env!("CARGO_MANIFEST_DIR")).join("test/config.ini");
        let mut config = parse_config(&path).unwrap();

        let data = json!({
          "Privilege Rights": {
              "SeNetworkLogonRight": "*S-1-5-32-545,*S-1-5-32-544,*S-1-5-32-551,*S-1-1-0"
          }
        });
        let data = data.as_object().unwrap();
        let res = config_search_and_replace(&mut config, &Ini::new(), data).unwrap();
        assert!(res.errors.is_empty());

        assert_eq!(
            config
                .get_from(Some("Privilege Rights"), "SeNetworkLogonRight")
                .unwrap(),
            "*S-1-1-0,*S-1-5-32-544,*S-1-5-32-545,*S-1-5-32-551"
        );
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
        let _ = config_search_and_replace(&mut config, &Ini::new(), data);
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
    fn test_diff() {
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
        let report = config_search_and_replace(&mut config, &Ini::new(), data).unwrap();
        assert_eq!(
            report.diff,
            "\n System Access:\n    - MinimumPasswordLength=0\n    + MinimumPasswordLength=12\n\n    - NewAdministratorName=Administrator\n    + NewAdministratorName=Ferris"
        );
    }

    #[test]
    fn test_key_equal() {
        let res = key_equal("valid", "valid");
        assert!(res);
        let res = key_equal("invalid", "valid");
        assert!(!res);

        let res = key_equal(
            "invalid,*S-1-1-0,*S-1-5-32-544,*S-1-5-32-545,*S-1-5-32-551",
            "*S-1-1-0,*S-1-5-32-544,*S-1-5-32-545,*S-1-5-32-551",
        );
        assert!(!res);

        let res = key_equal(
            "*S-1-1-0,*S-1-5-32-544,*S-1-5-32-545,*S-1-5-32-551",
            "*S-1-5-32-551,*S-1-1-0,*S-1-5-32-545,*S-1-5-32-544",
        );
        assert!(res);
    }
}
