// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use anyhow::Result;
use log::trace;

use crate::{
    backends::unix::{
        cfengine::{bundle::Bundle, promise::Promise},
        ncf::{method_call::MethodCall, technique::Technique},
    },
    ir,
};

use super::Backend;

// TODO support macros at the policy or bundle level
// this will allow conditionals on agent version
// and using more recent features while keeping compatibility

pub mod cfengine;
pub mod ncf;

pub struct Unix;

impl Default for Unix {
    fn default() -> Self {
        Self::new()
    }
}

impl Unix {
    pub fn new() -> Self {
        Self
    }
}

impl Backend for Unix {
    fn generate(&self, policy: ir::Policy) -> Result<String> {
        let mut bundle = Bundle::agent(policy.name.clone()).promise_group(vec![Promise::string(
            "resources_dir",
            "${this.promise_dirname}/resources",
        )]);

        for resource in policy.resources {
            for state in resource.states {
                let method = MethodCall::new()
                    .id(state.id.clone())
                    .resource(resource.name.clone())
                    .state(state.name.clone())
                    .parameters(state.params.clone())
                    .report_parameter(state.report_parameter.clone())
                    .report_component(state.report_component.clone())
                    .condition(state.condition.clone())
                    // assume everything is supported
                    .supported(true)
                    // serialize state source as yaml in comment
                    .source(serde_yaml::to_string(&state)?)
                    .build();
                bundle.add_promise_group(method);
            }
        }

        let policy = Technique::new()
            .name(policy.name)
            .version(policy.version)
            .bundle(bundle);

        trace!("Generated policy:\n{:#?}", policy);

        Ok(policy.to_string())
    }
}
