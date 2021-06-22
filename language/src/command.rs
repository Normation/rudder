// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

pub mod compile;
pub use compile::compile;
mod save;
pub use save::save;
mod technique_generate;
pub use technique_generate::technique_generate;
mod technique_read;
pub use technique_read::technique_read;

use crate::{generator::Format, technique::TechniqueFmt};

use serde::{Deserialize, Serialize};
use std::{fmt, path::PathBuf};

#[derive(Clone, Copy, PartialEq, Deserialize)]
pub enum Command {
    ReadTechnique,
    GenerateTechnique,
    Save,
    Compile,
}
impl fmt::Display for Command {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "{}",
            match self {
                Command::ReadTechnique => "read technique",
                Command::GenerateTechnique => "generate technique",
                Command::Save => "save",
                Command::Compile => "compile",
            }
        )
    }
}
impl fmt::Debug for Command {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "{}",
            match self {
                Command::ReadTechnique => "Technique reading",
                Command::GenerateTechnique => "Technique generation",
                Command::Save => "Saving",
                Command::Compile => "Compilation",
            }
        )
    }
}

#[derive(Serialize, Clone, Debug)]
pub struct CommandResult {
    pub format: Format,
    pub destination: Option<PathBuf>, // None means it will be printed to stdout
    pub content: Option<TechniqueFmt>, // None means there has been an error
}
impl CommandResult {
    /// CommandResult s can only hold either a destination file or directly the content
    /// If both respective fields hold a variable (or both do not), means there is a bug
    pub fn new(
        format: Format,
        destination: Option<PathBuf>,
        technique: Option<TechniqueFmt>,
    ) -> Self {
        Self {
            format,
            destination,
            content: technique,
        }
    }

    // (temp)
    pub fn default() -> Self {
        Self {
            format: Format::CFEngine,
            destination: None,
            content: None,
        }
    }
}
