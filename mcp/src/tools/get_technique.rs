// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

use anyhow::{Result, bail};
use axum::http::request::Parts;
use rmcp::{
    handler::server::{tool::Extension, wrapper::Parameters},
    schemars, tool, tool_router,
};
use serde::Deserialize;

use super::{Rudder, caller, data, technique::Technique, tool_error};
use crate::api::CallerApi;

#[derive(Debug, Deserialize, schemars::JsonSchema)]
#[serde(deny_unknown_fields)]
struct TechniqueQuery {
    /// Technique id, e.g. `ntp_configuration` (not its name).
    technique: String,
    /// Technique version, e.g. `1.0`. Needed only when the technique has several.
    version: Option<String>,
}

/// `GET techniques/{id}`: every version of the technique, only the version read here
#[derive(Deserialize)]
struct Versions {
    techniques: Vec<Version>,
}

#[derive(Deserialize)]
struct Version {
    version: String,
}

#[tool_router(router = get_technique_router, vis = "pub(super)")]
impl Rudder {
    #[tool(
        description = "Get a technique by id: its id, name, version and source (`built-in` or `editor`). Editor techniques also have their YAML definition in `content`; Rudder has no definition of built-in ones. The version can be omitted when the technique has only one.",
        annotations(read_only_hint = true)
    )]
    async fn get_technique(
        &self,
        Extension(parts): Extension<Parts>,
        Parameters(query): Parameters<TechniqueQuery>,
    ) -> Result<String, String> {
        let api = &caller(&parts)?.api;
        let technique = technique(api, &query.technique, query.version)
            .await
            .map_err(tool_error)?;
        serde_json::to_string(&technique).map_err(|e| tool_error(e.into()))
    }
}

/// The technique, in its only version if none is given
async fn technique(api: &CallerApi, id: &str, version: Option<String>) -> Result<Technique> {
    // Checked here, as the API path's segments: a bad value is the caller's, not Rudder's
    if !plain(id) {
        bail!("Invalid technique id '{id}': use the id, not the name");
    }
    let version = match version {
        Some(version) if plain(&version) => version,
        Some(version) => bail!("Invalid technique version '{version}'"),
        None => only_version(api, id).await?,
    };
    Technique::get(api, id, &version).await
}

/// Letters, digits and the punctuation of technique ids and versions, never a whole `.` or `..`
fn plain(s: &str) -> bool {
    !matches!(s, "" | "." | "..")
        && s.chars()
            .all(|c| c.is_ascii_alphanumeric() || matches!(c, '_' | '-' | '.' | '~' | '+'))
}

async fn only_version(api: &CallerApi, id: &str) -> Result<String> {
    let body = api
        .get(&format!("techniques/{id}"), &[], "technique_read")
        .await?;
    let mut versions: Vec<_> = data::<Versions>(&body)?
        .techniques
        .into_iter()
        .map(|t| t.version)
        .collect();
    match versions.len() {
        0 => bail!("No technique '{id}' in Rudder: use the id of a technique, not its name"),
        1 => Ok(versions.remove(0)),
        _ => bail!(
            "Technique '{id}' has several versions, pass one as `version`: {}",
            versions.join(", ")
        ),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_plain_ids_and_versions_reach_the_path() {
        for ok in ["ntp_configuration", "packageManagement", "1.0", "2.1-rc1"] {
            assert!(plain(ok), "{ok}");
        }
        for bad in ["", ".", "..", "a/b", "a?b", "a%2Fb", "a b"] {
            assert!(!plain(bad), "{bad}");
        }
    }
}
