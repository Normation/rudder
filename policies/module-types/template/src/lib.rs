// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021 Normation SAS

mod cli;
pub mod minijinja_filters;
use crate::cli::Cli;
use clap::ValueEnum;
use core::panic;
use rudder_module_type::ProtocolResult;
use similar::TextDiff;
use std::collections::HashMap;
use std::io::Write;
use std::process::{Command, Stdio};
use tempfile::{NamedTempFile, TempPath};

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
    Minijinja,
    Jinja2,
}

impl Default for Engine {
    fn default() -> Self {
        Self::Minijinja
    }
}

impl Engine {
    fn minijinja(
        template_path: Option<&Path>,
        template_src: Option<String>,
        data: Value,
    ) -> Result<String> {
        let (template, template_name) = match (&template_path, template_src) {
            (Some(p), _) => (
                read_to_string(p)
                    .with_context(|| format!("Failed to read template {}", p.display()))?,
                p.file_name().unwrap().to_string_lossy().into_owned(),
            ),
            (_, Some(s)) => (s, "template".to_string()),
            _ => unreachable!(),
        };
        // We need to create the Environment even for one template
        let mut env = minijinja::Environment::new();
        // Fail on non-defined values, even in iteration
        env.set_undefined_behavior(UndefinedBehavior::Strict);
        minijinja_contrib::add_to_environment(&mut env);
        env.add_template(&template_name, &template)?;
        env.add_filter("b64encode", minijinja_filters::b64encode);
        env.add_filter("b64decode", minijinja_filters::b64decode);
        env.add_filter("basename", minijinja_filters::basename);
        env.add_filter("dirname", minijinja_filters::dirname);
        env.add_filter("urldecode", minijinja_filters::urldecode);
        env.add_filter("hash", minijinja_filters::hash);
        env.add_filter("quote", minijinja_filters::quote);
        env.add_filter("regex_escape", minijinja_filters::regex_escape);
        env.add_filter("regex_replace", minijinja_filters::regex_replace);
        let tmpl = env.get_template(&template_name).unwrap();
        Ok(tmpl.render(data)?)
    }

    fn jinja2(
        template_path: Option<&Path>,
        template_src: Option<String>,
        data: Value,
        temporary_dir: &Path,
        python: &str,
    ) -> Result<String> {
        let named: TempPath;
        let template_path = match (&template_path, template_src) {
            (Some(p), _) => p.to_str().unwrap(),
            (_, Some(s)) => {
                let mut tmp_file = NamedTempFile::new().expect("Failed to create tempfile");
                tmp_file
                    .write_all(s.to_string().as_bytes())
                    .expect("Failed to write template in tempfile");
                named = tmp_file.into_temp_path();
                named.to_str().unwrap()
            }
            _ => unreachable!(),
        };

        let templating_script_content = include_str!("../jinja2-templating.py");
        let script_name = "jinja2-templating.py";

        let mut path = PathBuf::from(temporary_dir);
        path.push(script_name);
        let templating_script_path = path.to_str().unwrap();

        if !fs::exists(templating_script_path)? {
            fs::write(templating_script_path, templating_script_content)?;
            #[cfg(target_family = "unix")]
            {
                use std::os::unix::fs::PermissionsExt;
                let perms = fs::Permissions::from_mode(0o755);
                fs::set_permissions(templating_script_path, perms)?;
            }
            #[cfg(target_family = "windows")]
            {
                let mut perms = fs::metadata(templating_script_path)?.permissions();
                perms.set_readonly(false);
                fs::set_permissions(templating_script_path, perms)?;
            }
        }

        let output = if cfg!(target_os = "linux") {
            let mut child = Command::new(python)
                .args([templating_script_path, template_path])
                .stdin(Stdio::piped())
                .stdout(Stdio::piped())
                .spawn()
                .unwrap_or_else(|_| panic!("Failed to execute {}", templating_script_path));

            let stdin = child.stdin.as_mut().unwrap();
            stdin
                .write_all(data.to_string().as_bytes())
                .expect("Failed to write to stdin");

            let output_info = child.wait_with_output()?;
            let output = String::from_utf8_lossy(&output_info.stdout).to_string();
            if !output_info.status.success() {
                bail!("Error {} failed with : {}", script_name, output);
            }
            output
        } else {
            bail!("Jinja2 templating engine is not supported on Windows")
        };
        Ok(output)
    }

