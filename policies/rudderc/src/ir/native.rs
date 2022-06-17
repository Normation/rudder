// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2022 Normation SAS

//! A native resource_type/state represents a leaf item that will be interpreted directly by the
//! generators.
//!
//! It should have an interface generalizing the generic methods interface.
//!
//! We will probably need to keep specialized info available for each type for the generator.
//!
//! The second example beyond generic methods could be custom promise types generated directly as CFEngine
//! promises.
//!
//! Custom promise type could use rudder-specific additionnal arguments in promise parameters.
//!
//! eg:
//! rudder version
//! tmp dir
//!
//!

use std::{collections::HashMap, path::PathBuf};

use serde::{Deserialize, Serialize};

use rudder_commons::{Constraints, ParameterType, Target};

pub struct Lib {
    data: HashMap<String, State>,
}

pub enum StateTarget {
    Resource,
    /// Implicit world resource_type, without parameters
    Universe,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Resource {
    pub name: String,
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
