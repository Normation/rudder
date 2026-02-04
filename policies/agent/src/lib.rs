// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2025 Normation SAS
use anyhow::{Result, bail};
use clap::{Parser, Subcommand};
use std::path::PathBuf;

pub mod backends;
pub use backends::*;
mod agent_state;
mod constants;
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

    let policy_params = PolicyParams::default();
    let agent_conf: AgentConf = AgentConf {
        cert_validation: false,
        https_port: 443,
        https_proxy: None,
    };
    let build_info = BuildInfo::default();
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
