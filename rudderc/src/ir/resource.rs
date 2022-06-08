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

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(untagged)]
pub enum Resource {
    BlockResource(BlockResource),
    LeafResource(LeafResource),
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct BlockResource {
    pub condition: String,
    pub name: String,
    pub params: HashMap<String, String>,
    #[serde(rename = "type")]
    pub resource_type: String,
    // contains either states or resources
    pub resources: Vec<Resource>,
    pub id: String,
    pub reporting: Option<ReportingPolicy>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct LeafResource {
    pub name: String,
    pub params: HashMap<String, String>,
    #[serde(rename = "type")]
    pub resource_type: String,
    // contains either states or resources
    pub states: Vec<State>,
    pub id: String,
    pub reporting: Option<ReportingPolicy>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
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
    #[serde(rename = "type")]
    pub state_type: String,
    pub reporting: Option<ReportingPolicy>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ReportingPolicy {
    pub enabled: bool,
    #[serde(default)]
    pub compute: ReportingCompute,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum ReportingCompute {
    #[serde(rename = "worst-case-weighted-sum")]
    WorstCaseWeightedSum,
    #[serde(rename = "worst-case-weighted-one")]
    WorstCaseWeightedOne,
    #[serde(rename = "focus")]
    Focus(String),
    #[serde(rename = "weighted")]
    Weighted,
}

impl Default for ReportingPolicy {
    fn default() -> Self {
        ReportingPolicy {
            enabled: true,
            compute: ReportingCompute::default(),
        }
    }
}

impl Default for ReportingCompute {
    fn default() -> Self {
        ReportingCompute::Weighted
    }
}
