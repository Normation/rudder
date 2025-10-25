use anyhow::{Result, bail};
use clap::{Parser, Subcommand};
use std::path::PathBuf;

mod agent_state;
mod consts;
pub use agent_state::*;
mod config;
pub use config::*;

mod commands;
pub use commands::*;

/// Rudder agent command line interface
#[derive(Parser, Debug)]
#[command(name = "rudder")]
#[command(about = "Rudder generic command line interface", long_about = None)]
#[command(version)]
pub struct Cli {
    #[command(subcommand)]
    pub command: Commands,
}

#[derive(Subcommand, Debug)]
pub enum Commands {
    /// Rudder agent related commands
    Agent {
        #[command(subcommand)]
        action: AgentAction,
    },
}

#[derive(Subcommand, Debug)]
pub enum AgentAction {
    /// Check agent status
    Check {
        /// Reset the check status
        #[arg(short, long)]
        reset: bool,
    },

    /// Check scheduled tasks
    CheckScheduledTasks {
        /// Username for scheduled tasks
        #[arg(short = 'u', long)]
        username: Option<String>,
    },

    /// Disable the agent
    Disable,

    /// Enable the agent
    Enable,

    /// Perform a factory reset
    FactoryReset,

    /// Check agent health
    Health,

    /// Display agent history
    History {
        /// Number of entries to display
        #[arg(short, long, default_value = "25")]
        n: u32,
    },

    /// Display agent information
    Info {},

    /// List classes
    ListClasses,

    /// Display agent logs
    Log {
        /// Path to log file
        #[arg(short, long)]
        path: Option<String>,

        /// Number of log entries to display
        #[arg(short, long, default_value = "1")]
        n: u32,

        /// Recursive flag
        #[arg(short)]
        r: bool,

        /// Show only failures
        #[arg(long)]
        fail: bool,
    },

    /// Run inventory
    Inventory,

    /// Configure policy server
    PolicyServer {
        /// Policy server address
        #[arg(long)]
        policy_server: Option<String>,
    },

    /// Reset the agent
    Reset,

    /// Reset server keys
    ServerKeysReset {
        /// Force the operation
        #[arg(short, long)]
        force: bool,
    },

    /// Run the agent
    Run {},

    /// Update the agent
    Update,

    /// Display agent version
    Version,
}

