// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

//! Generate policies for Unix agents running CFEngine.
//!
//! # Goals
//!
//! This module doesn't cover full CFEngine syntax but only a subset of it, which is our
//! execution target, plus some Rudder-specific metadata in comments, required for reporting.
//! In particular, there is no need to handle all promises and attributes;
//! we only need to support the ones we're able to generate.
//!
//! This subset should be safe, fast and readable (in that order).
//!
//! Everything that doesn't have an effect on applied state
//! should follow a deterministic rendering process (attribute order, etc.)
//! This allows an easy diff between produced files.
//!
//! # Generation by `cf-promises`
//!
//! CFEngine is able to generate `.cf` policies from a JSON with a command like:
//!
//! ```shell
//! cf-promises --eval-functions=false --policy-output-format cf --file ./policy.json
//! ```
//!
//! So it could partially replace this module.
//! But it doesn't store macro information
//! which could prove really useful for producing fallback modes in generation.

pub(crate) mod bundle;
pub(crate) mod promise;

use anyhow::{Context, Result, bail};
use rudder_commons::{
    regex_comp,
    report::{Report, RunLog},
};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::{fs, path::Path, process::Command};
use tempfile::tempdir;
use tracing::debug;

pub const MIN_INT: i64 = -99_999_999_999;
pub const MAX_INT: i64 = 99_999_999_999;

// FIXME only quote when necessary + only concat when necessary
// no need to call explicitly
pub fn quoted(s: &str) -> String {
    format!("\"{s}\"")
}

pub fn expanded(s: &str) -> String {
    format!("\"${{{s}}}\"")
}

/// Escapes the string for usage in CFEngine
///
/// Here the goal is that what is written in the YAML source
/// (for example, a file content) gets correctly passed into the
/// destination configuration item (correctly = verbatim).
///
/// It is specially tricky as we often have to have several escaping levels, for example,
/// for commands included in CFEngine strings, themselves
/// including escaping for the shell.
///
/// CFEngine also provides variables for non-expressible things in
/// the [const](https://docs.cfengine.com/docs/3.21/reference-special-variables-const.html)
/// pseudo-bundle.
///
/// What we need to do:
///
/// * CFEngine strings use either simple or double quotes as delimiters.
///   `Rudderc` only writes double quotes, so we need to escape them using a backslash
///   inside string literals.
/// * CFEngine uses backslashes for escaping, so we'll need to escape them to pass them
///   verbatim in the output, by doubling them.
///
///  ```text
///  # in order to get this into the destination file:
///  \ \\ \\\ \\\\ \\\\\ " \" \\" ' \' \\'
///  # the source needs to contain:
///  "a" string => "\\ \\\\ \\\\\\ \\\\\\\\ \\\\\\\\\\ \" \\\" \\\\\" ' \\' \\\\'";
///  ```
/// **Warning**: in the YAML sources, [YAML escaping rules](https://yaml.org/spec/1.2.2/#escaped-characters)
/// applies, and additional backslashes will be necessary.
///
pub fn cfengine_escape(s: &str) -> String {
    //replace(  \      ,   \\     ).replace(    "    ,    \"    )
    s.replace('\\', "\\\\").replace('"', "\\\"")
}

/// Canonify a string the same way CFEngine does, i.e. one underscore for each
/// non-ascii byte.
///
/// ```Text
/// $ cat test.cf
/// bundle agent main {
///     vars:
///         "r" string => canonify("iq😋aà3");
///     reports:
///         "${r}";
/// }
///
/// $ cf-agent -KIf ./ test.cf
/// R: iq____a__3
/// ```
pub fn cfengine_canonify(input: &str) -> String {
    let s = input
        .as_bytes()
        .iter()
        .map(|x| {
            if x.is_ascii_alphanumeric() || *x == b'_' {
                *x
            } else {
                b'_'
            }
        })
        .collect::<Vec<u8>>();
    std::str::from_utf8(&s)
        .unwrap_or_else(|_| panic!("Canonify failed on {input}"))
        .to_owned()
}

