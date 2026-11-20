// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS
use anyhow::{Context, Result};
use clap::Parser;
use rudder_cli::spinner;
use rudder_module_type::PolicyMode;
use serde_json::{Map, Value};
use std::path::Path;
use tracing_subscriber;

use crate::{module::ChocoModule, module::ModuleOutcome, parameters::ChocoModuleParameters};

fn read_from_json_or_file(s: &str) -> Result<Map<String, Value>> {
    let content = if Path::new(s).is_file() {
        std::fs::read_to_string(s).with_context(|| format!("Could not read file '{s}'"))?
    } else {
        s.to_string()
    };
    serde_json::from_str(&content).context("Invalid JSON '{s}'")
}

#[derive(Parser, Debug)]
#[command(version, about, long_about = None)]
pub struct Cli {
    #[arg(short, long, value_name = "inline JSON or path to a JSON file", value_parser = read_from_json_or_file)]
    data: Map<String, Value>,
    #[arg(short, long)]
    audit: bool,
    #[arg(short, long)]
    verbose: bool,
}

impl Cli {
    pub fn run() -> Result<()> {
        let cli = Cli::parse();
        let log_level = if cli.verbose {
            tracing::Level::DEBUG
        } else {
            tracing::Level::INFO
        };
        tracing_subscriber::fmt()
            .with_max_level(log_level)
            .with_writer(std::io::stderr)
            .init();
        let p: ChocoModuleParameters = serde_json::from_value(Value::Object(cli.data))
            .context("Could not deserialize the data parameter")?;
        let policy_mode = if cli.audit {
            PolicyMode::Audit
        } else {
            PolicyMode::Enforce
        };

        let mut module = ChocoModule::default();
        let spinner = spinner::Animation::start(
            "Gathering the installed and available packages...".to_string(),
        );
        module.read_state()?;
        spinner.stop_with_symbol("🗸");
        let outcomes = module.check_apply_inner(policy_mode, &p)?;

        let summary = ChocoModule::summarize(&outcomes);
        println!("{}", summary.message);
        match summary.outcome {
            ModuleOutcome::Success | ModuleOutcome::Repaired => Ok(()),
            _ => std::process::exit(1),
        }
    }
}
