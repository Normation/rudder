// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2022 Normation SAS

//! Models of the classic "ncf" methods of Rudder
//!
//! Uses a method (function-like) based model.

// Parseur de metadata à partir d'un .cf
// C'est quoi l'interface d'une generic method ?
// Faire la conversion vers une ressource

// transformer en "native resources" qui sont aussi définissables
// à la main

use std::path::PathBuf;

use serde::{Deserialize, Serialize};

use crate::Target;

/// metadata about a "ncf" CFEngine/DSC method
///
/// Leaf yaml implemented by ncf
#[derive(Debug, PartialEq, Serialize, Deserialize)]
pub struct Method {
    pub name: String,
    pub description: String,
    /// Markdown formatted documentation
    pub documentation: String,
    pub supported_targets: Vec<Target>,
    pub class_prefix: String,
    pub class_parameter_index: String,
    pub source: PathBuf,
    pub deprecated: Option<String>,
    /// Renamed method
    pub renamed: Option<String>,
}

#[derive(Debug, PartialEq, Serialize, Deserialize)]
pub struct Parameter {
    pub name: String,
    pub description: String,
    pub constraints: Vec<Constraint>,
}

#[derive(Debug, PartialEq, Serialize, Deserialize)]
pub enum Constraint {
    AllowEmpty,
    AllowWhitespace,
    Select(Vec<String>),
    Regex(String),
    MaxLength(usize),
}
