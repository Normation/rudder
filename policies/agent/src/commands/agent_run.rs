use crate::{AgentConf, AgentState, AgentStatus, BuildInfo, PolicyParams};
use anyhow::Context;
use askama::Template;
use std::fs;
use std::path::PathBuf;
use std::process::Command;
use tracing::debug;

#[cfg(unix)]
pub const POWERSHELL_BIN: &str = "pwsh";
pub const POWERSHELL_OPTS: &[&str] = &["-NoProfile", "-NonInteractive"];
#[cfg(windows)]
pub const POWERSHELL_BIN: &str = "PowerShell.exe";

#[derive(Template)]
#[template(
    escape = "none",
    source = r#"
$errorActionPreference = "Stop"
Add-Type -Path '{{ncf_folder}}/rudderLib/Library.dll'
[Rudder.Logger]::StartLogger(' {{ log_file_path }}', '{{ verbosity }}')
[Rudder.Logger]::Log.Debug("Loading the ncf library '{{ ncf_folder }}/ncf.ps1'")
$script:rudderVariable = New-Object System.Collections.ArrayList
.              '{{ncf_folder}}/ncf.ps1'
#################
# Load the ps1 files #
#################
try {
  [Rudder.Logger]::Log.Debug("Loading technique file '{{ technique_file_path }}'")
  . '{{ technique_file_path }}'
} catch {
  [Rudder.Logger]::Log.Error("${_}")
  exit 1
}
#################
# Load the data #
#################
$rudderState = cat '{{ state_file_path }}' | ConvertFrom-Json
$rudderState.PSObject.Properties | Foreach-Object {
  $name = $_.Name
  $value = ConvertPSObjectToHashtable -InputObject $_.Value
  [Rudder.Datastate]::AddVar($name, $value)
}
[Rudder.Datastate]::AddVar('startDate', ([System.DateTime]::UTCNow))
[Rudder.Datastate]::AddVar('reportFile', '{{ report_file_path }}')

#######################
# Load the directives #
#######################

try {
  [Rudder.Logger]::Log.Debug("Loading directive file '{{ directive_file_path }}'")
  . '{{ directive_file_path }}'
} catch {
  [Rudder.Logger]::Log.Error("${_}")
  exit 1
}

##################
# Run the policy #
##################

Rudder-Control-Report -Step "start"
Call_Directives
Rudder-Control-Report -Step "end"

######################
# Dump the datastate #
######################

Set-Content -Path '{{ datastate_file_path }}' -Value (([Rudder.Datastate]::Get() | ConvertTo-Json -Depth 5))
"#,
    ext = "txt"
)]
struct FakeAgentTemplate {
    pub state_file_path: String,
    pub verbosity: String,
    pub ncf_folder: String,
    pub technique_file_path: String,
    pub report_file_path: String,
    pub datastate_file_path: String,
    pub directive_file_path: String,
    pub log_file_path: String,
}

#[derive(Debug)]
pub struct FakeAgent {
    pub work_dir: PathBuf,
    pub state_file_path: PathBuf,
    pub verbosity: String,
    pub ncf_folder: PathBuf,
    pub technique_file_path: PathBuf,
    pub report_file_path: PathBuf,
    pub datastate_file_path: PathBuf,
    pub directive_file_path: PathBuf,
    pub log_file_path: PathBuf,
}

impl FakeAgent {
    fn render(&self) -> Result<String, anyhow::Error> {
        let t = FakeAgentTemplate {
            state_file_path: self.state_file_path.display().to_string(),
            verbosity: self.verbosity.to_string(),
            ncf_folder: self.ncf_folder.display().to_string(),
            technique_file_path: self.technique_file_path.display().to_string(),
            report_file_path: self.report_file_path.display().to_string(),
            datastate_file_path: self.datastate_file_path.display().to_string(),
            directive_file_path: self.directive_file_path.display().to_string(),
            log_file_path: self.log_file_path.display().to_string(),
        };
        let s = t.render().context("Failed to render template")?;
        Ok(s)
    }
    pub fn run(&self) -> Result<std::process::Output, anyhow::Error> {
        debug!("{:#?}", self);
        let standalone_path = self.work_dir.join("standalone.ps1");
        let agent_state = AgentState {
            root: self.work_dir.to_path_buf(),
            policy_params: PolicyParams::default(),
            agent_conf: AgentConf::default(),
            uuid: Some("fake-uuid".to_string()),
            policy_server: None,
            build_info: BuildInfo::default(),
            status: AgentStatus::Enabled,
        };
        debug!("Writing agent state to {}", &self.state_file_path.display());
        fs::write(
            &self.state_file_path,
            serde_json::to_string_pretty(&agent_state).context("Serializing the agent state")?,
        )
        .context("Writing the state file")?;
        debug!("Writing standalone file to {}", &standalone_path.display());
        fs::write(
            &standalone_path,
            self.render()
                .context("Failed to compute the standalone content")?,
        )
        .context("Writing the standalone file")?;

        let r = Command::new(POWERSHELL_BIN)
            .args(POWERSHELL_OPTS)
            .arg("-Command")
            .arg(format!(
                "&'{}'",
                &standalone_path
                    .canonicalize()
                    .context("Resolving the standalone path")?
                    .to_string_lossy()
            ))
            .current_dir(&self.work_dir)
            .output()
            .context("Reading execution output")?;
        Ok(r)
    }
}

pub fn run_agent_run(_agent_state: AgentState) -> Result<(), anyhow::Error> {
    unimplemented!();
}
