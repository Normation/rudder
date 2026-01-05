use anyhow::{Context, Result, bail};
use clap::Parser;
use std::{fs::read_to_string, path::PathBuf, process};
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
    /// Path for temporary files
    #[arg(short, long)]
    tmp: PathBuf,
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

        let tmpdir = &cli.tmp.to_string_lossy().to_string();
        let exit_code = match secedit::Secedit::new(tmpdir)?.run(data, cli.audit)? {
            secedit::Outcome::Success => 0,
            secedit::Outcome::NonCompliant => 1,
        };
        process::exit(exit_code);
    }
}