/// Canonify a condition before using it in `if`/`unless` attributes.
///
/// We need to take special care to preserve variable expansion as
/// our classes include the class_parameter variable value.
///
/// To do so, we split the condition to only canonify literal text
/// outside variables.
pub fn cfengine_canonify_condition(c: &str) -> String {
    let one_variable_ref = regex_comp!(r"^\$\{[^\}]*}$");
    // not a big deal if as specific as possible
    if !c.contains("${") {
        format!("\"{c}\"")
    } else if one_variable_ref.is_match(c) {
        format!("canonify(\"{c}\")")
    } else {
        // TODO: does not handle nested vars, we need a parser for this.
        let var = regex_comp!(r"(\$\{[^\}]*})");
        format!(
            "concat(\"{}\")",
            var.replace_all(c, r#"",canonify("$1"),""#)
        )
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Deserialize, Serialize, Hash)]
pub struct CfEngineDatastate {
    #[serde(deserialize_with = "deserialize_cfengine_classes")]
    pub classes: Vec<String>,
    pub vars: serde_json::Value,
}

fn deserialize_cfengine_classes<'de, D>(deserializer: D) -> Result<Vec<String>, D::Error>
where
    D: serde::Deserializer<'de>,
{
    let map: HashMap<String, bool> = HashMap::deserialize(deserializer)?;
    Ok(map
        .into_iter()
        .filter_map(|(k, v)| if v { Some(k) } else { None })
        .collect())
}
pub struct CfAgentResult {
    pub runlog: RunLog,
    pub datastate: CfEngineDatastate,
    pub output: String,
}

pub fn cf_agent(
    input: &Path,
    params: &Path,
    lib_path: &Path,
    agent_path: &Path,
    agent_verbose: bool,
) -> Result<CfAgentResult> {
    debug!("Running cf-agent on {}", input.display());
    // Use a dedicated workdir for each test
    // Required to run agents concurrently without trouble
    let work_dir = tempdir().unwrap();
    let bin_dir = work_dir.path().join("bin");
    fs::create_dir(&bin_dir)?;
    fs::copy(agent_path.join("cf-promises"), bin_dir.join("cf-promises")).context(format!(
        "An error occurred while copying the agent to the workdir '{}' from '{}'",
        bin_dir.join("cf-promises").to_string_lossy(),
        agent_path.join("cf-promises").to_string_lossy(),
    ))?;
    // CFEngine looks in its default dir otherwise
    let input_absolute = if input.is_absolute() {
        input.to_path_buf()
    } else {
        Path::new(".").join(input)
    };
    let datastate_path = work_dir.path().join("datastate.json");
    let canon_lib_path = lib_path.canonicalize()?;
    let cmd = Command::new(agent_path.join("cf-agent"))
        .args([
            if agent_verbose { "--verbose" } else { "--info" },
            "--no-lock",
            "--workdir",
            &work_dir.path().to_string_lossy(),
            "--file",
            &input_absolute.to_string_lossy(),
        ])
        // No way to pass textual data as parameter, so we use env variables
        .env("PARAMS_FILE", params)
        .env("DATASTATE_FILE", datastate_path.clone())
        .env("LIB_PATH", canon_lib_path)
        .env("TMP_DIR", work_dir.path().to_string_lossy().to_string())
        .env("CWD", std::env::current_dir()?)
        .output()?;
    let stdout = String::from_utf8_lossy(&cmd.stdout);
    let stderr = String::from_utf8_lossy(&cmd.stderr);
    if !cmd.status.success() {
        bail!("Failed to run cf-agent:\nstdout: {stdout}\nstderr:{stderr}",);
    } else {
        // CFEngine output everything on stdout
        let run_log = Report::parse(&stdout)?;
        debug!("cf-agent output: {}", stdout);
        let raw_datastate =
            &fs::read_to_string(datastate_path.clone()).expect("Could not read the datastate file");
        Ok(CfAgentResult {
            runlog: run_log,
            datastate: serde_json::from_str(raw_datastate).unwrap(),
            output: stdout.to_string(),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn it_cfengine_escapes() {
        assert_eq!(cfengine_escape(""), "".to_string());
        assert_eq!(
            cfengine_escape(r#"\ \\ \\\ \\\\ \\\\\ " \" \\" ' \' \\'"#),
            r#"\\ \\\\ \\\\\\ \\\\\\\\ \\\\\\\\\\ \" \\\" \\\\\" ' \\' \\\\'"#.to_string()
        );
    }

    #[test]
    fn it_cfengine_canonifies() {
        assert_eq!(cfengine_canonify(""), "".to_string());
        assert_eq!(cfengine_canonify("abc"), "abc".to_string());
        assert_eq!(cfengine_canonify("a-bc"), "a_bc".to_string());
        assert_eq!(cfengine_canonify("a_bc"), "a_bc".to_string());
        assert_eq!(cfengine_canonify("a bc"), "a_bc".to_string());
        assert_eq!(cfengine_canonify("a_à_bc"), "a____bc".to_string());
        assert_eq!(cfengine_canonify("a&bc"), "a_bc".to_string());
        assert_eq!(cfengine_canonify("a9bc"), "a9bc".to_string());
        assert_eq!(cfengine_canonify("a😋bc"), "a____bc".to_string());
        assert_eq!(cfengine_canonify("test_${var}"), "test___var_".to_string());
    }

    #[test]
    fn it_cfengine_canonifies_conditions() {
        assert_eq!(
            cfengine_canonify_condition("debian"),
            "\"debian\"".to_string()
        );
        assert_eq!(
            cfengine_canonify_condition("class_prefix_${class_parameter}"),
            "concat(\"class_prefix_\",canonify(\"${class_parameter}\"),\"\")".to_string()
        );
        assert_eq!(
            cfengine_canonify_condition("class_prefix_${class_parameter}_stuff"),
            "concat(\"class_prefix_\",canonify(\"${class_parameter}\"),\"_stuff\")".to_string()
        );
        assert_eq!(
            cfengine_canonify_condition("${class_parameter}"),
            "canonify(\"${class_parameter}\")".to_string()
        );
    }
}
