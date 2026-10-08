//! Documentation served to the model, embedded at build time from the rudderc docs, so it stays
//! versioned with rudderc and never needs copying.

use rmcp::schemars;
use serde::Deserialize;

/// Technique format reference: blocks, conditions, reporting, parameters, `foreach`, resources
pub const TECHNIQUE_SYNTAX: &str = include_str!("../../policies/rudderc/docs/src/syntax.md");

#[derive(Debug, Clone, Copy, Deserialize, schemars::JsonSchema)]
#[serde(rename_all = "kebab-case")]
pub enum DocTopic {
    /// Technique YAML format: blocks, conditions, reporting modes, parameters, foreach, resources
    TechniqueSyntax,
    /// A complete small technique
    TechniqueExample,
    /// Template module: engines, data, sandboxing, filters
    ModuleTemplate,
    /// Augeas module: editing configuration files through lenses
    ModuleAugeas,
    /// Commands module: running commands and scripts
    ModuleCommands,
    /// System updates module: patching campaigns
    ModuleSystemUpdates,
    /// Secedit module: Windows security policy
    ModuleSecedit,
}

impl DocTopic {
    pub fn content(self) -> &'static str {
        match self {
            Self::TechniqueSyntax => TECHNIQUE_SYNTAX,
            Self::TechniqueExample => {
                include_str!("../../policies/rudderc/docs/examples/ntp/technique.yml")
            }
            Self::ModuleTemplate => {
                include_str!("../../policies/rudderc/docs/src/modules/template.md")
            }
            Self::ModuleAugeas => include_str!("../../policies/rudderc/docs/src/modules/augeas.md"),
            Self::ModuleCommands => {
                include_str!("../../policies/rudderc/docs/src/modules/commands.md")
            }
            Self::ModuleSystemUpdates => {
                include_str!("../../policies/rudderc/docs/src/modules/system-updates.md")
            }
            Self::ModuleSecedit => {
                include_str!("../../policies/rudderc/docs/src/modules/secedit.md")
            }
        }
    }
}
