// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2022 Normation SAS

use serde::{Deserialize, Serialize};
use serde_yaml::Value;

#[derive(Debug, PartialEq, Serialize, Deserialize)]
pub struct Policy {
    pub format: usize,
    pub name: String,
    pub version: String,
    pub description: Option<String>,
    pub resources: Vec<Resource>,
}

// LeafResource ?

#[derive(Debug, PartialEq, Serialize, Deserialize)]
pub struct Resource {
    pub name: String,
    // named params?
    pub params: Vec<String>,
    // contains either states or resources
    pub states: Vec<State>,
}

#[derive(Debug, PartialEq, Serialize, Deserialize)]
pub struct State {
    pub name: String,
    pub id: String,
    // TODO specific type with custom deserializer that check validity
    // class regex or variables
    pub condition: String,
    pub meta: Value,
    pub report_component: String,
    // comes from stdlib
    pub report_parameter: String,
    // named params?
    pub params: Vec<String>,
}
