// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021 Normation SAS

//! Specification of the resource type.
//!
//! To be serialized to `rudder_resource_type.yml`.

use std::{collections::HashMap, path::PathBuf};

use rudder_commons::{Constraints, ParameterType, Target};
use serde::{Deserialize, Serialize};

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct ResourceTypeSpecs {
    /// Comes from crate FIXME
    pub name: String,
    /// Comes from crate
    pub version: String,
    pub description: String,
    /// Markdown formatted documentation
    pub documentation: String,
    pub parameters: HashMap<String, Parameter>,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct State {
    pub name: String,
    pub description: String,
    /// Markdown formatted documentation
    pub documentation: String,
    pub supported_targets: Vec<Target>,
    pub source: Option<PathBuf>,
    pub deprecated: Option<String>,
    /// There can be a message about the cause
    pub action: Option<String>,
    pub parameters: HashMap<String, Parameter>,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Parameter {
    pub description: String,
    /// For now use directly implementations from ncf
    pub constraints: Constraints,
    pub p_type: ParameterType,
}
