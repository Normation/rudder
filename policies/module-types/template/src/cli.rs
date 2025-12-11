// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021 Normation SAS

use crate::{Engine, compute_diff_or_warning};

use anyhow::{Context, Result, bail};
use clap::Parser;
use serde_json::Value;
use std::fs;
use std::fs::read_to_string;
use std::path::PathBuf;
use tempfile::tempdir;

impl std::fmt::Display for Engine {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let engine = match self {
            Engine::Mustache => "mustache".to_string(),
            Engine::Minijinja => "minijinja".to_string(),
            Engine::Jinja2 => "jinja2".to_string(),
        };
        write!(f, "{engine}")
    }
}

#[derive(Parser)]
#[command(version, about, long_about = None)]
pub struct Cli {
    /// Template engine
    #[arg(short, long, default_value_t = Engine::Minijinja)]
    engine: Engine,

    /// Template file
    #[arg(short, long)]
    template: PathBuf,

    /// JSON data file
    #[arg(short, long)]
    data: PathBuf,

    /// Output file
    #[arg(short, long)]
    out: PathBuf,

    /// Audit mode
    #[arg(short, long)]
    audit: bool,

    /// Controls output of diffs
    #[arg(short, long)]
    show_content: bool,
}

impl Cli {
    pub fn run() -> Result<()> {
        let cli = Cli::parse();
        let data = read_to_string(&cli.data)
            .with_context(|| format!("Failed to load data {}", cli.data.display()))?;
        if data.is_empty() {
            bail!("The data file '{}' is empty.", cli.data.display());
        }

        let value: Value = serde_json::from_str(&data)?;
        let tmp = tempdir()?;
        let temporary_dir = tmp.path();
        let renderer = cli.engine.renderer(temporary_dir, None)?;
        let output = renderer.render(Some(cli.template.as_path()), None, value)?;

        let already_present = cli.out.exists();

        let mut content = String::new();
        let already_correct = if already_present {
            content = read_to_string(&cli.out)
                .with_context(|| format!("Failed to read file {}", cli.out.display()))?;
            content == output
        } else {
            false
        };

        let reported_diff = compute_diff_or_warning(
            &content,
            &output,
            cli.out.to_string_lossy().as_ref(),
            cli.show_content,
        );

        match (already_correct, already_present, cli.audit) {
            (true, _, _) => {
                println!("Output file '{}' already correct", cli.out.display());
                return Ok(());
            }
            (false, true, true) => {
                bail!(
                    "Output file '{}' is present but content is not up to date.\ndiff:\n{reported_diff}",
                    cli.out.display()
                )
            }
            (false, false, true) => {
                bail!("Output file '{}' does not exist", cli.out.display());
            }
            (false, ap, false) => {
                fs::write(&cli.out, output)
                    .with_context(|| format!("Failed to write file '{}'", cli.out.display()))?;

                let action = if ap { "Replaced" } else { "Written new" };

                println!(
                    "{action} '{}' content from template '{}'\ndiff:\n{reported_diff}",
                    cli.out.display(),
                    cli.template.display()
                )
            }
        }
        Ok(())
    }
}
