// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021-2025 Normation SAS

use anyhow::{Context, Result};
use clap::ValueEnum;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::path::Path;

mod jinja2;
mod minijinja;
mod mustache;

#[derive(
    Debug, Copy, Clone, PartialEq, Eq, Serialize, Deserialize, PartialOrd, Ord, ValueEnum, Default,
)]
#[serde(rename_all = "snake_case")]
pub enum Engine {
    Mustache,
    #[default]
    Minijinja,
    Jinja2,
}

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

impl Engine {
    pub fn renderer(
        &self,
        temporary_dir: &Path,
        python_version: Option<String>,
    ) -> Result<Box<dyn TemplateEngine>> {
        Ok(match self {
            Engine::Mustache => Box::new(mustache::MustacheEngine),
            Engine::Minijinja => Box::new(minijinja::MiniJinjaEngine),
            Engine::Jinja2 => Box::new(
                jinja2::Jinja2Engine::new(temporary_dir.to_path_buf(), python_version)
                    .context("Failed to create Jinja2 engine")?,
            ),
        })
    }
}

pub trait TemplateEngine {
    fn render(
        &self,
        template_path: Option<&Path>,
        template_src: Option<&str>,
        data: &Value,
    ) -> Result<String>;
}
