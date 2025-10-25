// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use std::{
    fs::{self, create_dir_all, read_to_string},
    path::Path,
};

use super::filters;
use crate::backends::unix::cfengine::CfAgentResult;
use crate::{backends::Windows, ir::Technique, test::TestCase};
use agent::FakeAgent;
use anyhow::{Context, Result, bail};
use askama::Template;
use rudder_commons::report::{Report, RunLog};
use tracing::debug;

#[derive(Template)]
#[template(path = "test-directive.ps1.askama", escape = "none")]
struct TechniqueTestDirectiveTemplate<'a> {
    bundle_name: &'a str,
    technique_name: &'a str,
    policy_mode: &'a str,
    params: &'a str,
    conditions: Vec<&'a String>,
}

pub fn win_agent_for_ncf(
    work_dir: &Path,
    technique: &Technique,
    technique_path: &Path,
    library_path: Option<&Path>,
    agent_path: &Path,
    agent_verbose: bool,
) -> Result<CfAgentResult> {
    // Generate the directive file
    // Assume that no parameters are needed at the technique level in the lib tests
    let params = "";
    // Assume that the directive policy mode will always be Enforce in the lib tests
    let policy_mode = rudder_commons::PolicyMode::Enforce;
    // Assume the extra conditions will always be empty in the lib tests
    let conditions = vec![];
    let technique_test_directive = TechniqueTestDirectiveTemplate {
        bundle_name: &Windows::technique_name(&technique.id.to_string()),
        technique_name: &technique.name,
        policy_mode: &filters::camel_case(policy_mode)?,
        params,
        conditions,
    };
    let directive_path = work_dir.join("directive.ps1");
    fs::write(
        &directive_path,
        technique_test_directive
            .render()
            .context("Compute the directive content")?,
    )
    .context("Writing the directive file")?;

    // Render the standalone
    let log_path = work_dir.join("agent.log");
    let report_path = work_dir.join("report.log");
    let state_path = work_dir.join("state.json");
    let datastate_path = work_dir.join("datastate.json");
    // Run the agent
    let fake_agent = FakeAgent {
        work_dir: work_dir.to_path_buf(),
        state_file_path: state_path,
        verbosity: if agent_verbose {
            "verbose".to_string()
        } else {
            "normal".to_string()
        },
        ncf_folder: if let Some(l) = library_path {
            l.to_path_buf()
        } else {
            agent_path.join("share/initial-policy/ncf")
        },
        technique_file_path: technique_path.to_path_buf(),
        report_file_path: report_path.clone(),
        datastate_file_path: datastate_path.clone(),
        directive_file_path: directive_path,
        log_file_path: log_path,
    };
    let output = fake_agent.run().context("Failed to run agent")?;

    debug!("stdout: {}", String::from_utf8_lossy(&output.stdout));
    debug!("stderr: {}", String::from_utf8_lossy(&output.stderr));
    if !output.status.success() {
        bail!(
            "Test failed with {}",
            String::from_utf8_lossy(&output.stderr)
        );
    }

    // Take latest file from reports_dir
    // FIXME: find something more reliable
    let reports = read_to_string(&report_path).context(format!(
        "Reading reports file from {}",
        report_path.display()
    ))?;

    // Remove dates from output
    // Test parser does not expect it as it provides a simpler format...
    let mut clean_reports: Vec<&str> = reports
        .lines()
        .map(|l| if l.contains("R: ") { &l[26..] } else { l })
        .collect();
    // Ensure empty line at the end to allow parsing last report
    clean_reports.push("");

    let run_log = Report::parse(&clean_reports.join("\n"))?;
    let raw_datastate =
        &fs::read_to_string(datastate_path.clone()).expect("Could not read the datastate file");
    Ok(CfAgentResult {
        runlog: run_log,
        datastate: serde_json::from_str(raw_datastate)?,
        output: String::from_utf8_lossy(&output.stdout).to_string(),
    })
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
    let target_dir = std::path::absolute(target_dir)?;
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
        bundle_name: &Windows::technique_name(&technique.id.to_string()),
        technique_name: &technique.name,
        policy_mode: &filters::camel_case(case.policy_mode.to_string())?,
        params: &params,
        conditions: case.conditions.iter().collect(),
    };
    let directive_path = case_dir.join("directive.ps1");
    let directive_content = technique_test_directive.render()?;
    fs::write(&directive_path, directive_content)?;

    let log_path = case_dir.join("agent.log");
    let report_path = case_dir.join("report.log");
    let state_path = case_dir.join("state.json");
    let datastate_path = case_dir.join("datastate.json");
    // Run the agent
    let fake_agent = FakeAgent {
        work_dir: case_dir,
        state_file_path: state_path,
        verbosity: if agent_verbose {
            "verbose".to_string()
        } else {
            "normal".to_string()
        },
        ncf_folder: agent_path.join("share/initial-policy/ncf"),
        technique_file_path: target_dir.join("technique.ps1"),
        report_file_path: report_path.clone(),
        datastate_file_path: datastate_path,
        directive_file_path: directive_path,
        log_file_path: log_path,
    };
    debug!("{:#?}", fake_agent);
    let output = fake_agent.run().context("Failed to run agent")?;

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
    let reports = read_to_string(report_path).context("Reading reports file")?;

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
