// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021 Normation SAS

mod cli;
use crate::cli::Cli;
use clap::ValueEnum;
use similar::TextDiff;

use std::{
    fs,
    fs::read_to_string,
    path::{Path, PathBuf},
};

use anyhow::{Context, Result, bail};
use minijinja::UndefinedBehavior;
use rudder_module_type::cfengine::called_from_agent;
use rudder_module_type::{
    CheckApplyResult, ModuleType0, ModuleTypeMetadata, Outcome, PolicyMode, ValidateResult,
    backup::Backup, parameters::Parameters, rudder_debug, run_module,
};
use serde::{Deserialize, Serialize};
use serde_json::Value;
// Configuration

#[derive(Debug, Copy, Clone, PartialEq, Eq, Serialize, Deserialize, PartialOrd, Ord, ValueEnum)]
#[serde(rename_all = "snake_case")]
pub enum Engine {
    Mustache,
    MiniJinja,
}

impl Default for Engine {
    fn default() -> Self {
        Self::Mustache
    }
}

impl Engine {
    fn render(
        self,
        template_path: Option<&Path>,
        template_src: Option<String>,
        data: Value,
    ) -> Result<String> {
        Ok(match self {
            Engine::Mustache => Self::mustache(template_path, template_src, data)?,
            Engine::MiniJinja => Self::mini_jinja(template_path, template_src, data)?,
        })
    }

    fn mini_jinja(
        template_path: Option<&Path>,
        template_src: Option<String>,
        data: Value,
    ) -> Result<String> {
        let template = match (&template_path, template_src) {
            (Some(p), _) => read_to_string(p)
                .with_context(|| format!("Failed to read template {}", p.display()))?,
            (_, Some(s)) => s,
            _ => unreachable!(),
        };

        // We need to create the Environment even for one template
        let mut env = minijinja::Environment::new();
        // Fail on non-defined values, even in iteration
        env.set_undefined_behavior(UndefinedBehavior::Strict);
        env.add_template("rudder", &template)?;
        let tmpl = env.get_template("rudder").unwrap();
        Ok(tmpl.render(data)?)
    }

    fn mustache(
        template_path: Option<&Path>,
        template_src: Option<String>,
        data: Value,
    ) -> Result<String> {
        let template =
            match (&template_path, template_src) {
                (Some(p), _) => mustache::compile_path(p)
                    .with_context(|| "Failed to compile mustache template")?,
                (_, Some(ref s)) => mustache::compile_str(s)
                    .with_context(|| "Failed to compile mustache template")?,
                _ => unreachable!(),
            };
        template
            .render_to_string(&data)
            .with_context(|| "Rendering mustache template")
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub struct TemplateParameters {
    /// Output file path
    path: PathBuf,
    /// Source template path
    #[serde(skip_serializing_if = "Option::is_none")]
    template_path: Option<PathBuf>,
    /// Inlined source template
    #[serde(skip_serializing_if = "Option::is_none")]
    template_src: Option<String>,
    /// Templating engine
    #[serde(default)]
    engine: Engine,
    /// Data to use for templating
    #[serde(default)]
    data: Value,
    /// Controls output of diffs in the report
    #[serde(default = "default_as_true")]
    show_content: bool,
}

fn default_as_true() -> bool {
    true
}

// Module

struct Template {}

impl ModuleType0 for Template {
    fn metadata(&self) -> ModuleTypeMetadata {
        let meta = include_str!("../rudder_module_type.yml");
        let docs = include_str!("../README.md");
        ModuleTypeMetadata::from_metadata(meta)
            .expect("invalid metadata")
            .documentation(docs)
    }

    fn validate(&self, parameters: &Parameters) -> ValidateResult {
        // Parse as parameters type
        let parameters: TemplateParameters =
            serde_json::from_value(Value::Object(parameters.data.clone()))?;

        match (
            parameters.template_path.is_some(),
            parameters.template_src.is_some(),
        ) {
            (true, true) => bail!("Only one of 'template_path' and 'template_src' can be provided"),
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
        let output_file = &p.path;
        let output_file_d = output_file.display();

        // Compute output
        let output = p
            .engine
            .render(p.template_path.as_deref(), p.template_src, p.data)?;

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

        let reported_diff = if p.show_content {
            let reported_diff = diff(content, output.clone());
            let max_reported_diff = 10_000;

            if reported_diff.len() > max_reported_diff {
                format!(
                    "Changes to {} could not be reported. The diff output exceeds the maximum size limit.",
                    output_file_d
                )
            } else {
                reported_diff
            }
        } else {
            format!(
                "Changes to {} could not be reported. The diff output is disabled.",
                output_file_d
            )
        };

        let outcome = match (already_correct, mode) {
            (true, _) => Outcome::success(),
            (false, PolicyMode::Audit) => {
                if already_present {
                    bail!(
                        "Output file {output_file_d} is present but content is not up to date. diff: {reported_diff}"
                    )
                } else {
                    bail!("Output file {output_file_d} does not exist")
                }
            }
            (false, PolicyMode::Enforce) => {
                // Backup current file
                if already_present {
                    backup_file(output_file, &parameters.backup_dir)?;
                }

                // Write file
                fs::write(output_file, output.as_bytes())
                    .with_context(|| format!("Failed to write file {output_file_d}"))?;

                let source_file = p
                    .template_path
                    .as_ref()
                    .map(|p| format!(" (from {})", p.display()))
                    .unwrap_or_default();

                if already_present {
                    Outcome::repaired(format!(
                        "Replaced {output_file_d} content from template {source_file} diff: {reported_diff}",
                    ))
                } else {
                    Outcome::repaired(format!(
                        "Written new {output_file_d} from template {source_file} diff: {reported_diff}",
                    ))
                }
            }
        };
        Ok(outcome)
    }
}

pub fn diff(old: String, new: String) -> String {
    let diff = TextDiff::from_lines(&old, &new);
    let mut unified = diff.unified_diff();
    unified.context_radius(3).header("old", "new").to_string()
}

fn backup_file(output_file: &Path, backup_dir: &Path) -> Result<(), anyhow::Error> {
    let backup_file = backup_dir.join(Backup::BeforeEdit.backup_file(output_file));
    fs::copy(output_file, &backup_file)
        .with_context(|| format!("Failed to write file {}", backup_file.display()))?;

    Ok(())
}

pub fn entry() -> Result<(), anyhow::Error> {
    let promise_type = Template {};

    if called_from_agent() {
        run_module(promise_type)
    } else {
        Cli::run()
    }
}
