// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021 Normation SAS

use std::{
    fs,
    fs::read_to_string,
    path::{Path, PathBuf},
};

use anyhow::{bail, Context, Result};
use minijinja::UndefinedBehavior;
use rudder_module_type::{
    backup::Backup, parameters::Parameters, rudder_debug, run, CheckApplyResult, ModuleType0,
    ModuleTypeMetadata, Outcome, PolicyMode, ValidateResult,
};
use serde::{Deserialize, Serialize};
use serde_json::Value;

// Configuration

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize, Copy)]
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
            (Some(ref p), _) => read_to_string(p)
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
                (Some(ref p), _) => mustache::compile_path(p)
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
        let already_correct = if already_present {
            let content = read_to_string(output_file)
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

        let outcome = match (already_correct, mode) {
            (true, _) => Outcome::success(),
            (false, PolicyMode::Audit) => {
                if already_present {
                    bail!("Output file {output_file_d} is present but content is not up to date")
                } else {
                    bail!("Output file {output_file_d} does not exist")
                }
            }
            (false, PolicyMode::Enforce) => {
                // Backup current file
                if already_present {
                    let backup_file = parameters
                        .backup_dir
                        .join(Backup::BeforeEdit.backup_file(output_file));
                    fs::copy(output_file, &backup_file).with_context(|| {
                        format!("Failed to write file {}", backup_file.display())
                    })?;
                }

                // Write file
                fs::write(output_file, output.as_bytes())
                    .with_context(|| format!("Failed to write file {output_file_d}"))?;

                // Repaired message
                let source_file = p
                    .template_path
                    .as_ref()
                    .map(|p| format!(" (from {})", p.display()))
                    .unwrap_or_else(|| "".to_string());
                if already_present {
                    Outcome::repaired(format!(
                        "Replaced {output_file_d} content from template{source_file}",
                    ))
                } else {
                    Outcome::repaired(format!(
                        "Written new {output_file_d} from template{source_file}",
                    ))
                }
            }
        };
        Ok(outcome)
    }
}

// Start runner

fn main() -> Result<(), anyhow::Error> {
    let directory_promise_type = Template {};
    // Run the promise executor
    run(directory_promise_type)
}
