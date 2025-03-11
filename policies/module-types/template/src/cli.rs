// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021 Normation SAS

use crate::Engine;

use anyhow::{Context, Result};
use clap::Parser;
use serde_json::Value;
use std::fs;
use std::fs::read_to_string;
use std::path::PathBuf;

impl std::fmt::Display for Engine {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let engine = match self {
            Engine::MiniJinja => "mini-jinja".to_string(),
            Engine::Mustache => "mustache".to_string(),
        };
        write!(f, "{}", engine)
    }
}

#[derive(Parser)]
#[command(version, about, long_about = None)]
pub struct Cli {
    /// Template engine
    #[arg(short, long, default_value_t = Engine::MiniJinja)]
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
}

impl Cli {
    pub fn run() -> Result<()> {
        let cli = Cli::parse();
        let data = read_to_string(&cli.data)
            .with_context(|| format!("Failed to load data {}", cli.data.display()))?;

        let value: Value = serde_json::from_str(&data)?;
        let output = cli
            .engine
            .render(Some(cli.template.as_path()), None, value)?;

        fs::write(&cli.out, output)
            .with_context(|| format!("Failed to write file {}", cli.out.display()))?;

        Ok(())
    }
}