    fn mustache(
        template_path: Option<&Path>,
        template_src: Option<String>,
        data: Value,
    ) -> Result<String> {
        let template =
            match (&template_path, template_src) {
                (_, Some(ref s)) if !s.is_empty() => mustache::compile_str(s)
                    .with_context(|| "Failed to compile mustache template")?,
                (Some(p), _) => mustache::compile_path(p)
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
    /// Datastate file path
    #[serde(skip_serializing_if = "Option::is_none")]
    datastate_path: Option<PathBuf>,
    /// Controls output of diffs in the report
    #[serde(default = "default_as_true")]
    show_content: bool,
}

fn default_as_true() -> bool {
    true
}

// Module

struct Template {
    python_version: Option<Result<String>>,
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

        match (parameters.template_path, parameters.template_src) {
            (None, None) => bail!("Need one of 'template_path' and 'template_src'"),
            _ => {}
        }

        Ok(())
    }

    fn check_apply(&mut self, mode: PolicyMode, parameters: &Parameters) -> CheckApplyResult {
        assert!(self.validate(parameters).is_ok());
        let p: TemplateParameters = serde_json::from_value(Value::Object(parameters.data.clone()))?;
        let output_file = &p.path;
        let output_file_d = output_file.display();

        let data = match (p.data.clone(), p.datastate_path) {
            (Value::String(s), _) if !s.is_empty() => p.data,
            (_, Some(ref datastate_path)) => {
                let datastate = read_to_string(datastate_path).with_context(|| {
                    format!(
                        "Failed to read datastate file: '{}'",
                        datastate_path.to_string_lossy()
                    )
                })?;
                serde_json::from_str(&datastate)?
            }
            _ => bail!("Could not get datastate file"),
        };

        let output = match p.engine {
            Engine::Mustache => Engine::mustache(p.template_path.as_deref(), p.template_src, data)?,
            Engine::Minijinja => {
                Engine::minijinja(p.template_path.as_deref(), p.template_src, data)?
            }
            Engine::Jinja2 => {
                // Only detect if necessary
                if self.python_version.is_none() {
                    self.python_version = Some(get_python_version());
                }

                let python_bin = match self.python_version {
                    Some(Ok(ref v)) => v,
                    Some(Err(ref e)) => bail!("Could not get python version: {}", e),
                    None => unreachable!(),
                };
                Engine::jinja2(
                    p.template_path.as_deref(),
                    p.template_src,
                    data,
                    parameters.temporary_dir.as_path(),
                    python_bin,
                )?
            }
        };

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
                    "Changes to {output_file_d} could not be reported. The diff output exceeds the maximum size limit."
                )
            } else {
                reported_diff
            }
        } else {
            format!(
                "Changes to {output_file_d} could not be reported. The diff output is disabled."
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
    let promise_type = Template {
        python_version: None,
    };

    if called_from_agent() {
        run_module(promise_type)
    } else {
        Cli::run()
    }
}

fn get_python_version() -> Result<String> {
    let args = ["-c", "import jinja2"];
    let python_versions = HashMap::from([
        ("python3", "- python3 -c import jinja2"),
        ("python2", "- python2 -c import jinja2"),
        ("python", "- python -c import jinja2"),
    ]);
    let mut used_cmd: Vec<&str> = vec![];
    for (version, cmd) in python_versions {
        let status = Command::new(version).args(args).status();
        if status.is_ok() && status?.success() {
            return Ok(version.to_string());
        }
        used_cmd.push(cmd);
    }
    bail!(
        "Failed to locate a Python interpreter with Jinja2 installed. Tried the following commands:\n{}",
        used_cmd.join("\n")
    )
}
