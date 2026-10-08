//! Technique compilation and method library metadata, with the `rudderc` binary.
//!
//! `rudderc` runs as a subprocess, like the webapp does. The `rudderc` library keeps its user
//! error count in process-wide statics that are never reset, so in a long-running server one
//! invalid technique would make every later compilation fail.

use std::{
    collections::BTreeMap,
    ffi::OsStr,
    path::{Path, PathBuf},
    process::{Output, Stdio},
    sync::Arc,
    time::Duration,
};

use serde::Deserialize;
use serde_json::Value;
use tokio::{process::Command, time::timeout};

const TIMEOUT: Duration = Duration::from_secs(30);
const TECHNIQUE_FILE: &str = "technique.yml";
/// Files produced by `rudderc build` in `target/`
const OUTPUT_FILES: [&str; 3] = ["technique.cf", "technique.ps1", "metadata.xml"];

/// Methods of the library, by method id (the `method` value in a technique), with their full
/// metadata as `rudderc` exports it
pub type Methods = BTreeMap<String, Value>;

/// Fields of a method's metadata used in the method list
#[derive(Deserialize)]
pub struct MethodSummary {
    pub name: String,
    pub description: String,
    pub deprecated: Option<String>,
}

#[derive(Debug, Clone)]
pub struct Rudderc {
    path: Arc<Path>,
}

impl Rudderc {
    pub fn new(path: PathBuf) -> Self {
        Self { path: path.into() }
    }

    pub fn path(&self) -> &Path {
        &self.path
    }

    /// Compiles a YAML technique, using the method library in `/var/rudder/ncf`.
    ///
    /// Returns `rudderc`'s output followed by the generated files, or its diagnostics on failure.
    pub async fn build(&self, technique: &str) -> Result<String, String> {
        let dir =
            tempfile::tempdir().map_err(|e| format!("could not create a work directory: {e}"))?;
        tokio::fs::write(dir.path().join(TECHNIQUE_FILE), technique)
            .await
            .map_err(|e| format!("could not write the technique: {e}"))?;

        let output = self
            .run([
                OsStr::new("--directory"),
                dir.path().as_os_str(),
                OsStr::new("build"),
            ])
            .await?;
        let log = format!(
            "{}{}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr)
        );
        if !output.status.success() {
            return Err(format!("rudderc failed ({}):\n{log}", output.status));
        }

        let mut result = format!("rudderc output:\n{log}");
        for name in OUTPUT_FILES {
            let content = tokio::fs::read_to_string(dir.path().join("target").join(name))
                .await
                .map_err(|e| format!("rudderc did not produce {name}: {e}"))?;
            result.push_str(&format!("\n--- {name} ---\n{content}"));
        }
        Ok(result)
    }

    /// Metadata of all methods in the library in `/var/rudder/ncf`
    pub async fn methods(&self) -> Result<Methods, String> {
        let output = self
            .run(["lib", "--format", "json", "--stdout"].map(OsStr::new))
            .await?;
        if !output.status.success() {
            return Err(format!(
                "rudderc failed ({}):\n{}",
                output.status,
                String::from_utf8_lossy(&output.stderr)
            ));
        }
        serde_json::from_slice(&output.stdout)
            .map_err(|e| format!("unexpected rudderc method list: {e}"))
    }

    async fn run<'a>(&self, args: impl IntoIterator<Item = &'a OsStr>) -> Result<Output, String> {
        let run = Command::new(&*self.path)
            .args(args)
            .env("NO_COLOR", "1")
            .stdin(Stdio::null())
            // Stops `rudderc` when the timeout drops the future
            .kill_on_drop(true)
            .output();
        timeout(TIMEOUT, run)
            .await
            .map_err(|_| format!("rudderc timed out after {}s", TIMEOUT.as_secs()))?
            .map_err(|e| format!("could not run {}: {e}", self.path.display()))
    }
}

#[cfg(test)]
mod tests {
    use std::os::unix::fs::PermissionsExt;

    use pretty_assertions::assert_eq;
    use tempfile::TempDir;

    use super::*;

    /// A `rudderc` stand-in: tests the wrapper (arguments, output handling, errors), not `rudderc`
    fn fake_rudderc(script: &str) -> (TempDir, Rudderc) {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("rudderc");
        std::fs::write(&path, format!("#!/bin/sh\n{script}")).unwrap();
        std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o755)).unwrap();
        (dir, Rudderc::new(path))
    }

    const METHODS_JSON: &str = r#"{
        "package_present": {"name": "Package present", "description": "Enforce a package",
                            "parameter": [], "source": "/var/rudder/ncf/common/package_present.cf"},
        "package_install": {"name": "Package install", "description": "Install a package",
                            "deprecated": "Use package_present instead"}
    }"#;

    #[tokio::test]
    async fn methods_are_parsed_by_id() {
        let (_dir, rudderc) = fake_rudderc(&format!(
            "[ \"$*\" = \"lib --format json --stdout\" ] || exit 9\ncat <<'EOF'\n{METHODS_JSON}\nEOF\n"
        ));
        let methods = rudderc.methods().await.unwrap();
        assert_eq!(
            methods.keys().collect::<Vec<_>>(),
            ["package_install", "package_present"]
        );
        let summary = MethodSummary::deserialize(methods["package_install"].clone()).unwrap();
        assert_eq!(summary.name, "Package install");
        assert_eq!(
            summary.deprecated.as_deref(),
            Some("Use package_present instead")
        );
    }

    #[tokio::test]
    async fn methods_failure_reports_stderr() {
        let (_dir, rudderc) = fake_rudderc("echo 'ERROR No methods were loaded.' >&2\nexit 1\n");
        let error = rudderc.methods().await.unwrap_err();
        assert!(error.contains("No methods were loaded"), "{error}");
    }

    #[tokio::test]
    async fn build_returns_output_and_generated_files() {
        // Checks it is called as `--directory <dir> build`, with the technique in `<dir>`
        let (_dir, rudderc) = fake_rudderc(
            r#"[ "$1" = "--directory" ] && [ "$3" = "build" ] || exit 9
grep -q "id: min" "$2/technique.yml" || exit 8
mkdir -p "$2/target"
for f in technique.cf technique.ps1 metadata.xml; do echo "generated $f" > "$2/target/$f"; done
echo "   Compiling min"
"#,
        );
        let result = rudderc.build("id: min\n").await.unwrap();
        assert!(result.contains("Compiling min"), "{result}");
        for file in OUTPUT_FILES {
            assert!(
                result.contains(&format!("--- {file} ---\ngenerated {file}")),
                "{result}"
            );
        }
    }

    #[tokio::test]
    async fn build_failure_returns_diagnostics() {
        let (_dir, rudderc) =
            fake_rudderc("echo \"ERROR Unknown method 'package_presen'\" >&2\nexit 1\n");
        let error = rudderc.build("id: broken\n").await.unwrap_err();
        assert!(error.contains("rudderc failed"), "{error}");
        assert!(error.contains("Unknown method 'package_presen'"), "{error}");
    }

    #[tokio::test]
    async fn missing_binary_is_reported() {
        let rudderc = Rudderc::new(PathBuf::from("/nonexistent/rudderc"));
        let error = rudderc.methods().await.unwrap_err();
        assert!(
            error.contains("could not run /nonexistent/rudderc"),
            "{error}"
        );
    }
}
