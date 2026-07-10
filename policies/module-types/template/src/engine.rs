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

/// What template content is allowed to do at render time.
#[derive(
    Debug, Copy, Clone, PartialEq, Eq, Serialize, Deserialize, PartialOrd, Ord, ValueEnum, Default,
)]
#[serde(rename_all = "snake_case")]
pub enum Mode {
    /// No filesystem access, no escape to the host runtime.
    #[default]
    Sandboxed,
    /// No restriction; trusted templates only.
    Unrestricted,
}

impl Mode {
    pub fn is_sandboxed(self) -> bool {
        matches!(self, Mode::Sandboxed)
    }
}

impl std::fmt::Display for Mode {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let mode = match self {
            Mode::Sandboxed => "sandboxed",
            Mode::Unrestricted => "unrestricted",
        };
        write!(f, "{mode}")
    }
}

impl Engine {
    pub fn renderer(&self, python_version: Option<String>) -> Result<Box<dyn TemplateEngine>> {
        Ok(match self {
            Engine::Mustache => Box::new(mustache::MustacheEngine),
            Engine::Minijinja => Box::new(minijinja::MiniJinjaEngine::default()),
            Engine::Jinja2 => Box::new(
                jinja2::Jinja2Engine::new(python_version)
                    .context("Failed to create Jinja2 engine")?,
            ),
        })
    }
}

pub trait TemplateEngine {
    /// With `Mode::Sandboxed`, implementations must keep the render a pure
    /// function of the template and `data`: no filesystem reads besides the
    /// template file itself, no writes, no code execution on the host, and
    /// bounded compute (as strictly as the engine allows).
    fn render(
        &self,
        template_path: Option<&Path>,
        template_string: Option<&str>,
        data: &Value,
        mode: Mode,
    ) -> Result<String>;
}
