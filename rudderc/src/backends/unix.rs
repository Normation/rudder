// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use anyhow::Result;
use log::trace;

use crate::{
    backends::unix::{
        cfengine::{bundle::Bundle, promise::Promise},
        ncf::{method_call::MethodCall, technique::Technique},
    },
    ir::{self, resource::Resource},
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

        fn resolve_resource(r: Resource, context: &str) -> Result<Vec<MethodCall>> {
            match r {
                Resource::BlockResource(r) => {
                    let mut result: Vec<MethodCall> = vec![];
                    for inner in r.resources {
                        result.extend(resolve_resource(
                            inner,
                            format!("({}).({})", r.condition, context).as_ref(),
                        )?);
                    }
                    Ok(result)
                }
                Resource::LeafResource(r) => {
                    let mut branch_result: Vec<MethodCall> = vec![];
                    for state in r.states {
                        // sort the params in arbitrary order to make the tests more determinist
                        // must be removed when we the parameters ordering will be implemented
                        // Add quotes around the parameters as the bundle call expects them.
                        let method_params = {
                            let mut vec = state.params.values().cloned().collect::<Vec<String>>();
                            vec = vec
                                .iter()
                                .map(|x| format!("\"{}\"", x))
                                .collect::<Vec<String>>();
                            vec.sort();
                            vec
                        };
                        let method = MethodCall::new()
                            .id(state.id.clone())
                            .resource(r.resource_type.clone())
                            .state(state.state_type.clone())
                            .parameters(method_params)
                            .report_parameter(state.report_parameter.clone())
                            .report_component(state.name.clone())
                            .condition(format!("({}).({})", context, state.condition.clone()))
                            // assume everything is supported
                            .supported(true)
                            // serialize state source as yaml in comment
                            .source(serde_yaml::to_string(&state)?);
                        branch_result.push(method);
                    }
                    Ok(branch_result)
                }
            }
        }

        for resource in policy.resources {
            bundle.add_promise_group(
                resolve_resource(resource, "any")?
                    .into_iter()
                    .flat_map(|x| -> Vec<Promise> { x.build() })
                    .collect(),
            )
        }

        let policy = Technique::new()
            .name(policy.name)
            .version(policy.version)
            .bundle(bundle);

        trace!("Generated policy:\n{:#?}", policy);

        Ok(policy.to_string())
    }
}
