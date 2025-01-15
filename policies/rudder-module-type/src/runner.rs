//! Runner for module types.
//! Replaces CFEngine.
//!
//! FIXME: Allows configuration of the module's logger to output "normal" logs
//!        when run from the runner.

use crate::parameters::Parameters;
use crate::ModuleType0;
use rudder_commons::PolicyMode;
use serde_json::Value;

/// Runner for module types.
///
/// Acts as a replacement for CFEngine. Runs the given module with the given parameters.
pub struct Runner {
    agent_info: Parameters,
}

impl Runner {
    pub fn new(agent_info: Parameters) -> Self {
        Self { agent_info }
    }

    pub fn run(
        &self,
        mut module: Box<dyn ModuleType0>,
        mode: PolicyMode,
        parameters: Value,
    ) -> Result<(), anyhow::Error> {
        let parameters = serde_json::from_value(parameters)?;

        let result = module.init();
        //if result.is_err() {
        //    return result;
        //}

        let result = module.validate(&parameters);
        if result.is_err() {
            return result;
        }

        let result = module.check_apply(mode, &parameters);
        if result.is_err() {
            return result.map(|_| ());
        }

        Ok(())
    }
}
