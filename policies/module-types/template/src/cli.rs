// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021 Normation SAS

use crate::{Engine, get_python_version};

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
        write!(f, "{}", engine)
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
        let output = match cli.engine {
            Engine::Mustache => Engine::mustache(Some(cli.template.as_path()), None, value)?,
            Engine::Minijinja => Engine::minijinja(Some(cli.template.as_path()), None, value)?,
            Engine::Jinja2 => {
                let tmp = tempdir()?;
                let temporary_dir = tmp.path();
                let python_version = get_python_version()?;
                Engine::jinja2(
                    Some(cli.template.as_path()),
                    None,
                    value,
                    temporary_dir,
                    &python_version,
                )?
            }
        };

        if cli.audit {
            let audited_content = fs::read_to_string(&cli.out).with_context(|| {
                format!("Failed to read audited template {}", cli.out.display())
            })?;

            if output != audited_content {
                bail!(
                    "The content in the audited template file ({}) does not match with the rendered template.",
                    cli.out.display()
                )
            }
        } else {
            fs::write(&cli.out, output)
                .with_context(|| format!("Failed to write file {}", cli.out.display()))?;
        }

        Ok(())
    }
}
