// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021-2025 Normation SAS

use std::io::{Read, Write};
use std::process::{Command, Stdio};
use std::thread;
use std::time::{Duration, Instant};
use tempfile::{NamedTempFile, TempPath};

use crate::engine::{Mode, TemplateEngine};
use anyhow::{Context, Result, bail};
use serde_json::Value;
use std::collections::HashMap;
use std::{fs, path::Path};

/// Wall-clock timeout for a single jinja2 render in sandboxed mode. The engine
/// shells out to Python and has no way to cap compute from inside, so this
/// bounds runaway or untrusted templates instead of letting them hang the agent.
const JINJA2_SANDBOX_TIMEOUT: Duration = Duration::from_secs(3);
/// Timeout for unrestricted (trusted) renders: only catches a hung render.
const JINJA2_UNRESTRICTED_TIMEOUT: Duration = Duration::from_secs(10);

pub(crate) struct Jinja2Engine {
    python_interpreter: String,
    sandbox_timeout: Duration,
    unrestricted_timeout: Duration,
}

impl Jinja2Engine {
    pub fn new(python_version: Option<String>) -> Result<Self> {
        let python_interpreter = match python_version {
            Some(v) => v,
            None => detect_python_version().context("Could not get python version")?,
        };
        Ok(Jinja2Engine {
            python_interpreter,
            sandbox_timeout: JINJA2_SANDBOX_TIMEOUT,
            unrestricted_timeout: JINJA2_UNRESTRICTED_TIMEOUT,
        })
    }
}

