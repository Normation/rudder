// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2022 Normation SAS

use serde::{Deserialize, Serialize};
use serde_yaml::Value;
use std::collections::HashMap;

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
    pub params: HashMap<String, String>,
    pub resource_type: String,
    // contains either states or resources
    pub states: Vec<State>,
    pub id: String,
    pub reporting: Option<ReportingPolicy>
}

#[derive(Debug, PartialEq, Serialize, Deserialize)]
pub struct State {
    // TODO specific type with custom deserializer that check validity
    // class regex or variables
    pub condition: String,
    pub id: String,
    pub meta: Value,
    pub name: String,
    pub params: HashMap<String, String>,
    // comes from stdlib
    pub report_parameter: String,
    pub state_type: String,
}

#[derive(Debug, PartialEq, Serialize, Deserialize)]
pub struct ReportingPolicy {
    pub enabled: Option<bool>,
    pub compute: Option<ReportingCompute>

}

#[derive(Debug, PartialEq, Serialize, Deserialize)]
pub enum ReportingCompute {
    #[serde(rename = "worst-weighted-sum")]
    WorstCaseWeightedSum,
    #[serde(rename = "worst-one")]
    WorstCaseWeightedOne,
    #[serde(rename = "focus")]
    Focus(String),
    #[serde(rename = "default")]
    Weighted
}