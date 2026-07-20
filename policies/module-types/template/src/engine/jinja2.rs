// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021-2025 Normation SAS

use std::io::Write;
use std::process::{Command, Stdio};
use tempfile::{NamedTempFile, TempPath};

use crate::engine::TemplateEngine;
use anyhow::{Context, Result, bail};
use serde_json::Value;
use std::collections::HashMap;
use std::{fs, path::Path};

pub(crate) struct Jinja2Engine {
    python_interpreter: String,
}

impl Jinja2Engine {
    pub fn new(python_version: Option<String>) -> Result<Self> {
        let python_interpreter = match python_version {
            Some(v) => v,
            None => detect_python_version().context("Could not get python version")?,
        };
        Ok(Jinja2Engine { python_interpreter })
    }
}

impl TemplateEngine for Jinja2Engine {
    fn render(
        &self,
        template_path: Option<&Path>,
        template_string: Option<&str>,
        data: &Value,
    ) -> Result<String> {
        let named: TempPath;
        // `validate` guarantees exactly one of the two sources is provided.
        let template_path: &Path = match (&template_path, template_string) {
            (Some(p), _) => p,
            (_, Some(s)) => {
                let mut tmp_file =
                    NamedTempFile::new().context("Failed to create a temporary template file")?;
                tmp_file
                    .write_all(s.as_bytes())
                    .context("Failed to write the template into a temporary file")?;
                named = tmp_file.into_temp_path();
                &named
            }
            _ => unreachable!(),
        };

        let tmp_script =
            NamedTempFile::new().context("Failed to create a temporary rendering script")?;
        let template_script_path = tmp_script.into_temp_path();
        let templating_script_content = include_str!("jinja2/render.py");

        fs::write(&template_script_path, templating_script_content)?;
        #[cfg(target_family = "unix")]
        {
            use std::os::unix::fs::PermissionsExt;
            let perms = fs::Permissions::from_mode(0o755);
            fs::set_permissions(&template_script_path, perms)?;
        }
        #[cfg(target_family = "windows")]
        {
            let mut perms = fs::metadata(&template_script_path)?.permissions();
            perms.set_readonly(false);
            fs::set_permissions(&template_script_path, perms)?;
        }

        let output = if cfg!(target_os = "linux") {
            let mut child = Command::new(&self.python_interpreter)
                .arg(&template_script_path)
                .arg(template_path)
                .stdin(Stdio::piped())
                .stdout(Stdio::piped())
                .stderr(Stdio::piped())
                .spawn()
                .with_context(|| {
                    format!(
                        "Failed to run the Python interpreter '{}' on {}",
                        self.python_interpreter,
                        template_script_path.display()
                    )
                })?;

            // Cannot fail, stdin is piped above.
            let stdin = child.stdin.as_mut().expect("stdin was piped");
            stdin
                .write_all(data.to_string().as_bytes())
                .context("Failed to pass the template data to the rendering script")?;

            let output_info = child.wait_with_output()?;
            if !output_info.status.success() {
                let output = String::from_utf8_lossy(&output_info.stderr).to_string();
                bail!(
                    "Error {} failed with : {}",
                    template_script_path.display(),
                    output
                );
            }
            String::from_utf8_lossy(&output_info.stdout).to_string()
        } else {
            bail!("Jinja2 templating engine is not supported on Windows")
        };
        Ok(output)
    }
}

#[cfg(all(test, target_os = "linux"))]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn it_fails_gracefully_when_the_interpreter_is_missing() {
        let engine = Jinja2Engine::new(Some("definitely-not-a-python-interpreter".to_string()))
            .expect("an explicit interpreter is never probed");
        let err = engine
            .render(None, Some("{{ vars.a }}"), &json!({"vars": {"a": 1}}))
            .unwrap_err();
        assert!(
            err.to_string()
                .contains("definitely-not-a-python-interpreter"),
            "unexpected error: {err}"
        );
    }
}

fn detect_python_version() -> Result<String> {
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
