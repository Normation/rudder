// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

//! The MCP prompts, one module per prompt. A prompt only builds text, from its arguments and the
//! documents embedded here: it reads nothing from Rudder, so it needs no token. The model then
//! uses the tools, with the caller's token.

mod audit_policies;

use rmcp::handler::server::router::prompt::PromptRouter;

use crate::tools::Rudder;

impl Rudder {
    /// Each prompt module has its own router
    pub(crate) fn prompt_router() -> PromptRouter<Self> {
        Self::audit_policies_router()
    }
}
