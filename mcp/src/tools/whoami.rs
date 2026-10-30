// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

//! The caller's Rudder API account, from the token check

use axum::http::request::Parts;
use rmcp::{handler::server::tool::Extension, tool, tool_router};
use serde_json::json;

use super::{Rudder, caller};

#[tool_router(router = whoami_router, vis = "pub(super)")]
impl Rudder {
    #[tool(
        description = "Who is calling: the Rudder API account behind the caller's token, and its rights",
        annotations(read_only_hint = true)
    )]
    fn whoami(&self, Extension(parts): Extension<Parts>) -> Result<String, String> {
        let account = &caller(&parts)?.account;
        let rights = account.authorization_type;
        Ok(json!({
            "id": account.id,
            "name": account.name,
            "rights": rights.as_str(),
            "rightsDescription": rights.describe(),
        })
        .to_string())
    }
}
