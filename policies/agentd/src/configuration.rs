// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

/*!
 * Parse the service configuration file and provide a common structure around it
 */

use anyhow::{Context, Result};
use log::{error, warn};
use rudder_module_type::encoding::unicode_file_to_string;
use serde::Deserialize;
use serde_inline_default::serde_inline_default;
use std::collections::HashMap;
use std::fs::read_dir;
use std::path::Path;
use std::time::Duration;
use uuid::Uuid;

/// The main configuration object
#[derive(Deserialize, Debug, Eq, PartialEq)]
pub struct Configuration {
    // trigger port
    //trigger_listen: u16,
    /// One schedule for each command
    #[serde(flatten)]
    pub schedules: HashMap<String, ScheduleConfiguration>,
}

/// The scheduling of a specific command
#[serde_inline_default]
#[derive(Deserialize, Debug, Eq, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct ScheduleConfiguration {
    // Run as is handled by service manager
    //run_as: Option<String>,
    /// Command that will be started within the specified interval
    /// First start will happen every day at 00:00+interval_begin+hash(uuid)%(interval_end_interval_begin)
    /// All start will be separated by min(period, 24h)
    /// All scheduling is done in local time
    pub command: String,

    /// Duration between 2 command start (end time is ignored)
    #[serde_inline_default(Duration::from_mins(5))]
    #[serde(with = "humantime_serde")]
    pub period: Duration,

    /// Delay between 00:00:00 and the beginning of the command start interval (must be < interval_end)
    #[serde_inline_default(Duration::from_secs(0))]
    #[serde(with = "humantime_serde")]
    pub interval_begin: Duration,

    /// Delay between 00:00:00 and the end of the command start interval (must be < period)
    /// None means = period
    #[serde_inline_default(None)]
    #[serde(with = "humantime_serde")]
    pub interval_end: Option<Duration>,

    /// Maximum execution duration (will be killed after that)
    #[serde_inline_default(Duration::from_mins(60))]
    #[serde(with = "humantime_serde")]
    pub max_execution_duration: Duration,

    /// Maximum concurrent command execution (no more execution once it has been reached)
    #[serde_inline_default(1)]
    pub max_concurrent_executions: usize,
}

const DEFAULT_CONFIGURATION: &str = r#"
[agent_run]
command="rudder agent run -i"
"#;

/**
 * Try to be resilient in configuration parsing!
 *
 * Since this must be run on unattended nodes, it is important to not break the run of the agent itself.
 * - in the absence of configuration, there is a default one
 * - if the parsing is broken, same
 * - most values have default
 */
impl Configuration {
    /// Parse a configuration file and its overrides in a .d directory and return a configuration object
    pub fn from_path(path: &Path) -> Result<Self> {
        // list potential configuration path
        let mut path_list = vec![];
        if path.exists() {
            path_list.push(path.to_owned());
        }
        if let Ok(entries) = read_dir(path.with_added_extension("d")) {
            for entry in entries.flatten() {
                if entry.file_name().to_string_lossy().ends_with(".conf") {
                    path_list.push(entry.path());
                }
            }
        }
        if path_list.is_empty() {
            error!("No configuration file found, using a default one");
            Self::from_str(DEFAULT_CONFIGURATION, "Default configuration")
        } else {
            let mut this = Configuration {
                schedules: Default::default(),
            };
            let mut error_count = 0;
            for path in path_list.iter() {
                match Self::from_file(path) {
                    Ok(conf) => this.merge(conf, path),
                    Err(e) => {
                        error!("Ignoring {} because of error {}", path.display(), e);
                        error_count += 1;
                    }
                }
            }
            if error_count > 0 && this.schedules.is_empty() {
                warn!(
                    "After {} error(s), no configuration was found, using the default one",
                    error_count
                );
                Self::from_str(DEFAULT_CONFIGURATION, "Default configuration")
            } else {
                Ok(this)
            }
        }
    }

    /// Parse a single configuration file and return a configuration object
    pub fn from_file(path: &Path) -> Result<Self> {
        let path_display = path.display();
        let content = unicode_file_to_string(path)
            .with_context(|| format!("Configuration file {} could not be read", path_display))?;
        Configuration::from_str(&content, &path_display.to_string())
    }
    /// Parse a configuration string and return a configuration object
    pub fn from_str(content: &str, source_name: &str) -> Result<Self> {
        // parse with serde
        toml::from_str(content)
            .with_context(|| format!("Configuration from '{}' is invalid", source_name))
    }

    /// Merge this configuration with another one
    /// "other" takes precedence in overriding, but overrides produce a warning
    fn merge(&mut self, other: Self, new_path: &Path) {
        let Configuration { schedules } = other;
        // manually catch overrides
        for key in schedules.keys() {
            if self.schedules.contains_key(key) {
                warn!(
                    "Configuration item [{}] from {} overrides the previous one",
                    key,
                    new_path.display()
                );
            }
        }
        self.schedules.extend(schedules);
    }
}

/// Read agent uuid file
pub fn read_uuid(path: &Path) -> Result<Uuid> {
    let content = unicode_file_to_string(path)
        .with_context(|| format!("UUID file {} could not be read", path.display()))?;
    // parse with serde
    let uuid = Uuid::parse_str(content.trim())
        .with_context(|| format!("UUID file {} invalid", path.display()))?;
    Ok(uuid)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_configuration_err() {
        let conf = Configuration::from_str(
            r#"[agent]
period = "120s"
"#,
            "test_str",
        );
        assert!(conf.is_err(), "A command is mandatory");

        let conf = Configuration::from_str(
            r#"command = "echo ok"
period = "120s"
"#,
            "test_str",
        );
        assert!(conf.is_err(), "Schedules must be in a section");
    }

    #[test]
    fn test_configuration_ok() {
        let conf = Configuration::from_str(
            r#"[agent]
command = "echo ok"
period = "120s"
"#,
            "test_str",
        );
        // test parsing
        assert!(conf.is_ok(), "Parsing should be ok");
        let conf = conf.unwrap();
        assert_eq!(conf.schedules.len(), 1, "Test with one schedule");
        let conf1 = conf.schedules.get("agent");
        assert!(conf1.is_some(), "Test with agent schedule");
        // test defaults
        assert_eq!(
            conf1.unwrap(),
            &ScheduleConfiguration {
                command: "echo ok".to_string(),
                period: Duration::from_secs(120),
                interval_begin: Duration::from_secs(0),
                interval_end: None,
                max_execution_duration: Duration::from_mins(60),
                max_concurrent_executions: 1,
            },
            "Test correct parsing and default"
        );
    }

    #[test]
    fn test_crlf_ok() {
        let conf = Configuration::from_str(
            "[agent]\r\ncommand = \"echo ok\"\r\nperiod = \"120s\"\r\n",
            "test_str",
        );
        assert!(conf.is_ok(), "CRLF Parsing should be ok");
        let conf = conf.unwrap();
        let conf1 = conf.schedules.get("agent");
        assert_eq!(
            conf1.unwrap().command,
            "echo ok".to_string(),
            "CRLF should be ignored"
        );
    }
}
