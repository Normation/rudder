// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021 Normation SAS

mod cli;
mod engine;
use crate::cli::Cli;
use engine::Engine;
use rudder_module_type::ProtocolResult;
use rudder_module_type::diff::diff;

use anyhow::{Context, Result, anyhow, bail};
use rudder_module_type::cfengine::called_from_agent;
use rudder_module_type::{
    CheckApplyResult, ModuleType0, ModuleTypeMetadata, Outcome, PolicyMode, ValidateResult,
    atomic_file_write::atomic_write, backup::Backup, parameters::Parameters, rudder_debug,
    run_module,
};
use serde::{Deserialize, Deserializer, Serialize};
use serde_json::Value;
use std::fmt::Display;
use std::{
    fs,
    fs::read_to_string,
    path::{Path, PathBuf},
};

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub struct TemplateParameters {
    /// Output file path
    path: PathBuf,
    /// Source template path
    #[serde(
        skip_serializing_if = "Option::is_none",
        deserialize_with = "deserialize_option_pathbuf"
    )]
    template_path: Option<PathBuf>,
    /// Inlined source template
    #[serde(
        skip_serializing_if = "Option::is_none",
        deserialize_with = "deserialize_option_string"
    )]
    template_string: Option<String>,
    /// Templating engine
    #[serde(default)]
    engine: Engine,
    /// Data to use for templating
    #[serde(
        skip_serializing_if = "Option::is_none",
        deserialize_with = "deserialize_data",
        default
    )]
    data: Option<Value>,
    /// Datastate file path
    #[serde(
        skip_serializing_if = "Option::is_none",
        deserialize_with = "deserialize_option_pathbuf"
    )]
    datastate_path: Option<PathBuf>,
    /// Controls output of diffs in the report
    #[serde(default = "default_as_true")]
    show_content: bool,
    #[serde(default)]
    report_file: Option<PathBuf>,
}

fn default_as_true() -> bool {
    true
}

fn deserialize_data<'de, D>(deserializer: D) -> Result<Option<Value>, D::Error>
where
    D: Deserializer<'de>,
{
    let value = Value::deserialize(deserializer)?;
    match value {
        Value::Null => Ok(None),
        Value::String(s) if s.is_empty() => Ok(None),
        Value::String(s) => serde_json::from_str(&s).map_err(serde::de::Error::custom),
        _ => Ok(Some(value)),
    }
}

fn deserialize_option_string<'de, D>(deserializer: D) -> Result<Option<String>, D::Error>
where
    D: Deserializer<'de>,
{
    let value: Option<String> = Option::deserialize(deserializer)?;
    Ok(value.and_then(|s| if s.is_empty() { None } else { Some(s) }))
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

/// Success cases for reporting
#[derive(Debug, Copy, Clone, PartialEq, Eq)]
enum TemplateOutcome {
    Success,
    Repaired,
    NonCompliant,
}

/// For now, until we have structured reporting.
struct TemplateReport {
    outcome: TemplateOutcome,
    message: String,
    diff: Option<String>,
}

impl TemplateReport {
    fn new(outcome: TemplateOutcome, message: String, diff: Option<String>) -> Self {
        TemplateReport {
            outcome,
            message,
            diff,
        }
    }
}

impl Display for TemplateReport {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.message)?;
        if let Some(diff) = &self.diff {
            write!(f, "\nFile diff:\n{}", diff)?;
        }
        Ok(())
    }
}

impl From<TemplateReport> for CheckApplyResult {
    fn from(t: TemplateReport) -> Self {
        match t.outcome {
            TemplateOutcome::Success => Ok(Outcome::success()),
            TemplateOutcome::Repaired => Ok(Outcome::repaired(t.to_string())),
            TemplateOutcome::NonCompliant => Err(anyhow!(t.to_string())),
        }
    }
}

// Module

struct Template {}

impl Template {
    fn new() -> Self {
        Template {}
    }

