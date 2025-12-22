use anyhow::{Context, Result, bail};
use clap::Parser;
use std::{fs::read_to_string, path::PathBuf};
mod secedit;

#[derive(Parser)]
#[command(version, about, long_about = None)]
pub struct Cli {
    /// JSON data file
    #[arg(short, long)]
    data: PathBuf,
    /// Audit mode
    #[arg(short, long)]
    audit: bool,
}

impl Cli {
    pub fn run() -> Result<()> {
        let cli = Cli::parse();
        let data = read_to_string(&cli.data)
            .with_context(|| format!("Failed to read data file '{}'", cli.data.display()))?;

        if data.is_empty() {
            bail!("The data file '{}' is empty.", cli.data.display());
        }
        let data = serde_json::from_str(&data)?;

        secedit::Secedit::new()?.run(data, cli.audit)
    }
}