// List of actions that require the agent to be enabled
pub fn run() -> Result<()> {
    let cli = Cli::parse();

    let system_params = r#"
    {
  "AGENT_RUN_INTERVAL":"5",
  "AGENT_RUN_SCHEDULE":"\"Min00\", \"Min05\", \"Min10\", \"Min15\", \"Min20\", \"Min25\", \"Min30\", \"Min35\", \"Min40\", \"Min45\", \"Min50\", \"Min55\"",
  "AGENT_RUN_SPLAYTIME":"4",
  "AGENT_RUN_STARTTIME":"00:03:12",
  "AGENT_TYPE":"dsc",
  "ALLOWED_NETWORKS":[],
  "CFENGINE_OUTPUTS_TTL":"6",
  "COMMUNITYPORT":"5309",
  "CONFIGURATION_REPOSITORY_FOLDER":"/var/rudder/configuration-repository",
  "DAVPASSWORD":"f861091fd5e9cc120866",
  "DAVUSER":"rudder",
  "DENYBADCLOCKS":"true",
  "HTTPS_POLICY_DISTRIBUTION_PORT":"443",
  "INSTANCE_ID":"1c5ebec2-add6-46b7-98c8-c4200a5dc299",
  "MODIFIED_FILES_TTL":"30",
  "POLICY_SERVER_KEY":"MD5=8a07f6941dd5afb7601da93c3c3bf36a",
  "POLICY_SERVER_KEY_HASH":"sha256//4BmcSBb6WJebDT5p0Y6yKHvmlyh193YN5no9Pj6D5Vo=",
  "RELAY_SYNC_METHOD":"classic",
  "RELAY_SYNC_PROMISES":"true",
  "RELAY_SYNC_SHAREDFILES":"true",
  "REPORTING_PROTOCOL":"HTTPS",
  "RUDDER_COMPLIANCE_MODE":"enforce",
  "RUDDER_DIRECTIVES_INPUTS":[
    "disable_swapiness_on_linux/1_0_443fca4d_3455_4821_bc8f_ff6251f96e86/technique.ps1",
    "hook_to_detect_if_reboot_is_needed/1_0_4957c1c2_3105_4648_8bf4_78c9915fd73c/technique.ps1",
    "configure_windows_ssh/1_0_3b0e3b4a_47aa_4aef_9e07_de389f39ac32/technique.ps1",
    "configure_windows_firewall/1_0_46766348_a79e_43ce_b67c_0eebb36770ac/technique.ps1"
  ],
  "RUDDER_DIRECTIVES_SEQUENCE":"  BeginDirectiveCall -Name @'\nAll System baseline/Disable swapiness or pagefile\n'@ -Id '443fca4d-3455-4821-bc8f-ff6251f96e86'\n  Technique-Disable-Swapiness-On-Linux -ReportId 'c59cc156-8655-4cd9-94bd-c152d0625201@@443fca4d-3455-4821-bc8f-ff6251f96e86@@0' -TechniqueName 'disable_swapiness_on_linux' -PolicyMode ([Rudder.PolicyMode]::Enforce)\n  EndDirectiveCall -Name @'\nAll System baseline/Disable swapiness or pagefile\n'@ -Id '443fca4d-3455-4821-bc8f-ff6251f96e86'\n\n  BeginDirectiveCall -Name @'\nAll System baseline/Hook to detect if reboot is needed\n'@ -Id '4957c1c2-3105-4648-8bf4-78c9915fd73c'\n  Technique-Hook-To-Detect-If-Reboot-Is-Needed -ReportId 'c59cc156-8655-4cd9-94bd-c152d0625201@@4957c1c2-3105-4648-8bf4-78c9915fd73c@@0' -TechniqueName 'hook_to_detect_if_reboot_is_needed' -PolicyMode ([Rudder.PolicyMode]::Enforce)\n  EndDirectiveCall -Name @'\nAll System baseline/Hook to detect if reboot is needed\n'@ -Id '4957c1c2-3105-4648-8bf4-78c9915fd73c'\n\n  BeginDirectiveCall -Name @'\nWindows baseline/Configure Windows SSH\n'@ -Id '3b0e3b4a-47aa-4aef-9e07-de389f39ac32'\n  Technique-Configure-Windows-Ssh -ReportId '7b6fa8ad-0dcf-4877-bbc4-3a2f5188a8c6@@3b0e3b4a-47aa-4aef-9e07-de389f39ac32@@0' -TechniqueName 'configure_windows_ssh' -PolicyMode ([Rudder.PolicyMode]::Enforce)\n  EndDirectiveCall -Name @'\nWindows baseline/Configure Windows SSH\n'@ -Id '3b0e3b4a-47aa-4aef-9e07-de389f39ac32'\n\n  BeginDirectiveCall -Name @'\nWindows System hardening/Configure Windows Firewall\n'@ -Id '46766348-a79e-43ce-b67c-0eebb36770ac'\n  Technique-Configure-Windows-Firewall -ReportId 'b4f37a63-a755-40fd-b0a7-0d82278791de@@46766348-a79e-43ce-b67c-0eebb36770ac@@0' -TechniqueName 'configure_windows_firewall' -PolicyMode ([Rudder.PolicyMode]::Enforce)\n  EndDirectiveCall -Name @'\nWindows System hardening/Configure Windows Firewall\n'@ -Id '46766348-a79e-43ce-b67c-0eebb36770ac'",
  "RUDDER_HEARTBEAT_INTERVAL":"4",
  "RUDDER_NODE_CONFIG_ID":"20251008-153231-d466457a",
  "RUDDER_NODE_GROUPS_CLASSES":"\"group_all_nodes_with_dsc_agent\"                   expression => \"any\",\n                                                         meta => { \"inventory\", \"attribute_name=rudder_groups\" };\n\"group_all_nodes_with_a_rudder_windows_dsc_agent\"  expression => \"any\",\n                                                         meta => { \"inventory\", \"attribute_name=rudder_groups\" };\n\"group_special_all_exceptpolicyservers\"            expression => \"any\",\n                                                         meta => { \"inventory\", \"attribute_name=rudder_groups\" };\n\"group_all_managed_nodes\"                          expression => \"any\",\n                                                         meta => { \"inventory\", \"attribute_name=rudder_groups\" };\n\"group_da5ec374_70bf_432a_860b_e28b4f8b3e6c\"       expression => \"any\",\n                                                         meta => { \"inventory\", \"attribute_name=rudder_groups\" };\n\"group_dev_servers\"                                expression => \"any\",\n                                                         meta => { \"inventory\", \"attribute_name=rudder_groups\" };\n\"group_special_all\"                                expression => \"any\",\n                                                         meta => { \"inventory\", \"attribute_name=rudder_groups\" };\n\"group_all_nodes\"                                  expression => \"any\",\n                                                         meta => { \"inventory\", \"attribute_name=rudder_groups\" };\n\"group_b10f6213_05e9_43bb_85de_fa390f19aaab\"       expression => \"any\",\n                                                         meta => { \"inventory\", \"attribute_name=rudder_groups\" };\n\"group_all_windows\"                                expression => \"any\",\n                                                         meta => { \"inventory\", \"attribute_name=rudder_groups\" };",
  "RUDDER_NODE_GROUPS_VARS":"\"by_uuid[all-nodes-with-dsc-agent]\"                   string => \"All nodes with a Rudder Windows DSC agent\",\n                                                        meta => { \"inventory\", \"attribute_name=rudder_groups\" };\n\"by_uuid[special:all_exceptPolicyServers]\"            string => \"All managed nodes\",\n                                                        meta => { \"inventory\", \"attribute_name=rudder_groups\" };\n\"by_uuid[da5ec374-70bf-432a-860b-e28b4f8b3e6c]\"       string => \"Dev servers\",\n                                                        meta => { \"inventory\", \"attribute_name=rudder_groups\" };\n\"by_uuid[special:all]\"                string => \"All nodes\",\n                                                        meta => { \"inventory\", \"attribute_name=rudder_groups\" };\n\"by_uuid[b10f6213-05e9-43bb-85de-fa390f19aaab]\"       string => \"All Windows\",\n                                                        meta => { \"inventory\", \"attribute_name=rudder_groups\" };",
  "RUDDER_NODE_KIND":"node",
  "RUDDER_REPORT_MODE":"full-compliance",
  "RUDDER_SYSTEM_DIRECTIVES_INPUTS":"",
  "RUDDER_SYSTEM_DIRECTIVES_SEQUENCE":"  BeginDirectiveCall -Name @'\nRudder system policy: base configuration for DSC based agent/DSC Based agent configuration\n'@ -Id 'dsc-common-all'\n  configure_logger -ReportId 'dsc-agent-all@@dsc-common-all@@0' -TechniqueName 'dsc-common' -PolicyMode ([Rudder.PolicyMode]::Enforce)\n  EndDirectiveCall -Name @'\nRudder system policy: base configuration for DSC based agent/DSC Based agent configuration\n'@ -Id 'dsc-common-all'\n\n  BeginDirectiveCall -Name @'\nRudder system policy: base configuration for DSC based agent/DSC Based agent configuration\n'@ -Id 'dsc-common-all'\n  Configure-Variables -ReportId 'dsc-agent-all@@dsc-common-all@@0' -TechniqueName 'dsc-common' -PolicyMode ([Rudder.PolicyMode]::Enforce)\n  EndDirectiveCall -Name @'\nRudder system policy: base configuration for DSC based agent/DSC Based agent configuration\n'@ -Id 'dsc-common-all'\n\n  BeginDirectiveCall -Name @'\nRudder system policy: base configuration for DSC based agent/DSC Based agent configuration\n'@ -Id 'dsc-common-all'\n  configure_scheduler -ReportId 'dsc-agent-all@@dsc-common-all@@0' -TechniqueName 'dsc-common' -PolicyMode ([Rudder.PolicyMode]::Enforce)\n  EndDirectiveCall -Name @'\nRudder system policy: base configuration for DSC based agent/DSC Based agent configuration\n'@ -Id 'dsc-common-all'\n\n  BeginDirectiveCall -Name @'\nRudder system policy: base configuration for DSC based agent/DSC Based agent configuration\n'@ -Id 'dsc-common-all'\n  reporting-http -ReportId 'dsc-agent-all@@dsc-common-all@@0' -TechniqueName 'dsc-common' -PolicyMode ([Rudder.PolicyMode]::Enforce)\n  EndDirectiveCall -Name @'\nRudder system policy: base configuration for DSC based agent/DSC Based agent configuration\n'@ -Id 'dsc-common-all'\n\n  BeginDirectiveCall -Name @'\nRudder system policy: base configuration for DSC based agent/DSC Based agent configuration\n'@ -Id 'dsc-common-all'\n  prerun-check -ReportId 'dsc-agent-all@@dsc-common-all@@0' -TechniqueName 'dsc-common' -PolicyMode ([Rudder.PolicyMode]::Enforce)\n  EndDirectiveCall -Name @'\nRudder system policy: base configuration for DSC based agent/DSC Based agent configuration\n'@ -Id 'dsc-common-all'\n\n  BeginDirectiveCall -Name @'\nRudder system policy: base configuration for DSC based agent/DSC Based agent configuration\n'@ -Id 'dsc-common-all'\n  Force-Rudder-Inventory -ReportId 'dsc-agent-all@@dsc-common-all@@0' -TechniqueName 'dsc-common' -PolicyMode ([Rudder.PolicyMode]::Enforce)\n  EndDirectiveCall -Name @'\nRudder system policy: base configuration for DSC based agent/DSC Based agent configuration\n'@ -Id 'dsc-common-all'",
  "RUDDER_VERIFY_CERTIFICATES":"false",
  "SEND_METRICS":"no",
  "SERVER_VERSION":"8.3.5",
  "SHARED_FILES_FOLDER":"/var/rudder/configuration-repository/shared-files",
  "TOOLS_FOLDER":"/var/rudder/tools"
}"#;
    let r_buildinfo = r#"
    {
"git_ref": "branches/rudder/8.2",
"main_version": "8.2.2",
"ncf_commit": "9412f89fe44435b40bdb9109e5c87eac55e387eb",
"nightly_tag": "git202411212205",
"release_step": "8.2.2",
"repository_version": "8.2-nightly",
"rudder-agent_commit": "4f6cf0e414a1aa98ccaa7540d3748b83b5f7cffe",
"rudder-api-client_commit": "d843a82babb222bc06b53314608d6d7557f1a464",
"rudder_commit": "c3d6bc329ccf843a7a3fee041d64c59afca90309",
"rudder-doc_commit": "cd01d4a4bb5b0fbeed0ab454d34fbd3ce247fb71",
"rudder_msi_version": "8.2.2.1902573166",
"rudder-packages_commit": "04786edb91e423b50ff074ddbc785e74cf3ed4f0",
"rudder-techniques_commit": "bcb6d7faf94a8e2911a3c23d60dc075fe75b92ae",
"rudder_version": "8.2.2~git202411212205",
"version": "8.2.2~git202411212205"
}

    "#;
    let policy_params: PolicyParams = serde_json::from_str(system_params)?;
    let agent_conf: AgentConf = AgentConf {
        cert_validation: false,
        https_port: 443,
        https_proxy: None,
    };
    let build_info = BuildInfo::load_from_json(r_buildinfo)?;
    let agent_state = AgentState::new(
        PathBuf::from("/tmp/agent"),
        policy_params,
        agent_conf,
        build_info,
    )?;

    match cli.command {
        Commands::Agent { action } => match action {
            AgentAction::Disable => commands::run_agent_disable(agent_state),
            AgentAction::Enable => commands::run_agent_enable(agent_state),
            AgentAction::Info {} => commands::run_agent_info(agent_state),
            AgentAction::Run {} => commands::run_agent_run(agent_state),
            _ => bail!("not implemented yet"),
        },
    }
}