    fn check_apply_inner(
        mode: PolicyMode,
        p: &TemplateParameters,
        temporary_dir: &Path,
        backup_dir: &Path,
    ) -> Result<TemplateReport> {
        let output_file = &p.path;
        let output_file_d = output_file.display();

        let template_data = if let Some(ref v) = p.data {
            v
        } else if let Some(ref datastate_path) = p.datastate_path {
            let data = read_to_string(datastate_path).with_context(|| {
                format!(
                    "Failed to read datastate file: '{}'",
                    datastate_path.to_string_lossy()
                )
            })?;
            &serde_json::from_str(&data)?
        } else {
            bail!("Could not get datastate file")
        };

        let renderer = p.engine.renderer(temporary_dir, None)?;
        let output = renderer.render(
            p.template_path.as_deref(),
            p.template_string.as_deref(),
            template_data,
        )?;

        let already_present = output_file.exists();

        // Check if already correct
        let mut content = String::new();
        let already_correct = if already_present {
            content = read_to_string(output_file)
                .with_context(|| format!("Failed to read file {output_file_d}"))?;
            if content == output {
                true
            } else {
                rudder_debug!(
                    "Output file {} exists but has outdated content",
                    output_file_d
                );
                false
            }
        } else {
            rudder_debug!("Output file {output_file_d} does not exist");
            false
        };

        let reported_diff = compute_diff_or_warning(
            &content,
            &output,
            &output_file_d.to_string(),
            p.show_content,
        );

        let outcome = match (already_correct, mode) {
            (true, _) => {
                let report = format!("File '{output_file_d}' was already correct");
                TemplateReport::new(TemplateOutcome::Success, report, None)
            }
            (false, PolicyMode::Audit) => {
                let report = if already_present {
                    format!("File '{output_file_d}' is present but content is not up to date.")
                } else {
                    format!("Output file {output_file_d} does not exist")
                };
                TemplateReport::new(TemplateOutcome::NonCompliant, report, Some(reported_diff))
            }
            (false, PolicyMode::Enforce) => {
                // Backup current file
                if already_present {
                    backup_file(output_file, backup_dir)?;
                }

                // Write the file
                atomic_write(output_file, output.as_bytes())
                    .with_context(|| format!("Failed to write file {output_file_d}"))?;

                let source_file = p
                    .template_path
                    .as_ref()
                    .map(|p| format!(" (from {})", p.display()))
                    .unwrap_or_default();

                let report = if already_present {
                    format!("Replaced '{output_file_d}' content from template '{source_file}'")
                } else {
                    format!("Written new '{output_file_d}' from template '{source_file}'")
                };
                TemplateReport::new(TemplateOutcome::Repaired, report, Some(reported_diff))
            }
        };
        Ok(outcome)
    }
}

impl ModuleType0 for Template {
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
        // Parse as parameters type
        let parameters: TemplateParameters =
            serde_json::from_value(Value::Object(parameters.data.clone()))?;

        match (
            parameters.template_path.is_some(),
            parameters.template_string.is_some(),
        ) {
            (true, true) => bail!(
                "Only one of 'template_path' and 'template_src' can be provided '{}' and '{}'",
                parameters.template_string.unwrap(),
                parameters.template_path.unwrap().display()
            ),
            (false, false) => {
                bail!("Need one of 'template_path' and 'template_src'")
            }
            _ => (),
        }

        Ok(())
    }

    fn check_apply(&mut self, mode: PolicyMode, parameters: &Parameters) -> CheckApplyResult {
        assert!(self.validate(parameters).is_ok());
        let p: TemplateParameters = serde_json::from_value(Value::Object(parameters.data.clone()))?;

        let res =
            Self::check_apply_inner(mode, &p, &parameters.temporary_dir, &parameters.backup_dir);
        if let Some(r) = p.report_file {
            fs::write(
                r,
                match &res {
                    Ok(report) => report.to_string(),
                    Err(e) => e.to_string(),
                },
            )?;
        }
        res.and_then(|r| r.into())
    }
}
fn compute_diff_or_warning(
    content: &str,
    output: &str,
    output_file_d: &str,
    show_content: bool,
) -> String {
    if show_content {
        let reported_diff = diff(content, output);
        let max_reported_diff = 10_000;

        if reported_diff.len() > max_reported_diff {
            format!(
                "Changes to {output_file_d} could not be reported. The diff output exceeds the maximum size limit."
            )
        } else {
            reported_diff
        }
    } else {
        format!("The diff output is disabled. Changes to {output_file_d} are not being reported.")
    }
}

fn backup_file(output_file: &Path, backup_dir: &Path) -> Result<(), anyhow::Error> {
    let backup_file = backup_dir.join(Backup::BeforeEdit.backup_file(output_file));
    fs::copy(output_file, &backup_file)
        .with_context(|| format!("Failed to write file {}", backup_file.display()))?;

    Ok(())
}

pub fn entry() -> Result<(), anyhow::Error> {
    let promise_type = Template::new();

    if called_from_agent() {
        run_module(promise_type)
    } else {
        Cli::run()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_deserialize_data_from_json() {
        #[derive(Deserialize)]
        struct DataTest {
            #[serde(deserialize_with = "deserialize_data", default)]
            data: Option<Value>,
        }

        // Using raw data
        let json = r#"
{
    "data": {
        "name": "Bob",
        "age": 30
    }
}
"#;
        let r: DataTest = serde_json::from_str(json).unwrap();
        let data = r.data.unwrap();
        assert_eq!(data["name"], "Bob");
        assert_eq!(data["age"], 30);

        // Using inline JSON
        let json2 = r#"
{
    "data": "{\"name\": \"Bob\", \"age\": 30}"
}
"#;
        let r2: DataTest = serde_json::from_str(json2).unwrap();
        let data2 = r2.data.unwrap();
        assert_eq!(data2["name"], "Bob");
        assert_eq!(data2["age"], 30);

        // With empty data
        let json3 = r#"
{
    "data": ""
}
"#;
        let r3: DataTest = serde_json::from_str(json3).unwrap();
        assert_eq!(r3.data, None);
    }
}
