// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021-2025 Normation SAS

use std::io::Write;
use std::process::{Command, Stdio};
use tempfile::{NamedTempFile, TempPath};

use crate::engine::TemplateEngine;
use anyhow::{Context, Result, bail};
use serde_json::Value;
use std::collections::HashMap;
use std::{
    fs,
    path::{Path, PathBuf},
};

pub(crate) struct Jinja2Engine {
    python_interpreter: String,
    temporary_dir: PathBuf,
}

impl Jinja2Engine {
    pub fn new(temporary_dir: PathBuf, python_version: Option<String>) -> Result<Self> {
        let python_interpreter = match python_version {
            Some(v) => v,
            None => detect_python_version().context("Could not get python version")?,
        };
        Ok(Jinja2Engine {
            python_interpreter,
            temporary_dir,
        })
    }
}

impl TemplateEngine for Jinja2Engine {
    fn render(
        &self,
        template_path: Option<&Path>,
        template_src: Option<&str>,
        data: &Value,
    ) -> Result<String> {
        let named: TempPath;
        let template_path = match (&template_path, template_src) {
            (Some(p), _) => p.to_str().unwrap(),
            (_, Some(s)) => {
                let mut tmp_file = NamedTempFile::new().expect("Failed to create tempfile");
                tmp_file
                    .write_all(s.as_bytes())
                    .expect("Failed to write template in tempfile");
                named = tmp_file.into_temp_path();
                named.to_str().unwrap()
            }
            _ => unreachable!(),
        };

        let templating_script_content = include_str!("jinja2/render.py");
        let script_name = "render-jinja2.py";

        let mut path = PathBuf::from(&self.temporary_dir);
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
            let mut child = Command::new(&self.python_interpreter)
                .args([templating_script_path, template_path])
                .stdin(Stdio::piped())
                .stdout(Stdio::piped())
                .stderr(Stdio::piped())
                .spawn()
                .unwrap_or_else(|_| panic!("Failed to execute {}", templating_script_path));

            let stdin = child.stdin.as_mut().unwrap();
            stdin
                .write_all(data.to_string().as_bytes())
                .expect("Failed to write to stdin");

            let output_info = child.wait_with_output()?;
            if !output_info.status.success() {
                let output = String::from_utf8_lossy(&output_info.stderr).to_string();
                bail!("Error {} failed with : {}", script_name, output);
            }
            String::from_utf8_lossy(&output_info.stdout).to_string()
        } else {
            bail!("Jinja2 templating engine is not supported on Windows")
        };
        Ok(output)
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
