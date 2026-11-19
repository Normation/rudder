// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021-2025 Normation SAS

use std::io::Write;
use std::path::PathBuf;
use std::process::{Command, Stdio};

use crate::engine::TemplateEngine;
use anyhow::{Context, Result, bail};
use serde_json::Value;
use std::collections::HashMap;
use std::{fs, path::Path};

/// Agent modules directories, mirroring `configuration.modules_dir` in
/// `10_ncf_internals/configuration.cf`: the new location is used when it exists.
const MODULES_DIR: &str = "/opt/rudder/var/modules";
const LEGACY_MODULES_DIR: &str = "/var/rudder/cfengine-community/modules";

pub(crate) struct Jinja2Engine {
    python_interpreter: String,
    /// Where the user-provided `jinja2_custom.py` (custom filters and tests) is looked up.
    extensions_dir: PathBuf,
}

impl Jinja2Engine {
    pub fn new(python_version: Option<String>) -> Result<Self> {
        let python_interpreter = match python_version {
            Some(v) => v,
            None => detect_python_version().context("Could not get python version")?,
        };
        Ok(Jinja2Engine {
            python_interpreter,
            extensions_dir: default_extensions_dir(),
        })
    }
}

fn default_extensions_dir() -> PathBuf {
    let modules_dir = if Path::new(MODULES_DIR).is_dir() {
        MODULES_DIR
    } else {
        LEGACY_MODULES_DIR
    };
    Path::new(modules_dir).join("extensions")
}

impl TemplateEngine for Jinja2Engine {
    fn render(
        &self,
        template_path: Option<&Path>,
        template_string: Option<&str>,
        data: &Value,
    ) -> Result<String> {
        // Python prepends the script's directory to `sys.path`, ahead of the stdlib, and
        // Jinja2 resolves `{% include %}` relative to the template's directory. Both must
        // therefore live in a directory only we can write to (`TempDir` is created 0700):
        // in the shared, world-writable temp dir, any local user could plant a module
        // (e.g. `jinja2_custom.py`, `json.py`) or a template and get it run as root.
        let work_dir = tempfile::Builder::new()
            .prefix("rudder-jinja2-")
            .tempdir()
            .context("Failed to create a private directory for the rendering script")?;

        let template_path: PathBuf = match (template_path, template_string) {
            (Some(p), _) => p.to_path_buf(),
            (_, Some(s)) => {
                let p = work_dir.path().join("template.j2");
                fs::write(&p, s).context("Failed to write the template into a temporary file")?;
                p
            }
            // `validate` guarantees exactly one of the two sources is provided.
            _ => unreachable!(),
        };

        let template_script_path = work_dir.path().join("render.py");
        fs::write(&template_script_path, include_str!("jinja2/render.py"))
            .context("Failed to write the rendering script")?;

        let output = if cfg!(target_os = "linux") {
            let mut child = Command::new(&self.python_interpreter)
                .arg(&template_script_path)
                .arg("--extensions-dir")
                .arg(&self.extensions_dir)
                .arg(&template_path)
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
    fn it_loads_custom_filters_and_tests_from_the_extensions_dir() {
        let extensions_dir = tempfile::tempdir().unwrap();
        fs::write(
            extensions_dir.path().join("jinja2_custom.py"),
            "FILTERS = {'shout': lambda s: s.upper()}\nTESTS = {'odd': lambda n: n % 2 == 1}\n",
        )
        .unwrap();
        let engine = Jinja2Engine {
            python_interpreter: "python3".to_string(),
            extensions_dir: extensions_dir.path().to_path_buf(),
        };
        let out = engine
            .render(
                None,
                Some("{{ vars.a | shout }} {{ vars.n is odd }}"),
                &json!({"vars": {"a": "ferris", "n": 3}}),
            )
            .unwrap();
        assert_eq!(out, "FERRIS True");
    }

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
