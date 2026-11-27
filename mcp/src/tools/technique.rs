// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

//! A technique version, as Rudder sends it, shared by the tools reading techniques

use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

use super::{UNEXPECTED, data, segment};
use crate::api::CallerApi;

#[derive(Deserialize)]
struct Techniques {
    techniques: [TechniqueResponse; 1],
}

/// `GET techniques/{id}/{version}?format=yaml`: an editor technique is only its YAML, a built-in
/// one only its metadata
#[derive(Deserialize)]
#[serde(untagged)]
enum TechniqueResponse {
    Editor { content: String },
    BuiltIn(Technique),
}

/// One shape for every technique: the metadata, plus the YAML definition of editor techniques
#[derive(Deserialize, Serialize)]
pub(super) struct Technique {
    id: String,
    name: String,
    version: String,
    /// `built-in` or `editor`
    source: String,
    /// The `technique.yml`, editor techniques only
    #[serde(default, skip_serializing_if = "Option::is_none")]
    content: Option<String>,
    /// From the YAML, editor techniques only
    #[serde(skip)]
    params: Vec<TechniqueParameter>,
}

/// The parts of an editor technique's YAML used here, the rest is passed through as is
#[derive(Deserialize)]
struct TechniqueYaml {
    name: String,
    #[serde(default)]
    params: Vec<TechniqueParameter>,
}

#[derive(Deserialize)]
struct TechniqueParameter {
    id: String,
    name: String,
}

impl Technique {
    /// A technique version, with the right Rudder requires for it on 403
    pub async fn get(api: &CallerApi, id: &str, version: &str) -> Result<Self> {
        let path = format!("techniques/{}/{}", segment(id)?, segment(version)?);
        let body = api
            .get(&path, &[("format", "yaml")], "technique_read")
            .await?;
        let [technique] = data::<Techniques>(&body)?.techniques;
        Ok(match technique {
            TechniqueResponse::BuiltIn(technique) => technique,
            TechniqueResponse::Editor { content } => {
                let yaml: TechniqueYaml = serde_yaml::from_str(&content)
                    .context(UNEXPECTED)
                    .context("could not read the technique YAML")?;
                Technique {
                    // From the request: in the YAML, an unquoted version (`2.0`) is a number
                    id: id.to_owned(),
                    version: version.to_owned(),
                    name: yaml.name,
                    source: "editor".to_owned(),
                    content: Some(content),
                    params: yaml.params,
                }
            }
        })
    }

    /// The parameter names, by the name rudderc gives their directive variable: the uppercased
    /// parameter id. Empty for a built-in technique.
    pub fn parameter_names(&self) -> HashMap<String, &str> {
        self.params
            .iter()
            .map(|p| (p.id.to_uppercase(), p.name.as_str()))
            .collect()
    }
}
