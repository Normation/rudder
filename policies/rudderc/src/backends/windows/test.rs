// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use std::{
    fs::{self, create_dir_all, read_to_string},
    path::Path,
    process::Command,
};

use super::filters;
use crate::{
    backends::{
        Windows,
        windows::{POWERSHELL_BIN, POWERSHELL_OPTS},
    },
    ir::Technique,
    test::TestCase,
};
use anyhow::{Context, Result, bail};
use askama::Template;
use rudder_commons::{
    logs::ok_output,
    report::{Report, RunLog},
};
use tracing::debug;

#[derive(Template)]
#[template(path = "test.ps1.askama", escape = "none")]
struct TechniqueTestTemplate<'a> {
    agent_path: &'a Path,
    verbosity: &'a str,
    log_path: &'a Path,
    ncf_path: &'a Path,
    directive_path: &'a Path,
    technique_path: &'a Path,
}

#[derive(Template)]
#[template(path = "test-directive.ps1.askama", escape = "none")]
struct TechniqueTestDirectiveTemplate<'a> {
    technique: &'a str,
    technique_name: &'a str,
    policy_mode: &'a str,
    params: &'a str,
    conditions: Vec<&'a String>,
}

/// Run a test technique with the Windows agent
pub fn win_agent(
    target_dir: &Path,
    technique: &Technique,
    case: &TestCase,
    case_id: &str,
    agent_path: &Path,
    agent_verbose: bool,
) -> Result<RunLog> {
    // Put all test-specific files into target/{CASE_NAME}/
    let case_dir = target_dir.join(case_id);
    create_dir_all(&case_dir)?;

    // Compute params arguments
    let params = case
        .parameters
        .iter()
        .map(|(n, v)| format!("-{} '{}'", n, filters::escape_double_quotes(v).unwrap()))
        .collect::<Vec<String>>()
        .join(" ");

    let technique_test_directive = TechniqueTestDirectiveTemplate {
        technique: &Windows::technique_name_plain(&technique.id.to_string()),
        technique_name: &technique.name,
        policy_mode: &filters::camel_case(case.policy_mode.to_string())?,
        params: &params,
        conditions: case.conditions.iter().collect(),
    };
    let directive_path = case_dir.join("directive.ps1");
    let directive_content = technique_test_directive.render()?;
    fs::write(&directive_path, directive_content)?;

    let log_path = case_dir.join("agent.log");
    let reports_dir = agent_path.join("tmp/reports");
    let technique_test = TechniqueTestTemplate {
        agent_path,
        verbosity: if agent_verbose { "verbose" } else { "normal" },
        log_path: &log_path,
        ncf_path: &agent_path.join("share/initial-policy/ncf"),
        directive_path: &directive_path,
        technique_path: &target_dir.join("technique.ps1"),
    };
    let test_path = case_dir.join("test.ps1");
    let test_content = technique_test.render()?;
    fs::write(&test_path, test_content)?;
    ok_output("Running", format!("test from {}", case_dir.display()));

    // Run the agent
    let output = Command::new(POWERSHELL_BIN)
        .args(POWERSHELL_OPTS)
        .arg("-Command")
        .arg(format!("&'{}'", test_path.display()))
        .output()?;

    debug!("stdout: {}", String::from_utf8_lossy(&output.stdout));
    debug!("stderr: {}", String::from_utf8_lossy(&output.stderr));
    if !output.status.success() {
        bail!(
            "Test {} failed with {}",
            case_id,
            String::from_utf8_lossy(&output.stderr)
        );
    }

    // Take latest file from reports_dir
    // FIXME: find something more reliable
    let mut paths: Vec<_> = fs::read_dir(reports_dir)?.map(|r| r.unwrap()).collect();
    paths.sort_by_key(|dir| dir.path());
    let reports_path = paths.last().unwrap().path();
    let reports = read_to_string(reports_path).context("Reading reports file")?;

    // Remove dates from output
    // Test parser does not expect it as it provides a simpler format...
    let mut clean_reports: Vec<&str> = reports
        .lines()
        .map(|l| if l.contains("R: ") { &l[26..] } else { l })
        .collect();
    // Ensure empty line at the end to allow parsing last report
    clean_reports.push("");

    let run_log = Report::parse(&clean_reports.join("\n"))?;
    debug!("reports: {}", reports);
    Ok(run_log)
}