impl TemplateEngine for Jinja2Engine {
    fn render(
        &self,
        template_path: Option<&Path>,
        template_string: Option<&str>,
        data: &Value,
        mode: Mode,
    ) -> Result<String> {
        let is_inline = template_path.is_none();
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
            NamedTempFile::new().context("Failed to create temporary file for render script")?;
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
            let mut cmd = Command::new(&self.python_interpreter);
            cmd.arg(&template_script_path).arg(template_path);
            if mode.is_sandboxed() {
                cmd.arg("--sandboxed");
            }
            if is_inline {
                cmd.arg("--inline");
            }
            let mut child = cmd
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

            // Feed the data on stdin and drain stdout/stderr from threads, so a
            // large template output cannot deadlock against a full pipe while we
            // wait for the process.
            let mut stdin = child.stdin.take().expect("stdin was piped");
            let payload = data.to_string();
            let stdin_writer = thread::spawn(move || stdin.write_all(payload.as_bytes()));
            let mut stdout_pipe = child.stdout.take().expect("stdout was piped");
            let stdout_reader = thread::spawn(move || {
                let mut buf = Vec::new();
                let _ = stdout_pipe.read_to_end(&mut buf);
                buf
            });
            let mut stderr_pipe = child.stderr.take().expect("stderr was piped");
            let stderr_reader = thread::spawn(move || {
                let mut buf = Vec::new();
                let _ = stderr_pipe.read_to_end(&mut buf);
                buf
            });

            // Bound the run so an untrusted or runaway template cannot hang the
            // agent forever.
            let timeout = if mode.is_sandboxed() {
                self.sandbox_timeout
            } else {
                self.unrestricted_timeout
            };
            let start = Instant::now();
            let status = loop {
                if let Some(status) = child.try_wait()? {
                    break status;
                }
                if start.elapsed() >= timeout {
                    let _ = child.kill();
                    let _ = child.wait();
                    bail!("Jinja2 rendering timed out after {}s", timeout.as_secs());
                }
                thread::sleep(Duration::from_millis(100));
            };

            let _ = stdin_writer.join();
            let stdout = stdout_reader.join().unwrap_or_default();
            let stderr = stderr_reader.join().unwrap_or_default();

            if !status.success() {
                let output = String::from_utf8_lossy(&stderr).to_string();
                bail!(
                    "Error {} failed with : {}",
                    template_script_path.display(),
                    output
                );
            }
            String::from_utf8_lossy(&stdout).to_string()
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

#[cfg(all(test, target_os = "linux"))]
mod tests {
    use super::*;
    use serde_json::json;

    fn engine(sandbox_timeout: Duration) -> Jinja2Engine {
        let python_interpreter = detect_python_version().expect(
            "no Python interpreter with jinja2 available; \
             install jinja2 to run the template module tests",
        );
        Jinja2Engine {
            python_interpreter,
            sandbox_timeout,
            unrestricted_timeout: JINJA2_UNRESTRICTED_TIMEOUT,
        }
    }

    #[test]
    fn it_fails_gracefully_when_the_interpreter_is_missing() {
        let engine = Jinja2Engine::new(Some("definitely-not-a-python-interpreter".to_string()))
            .expect("an explicit interpreter is never probed");
        let err = engine
            .render(
                None,
                Some("{{ vars.a }}"),
                &json!({"vars": {"a": 1}}),
                Mode::Sandboxed,
            )
            .unwrap_err();
        assert!(
            err.to_string()
                .contains("definitely-not-a-python-interpreter"),
            "unexpected error: {err}"
        );
    }

    #[test]
    fn test_jinja2_renders_basic() {
        let engine = engine(JINJA2_SANDBOX_TIMEOUT);
        let out = engine
            .render(
                None,
                Some("Hello {{ name }}"),
                &json!({ "name": "World" }),
                Mode::Sandboxed,
            )
            .expect("render failed");
        assert_eq!(out, "Hello World");
    }

    #[test]
    fn test_jinja2_sandbox_blocks_python_internals() {
        let engine = engine(JINJA2_SANDBOX_TIMEOUT);
        // The classic Jinja2 sandbox-escape vector: reaching Python internals
        // through dunder attributes. The SandboxedEnvironment must reject it.
        let template = "{{ ''.__class__.__mro__ }}";
        let err = engine
            .render(None, Some(template), &json!({}), Mode::Sandboxed)
            .unwrap_err();
        assert!(
            err.to_string().contains("SecurityError"),
            "sandboxed render should fail with a security error, got: {err}"
        );

        // The same access succeeds when unrestricted, proving it is the sandbox
        // — not a syntax or attribute error — that blocks it in sandboxed mode.
        let out = engine
            .render(None, Some(template), &json!({}), Mode::Unrestricted)
            .expect("unrestricted render should expose Python internals");
        assert!(
            out.contains("class 'str'"),
            "unrestricted render should reach the class, got: {out}"
        );
    }

    #[test]
    fn test_jinja2_times_out_on_runaway() {
        let engine = engine(Duration::from_secs(1));
        // Nested loops stay under the sandbox's per-range cap but iterate far
        // too long to finish; the timeout must abort them.
        let template =
            "{% for i in range(99999) %}{% for j in range(99999) %}{% endfor %}{% endfor %}";
        let err = engine
            .render(None, Some(template), &json!({}), Mode::Sandboxed)
            .unwrap_err();
        assert!(
            err.to_string().contains("timed out"),
            "unexpected error: {err}"
        );
    }

    #[test]
    fn test_jinja2_file_include_works_when_unrestricted() {
        let engine = engine(JINJA2_SANDBOX_TIMEOUT);
        let dir = tempfile::tempdir().unwrap();
        fs::write(dir.path().join("part.j2"), "PART").unwrap();
        let main = dir.path().join("main.j2");
        fs::write(&main, r#"A {% include "part.j2" %} B"#).unwrap();

        let out = engine
            .render(Some(main.as_path()), None, &json!({}), Mode::Unrestricted)
            .expect("render failed");
        assert_eq!(out, "A PART B");
    }

    #[test]
    fn test_jinja2_inline_include_ignores_temp_dir_when_unrestricted() {
        let engine = engine(JINJA2_SANDBOX_TIMEOUT);
        // A file in the same temp dir where inline templates are written. If the
        // inline template got a loader rooted there, this would leak in.
        let marker = tempfile::Builder::new()
            .prefix("rudder_s3_probe_")
            .suffix(".j2")
            .tempfile_in(std::env::temp_dir())
            .unwrap();
        fs::write(marker.path(), "LEAKED").unwrap();
        let name = marker.path().file_name().unwrap().to_str().unwrap();

        let template = format!("{{% include \"{name}\" %}}");
        let result = engine.render(None, Some(&template), &json!({}), Mode::Unrestricted);

        assert!(
            result.is_err(),
            "inline template must have no loader, but the include resolved"
        );
    }
}
