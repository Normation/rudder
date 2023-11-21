// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use std::{
    fs::{self, read_to_string},
    path::Path,
};

use super::filters;
use crate::{ir::Technique, test::TestCase};
use anyhow::{Context, Result};
use askama::Template;
use powershell_script::PsScriptBuilder;
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
}

/// Run a test technique with the Windows agent
pub fn win_agent(
    technique_path: &Path,
    technique: &Technique,
    case: &TestCase,
    // We need a full Windows agent
    agent_path: &Path,
    agent_verbose: bool,
) -> Result<RunLog> {
    let tmp = tempfile::tempdir()?.into_path();

    // Compute params arguments
    let params = case
        .parameters
        .iter()
        .map(|(n, v)| format!("-{} \"{}\"", n, filters::escape_double_quotes(v).unwrap()))
        .collect::<Vec<String>>()
        .join(" ");

    let technique_test_directive = TechniqueTestDirectiveTemplate {
        technique: &filters::dsc_case(&technique.id)?,
        technique_name: &technique.name,
        policy_mode: &filters::camel_case(case.policy_mode.to_string())?,
        params: &params,
    };
    let directive_path = tmp.join("directive.ps1");
    let directive_content = technique_test_directive.render()?;
    fs::write(&directive_path, directive_content)?;

    let log_path = tmp.join("agent.log");
    let reports_dir = agent_path.join("tmp/reports");
    let technique_test = TechniqueTestTemplate {
        agent_path,
        verbosity: if agent_verbose { "verbose" } else { "normal" },
        log_path: &log_path,
        ncf_path: &agent_path.join("share/initial-policy/ncf"),
        directive_path: &directive_path,
        technique_path,
    };
    let test_path = tmp.join("test.ps1");
    let test_content = technique_test.render()?;
    fs::write(&test_path, test_content)?;
    ok_output("Running", test_path.display());

    // Run the agent
    let ps = PsScriptBuilder::new()
        // load profile normally
        .no_profile(false)
        // non-interactive session
        .non_interactive(true)
        // don't show a window
        .hidden(true)
        // don't print commands in output
        .print_commands(false)
        .build();
    let output = ps.run(&test_path.to_string_lossy())?;

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
    // Ensure empty line at the end to allow pasring last report
    clean_reports.push("");

    let run_log = Report::parse(&clean_reports.join("\n"))?;
    debug!("reports: {}", reports);
    debug!("stdout: {}", output.stdout().unwrap_or("".to_string()));
    debug!("stderr: {}", output.stderr().unwrap_or("".to_string()));
    Ok(run_log)
}
