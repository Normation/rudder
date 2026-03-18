// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

/*!
 * Parse the service configuration file and provide a common structure around it
 */

use anyhow::{Result, bail};
use serde::Deserialize;
use serde_inline_default::serde_inline_default;
use skip_bom::{BomType, SkipEncodingBom};
use std::collections::HashMap;
use std::fs::File;
use std::io::Read;
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

/// Read a Unicode file to a string.
/// Accept UTF8 with BOM, UTF8 without BOM, UTF16LE with BOM
// Other formats could be added easily.
fn unicode_file_to_string(path: &Path) -> Result<String> {
    let name = path.as_os_str().to_string_lossy();
    if !path.exists() {
        bail!("File {} does not exist", name);
    }

    let file = match File::open(path) {
        Ok(f) => f,
        Err(e) => bail!("File {} could not be open: {}", name, e),
    };

    let mut reader = SkipEncodingBom::new(BomType::all(), file);
    let mut buf = Default::default();
    if let Err(e) = reader.read_to_end(&mut buf) {
        bail!("Error reading file {}: {}", name, e);
    }

    match reader.bom_found() {
        Some(Some(BomType::UTF16LE)) => match buf.as_chunks::<2>() {
            (chunks, []) => {
                match char::decode_utf16(chunks.iter().copied().map(u16::from_le_bytes))
                    .collect::<Result<_, _>>()
                {
                    Ok(s) => Ok(s),
                    Err(e) => bail!("Invalid UTF16 file {}: {}", name, e),
                }
            }
            _ => bail!("Invalid UTF16 file {}: missing byte", name),
        },
        _ => match String::from_utf8(buf) {
            Ok(s) => Ok(s),
            Err(e) => bail!("Invalid UTF8 file {}: {}", name, e),
        },
    }
}

impl Configuration {
    pub fn from_file(path: &Path) -> Result<Self> {
        let content = match unicode_file_to_string(path) {
            Ok(s) => s,
            Err(e) => {
                bail!("Configuration file could not be read: {}", e);
            }
        };
        Configuration::from_str(&content, &path.as_os_str().to_string_lossy())
    }

    pub fn from_str(content: &str, source_name: &str) -> Result<Self> {
        // parse with serde
        match toml::from_str(content) {
            Ok(c) => Ok(c),
            Err(e) => {
                bail!("Configuration from '{}' is invalid: {}", source_name, e);
            }
        }
    }
}

pub fn read_uuid(path: &Path) -> Result<Uuid> {
    let content = match unicode_file_to_string(path) {
        Ok(s) => s,
        Err(e) => {
            bail!("UUID file could not be read: {}", e);
        }
    };
    // parse with serde
    let uuid = match Uuid::parse_str(content.trim()) {
        Ok(c) => c,
        Err(e) => {
            bail!(
                "UUID file {} invalid: {}",
                path.as_os_str().to_string_lossy(),
                e
            );
        }
    };
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

    #[test]
    fn test_unicode() {
        let test_value = "# éoùçà\n".to_string();
        let res = unicode_file_to_string(Path::new("src/test/utf8-nobom.txt"));
        assert!(res.is_ok(), "Test utf8-nobom ok");
        assert_eq!(res.unwrap(), test_value, "Test utf8-nobom value");
        let res = unicode_file_to_string(Path::new("src/test/utf8-bom.txt"));
        assert!(res.is_ok(), "Test utf8-bom ok");
        assert_eq!(res.unwrap(), test_value, "Test utf8-bom value");
        let res = unicode_file_to_string(Path::new("src/test/utf16-bom.txt"));
        assert!(res.is_ok(), "Test utf16-bom ok");
        assert_eq!(res.unwrap(), test_value, "Test utf16-bom value");
        let res = unicode_file_to_string(Path::new("src/test/utf16-nobom.txt"));
        assert!(res.is_err(), "Test utf16-nobom err");
    }
}
