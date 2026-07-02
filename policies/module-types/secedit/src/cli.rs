use crate::secedit::Secedit;
use crate::secedit::{SeceditOutcome, SeceditParameters};
use anyhow::{Context, Result, bail};
use clap::Parser;
use rudder_module_type::PolicyMode;
use std::{fs::read_to_string, path::PathBuf, process};
use tempfile::tempdir_in;

#[derive(Parser)]
#[command(version, about, long_about = None)]
pub struct Cli {
    /// JSON data file
    #[arg(short, long, value_name = "JSON data file")]
    data: PathBuf,
    /// Audit mode
    #[arg(short, long)]
    audit: bool,
    /// Path for temporary files
    #[arg(short, long, value_name = "Temp work dir path")]
    tmp: PathBuf,
    /// Output in JSON format
    #[arg(short, long)]
    json: bool,
}

impl Cli {
    pub fn run() -> Result<()> {
        let cli = Cli::parse();
        let data = read_to_string(&cli.data)
            .with_context(|| format!("Failed to read data file '{}'", cli.data.display()))?;

        if data.is_empty() {
            bail!("The data file '{}' is empty.", cli.data.display());
        }
        let data: SeceditParameters = serde_json::from_str(&data)?;
        let policy_mode = if cli.audit {
            PolicyMode::Audit
        } else {
            PolicyMode::Enforce
        };

        let temp_dir = tempdir_in(cli.tmp)?;
        let report = Secedit::new().run(policy_mode, data, &temp_dir)?;
        let exit_code = match report.status {
            SeceditOutcome::Success | SeceditOutcome::Repaired => 0,
            _ => 1,
        };
        if cli.json {
            println!("{}", serde_json::to_string_pretty(&report)?);
        } else {
            println!("{}", &report);
        }
        process::exit(exit_code);
    }
}
