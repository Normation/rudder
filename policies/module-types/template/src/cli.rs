use crate::Engine;

use anyhow::{Context, Result};
use gumdrop::Options;
use serde_json::Value;
use std::fs;
use std::fs::read_to_string;
use std::path::PathBuf;
use std::str::FromStr;

impl FromStr for Engine {
    type Err = String;
    fn from_str(s: &str) -> Result<Self, Self::Err> {
        match s.to_lowercase().as_str() {
            "minijinja" => Ok(Engine::MiniJinja),
            "mustache" => Ok(Engine::Mustache),
            _ => Err(format!("Unknown engine: {}", s)),
        }
    }
}

#[derive(Debug, Options, Default)]
pub struct Cli {
    #[options(help = "print help message")]
    help: bool,

    #[options(help = "template engine", default = "minijinja")]
    engine: Engine,

    #[options(required, help = "source template")]
    template: PathBuf,

    #[options(required, help = "source data file")]
    data: PathBuf,

    #[options(required, help = "destination path")]
    out: PathBuf,
}

impl Cli {
    pub fn run() -> Result<()> {
        let opts = Self::parse_args_default_or_exit();

        let data = read_to_string(opts.data)?;
        let value: Value = serde_json::from_str(&data)?;

        let output = opts
            .engine
            .render(Some(opts.template.as_path()), None, value)?;

        fs::write(&opts.out, output)
            .with_context(|| format!("Failed to write file {}", opts.out.display()))?;

        Ok(())
    }
}
