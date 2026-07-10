use crate::secedit::Secedit;
use crate::secedit::{SeceditOutcome, SeceditParameters};
use anyhow::{Context, Result, bail};
use clap::{Parser, ValueEnum};
use rudder_module_type::PolicyMode;
use std::{fs::read_to_string, path::PathBuf, process};
use tempfile::tempdir_in;

#[derive(Debug, Clone, ValueEnum)]
#[value(rename_all = "lowercase")]
enum Format {
    Json,
    Human,
}
#[derive(Parser)]
#[command(version, about, long_about = None,
after_help = "NOTE: Regardless of the selected --format, any runtime or execution errors \
                  will always be printed in plain text (human-readable) to stderr."
)]
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
    /// Output format, ignored in case of error
    #[arg(short, long, value_enum, default_value_t = Format::Json)]
    format: Format,
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
        match cli.format {
            Format::Json => {
                println!("{}", serde_json::to_string_pretty(&report)?);
            }
            Format::Human => {
                println!("{}", report);
            }
        }
        process::exit(exit_code);
    }
}
