// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use std::path::Path;

use super::Backend;
use crate::generate_directive::GenerateDirective;
use crate::ir::technique::ForeachResolvedState;
use crate::{
    backends::unix::{
        cfengine::{bundle::Bundle, promise::Promise},
        ncf::{dry_run_mode, method_call::method_call, technique::Technique},
    },
    ir::{
        self,
        condition::Condition,
        technique::{ItemKind, TechniqueId},
    },
};
use anyhow::Result;
use askama::Template;
use indexmap::IndexMap;
use rudder_commons::PolicyMode;
use tracing::trace;
// TODO support macros at the policy or bundle level
// this will allow conditionals on the agent version
// and using more recent features while keeping compatibility

pub mod cfengine;
pub mod ncf;

#[derive(Template)]
#[template(path = "prelude.cf.askama", escape = "none")]
pub struct Prelude {
    technique_id: String,
    directive_id: String,
    library_path: Option<String>,
    parameters_path: Option<String>,
    temp_dir: Option<String>,
    datastate_path: Option<String>,
    work_dir: Option<String>,
}

impl Prelude {
    pub fn new(technique: &ir::Technique) -> Self {
        Prelude {
            technique_id: technique.id.to_string(),
            directive_id: "directive_id".to_string(),
            library_path: None,
            parameters_path: None,
            temp_dir: None,
            datastate_path: None,
            work_dir: None,
        }
    }
    pub fn compute(&self, params: Vec<String>) -> String {
        let init = Promise::usebundle("rudder_test_init", None, vec![]);
        let policy_mode = Promise::usebundle(
            "set_dry_run_mode",
            None,
            vec!["${rudder_test_init.dry_run}".to_string()],
        );
        //   "CIS audit/CIS 9.1 configure cron"  usebundle => set_dry_run_mode("true");
        let technique_call = Promise::usebundle(
            self.technique_id.clone(),
            None,
            params
                .iter()
                .map(|p| cfengine::quoted(&format!("${{rudder_test_init.test_case[params][{p}]}}")))
                .collect(),
        );
        let call = Bundle::agent("main").promise_group(vec![init, policy_mode, technique_call]);

        format!("{}\n{}", self.render().unwrap(), call)
    }
}
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
impl GenerateDirective for Unix {
    fn generate_directive(
        &self,
        technique: ir::Technique,
        directive_id: &str,
        rule_id: &str,
        params: IndexMap<String, String>,
        policy_mode: PolicyMode,
    ) -> Result<String> {
        //  "promiser" usebundle => rudder_reporting_context_v4("directive_id","rule_id","technique_name","component_name","component_key","report_id");
        let reporting_context = Promise::usebundle(
            "rudder_reporting_context_v4",
            None,
            vec![
                directive_id,
                rule_id,
                &technique.name,
                "",
                "",
                &format!("{}{}", directive_id, rule_id),
            ]
            .into_iter()
            .map(cfengine::quoted)
            .collect(),
        );
        let policy_mode = Promise::usebundle(
            "set_dry_run_mode",
            None,
            match policy_mode {
                PolicyMode::Enforce => {
                    vec!["false".to_string()]
                }
                PolicyMode::Audit => {
                    vec!["true".to_string()]
                }
            },
        );
        //   "CIS audit/CIS 9.1 configure cron"  usebundle => set_dry_run_mode("true");
        let technique_call = Promise::usebundle(
            technique.id.clone().to_string(),
            None,
            params.iter().map(|(_, v)| cfengine::quoted(v)).collect(),
        );
        let call = Bundle::agent("main").promise_group(vec![
            reporting_context,
            policy_mode,
            technique_call,
        ]);
        Ok(format!("{}", call))
    }
}

impl Backend for Unix {
    fn generate(
        &self,
        technique: ir::Technique,
        resources: &Path,
        standalone: bool,
    ) -> Result<String> {
        fn resolve_module(
            r: ItemKind,
            context: Condition,
            technique_id: &TechniqueId,
        ) -> Result<Vec<(Promise, Option<Bundle>)>> {
            match r {
                ItemKind::Block(r) => {
                    let mut calls: Vec<(Promise, Option<Bundle>)> = vec![];
                    if let Some(x) = dry_run_mode::push_policy_mode(r.policy_mode_override) {
                        calls.push((x.if_condition("pass3"), None))
                    }
                    for inner in r.items {
                        calls.extend(resolve_module(
                            inner,
                            context.and(&r.condition),
                            technique_id,
                        )?);
                    }
                    if let Some(x) = dry_run_mode::pop_policy_mode(r.policy_mode_override) {
                        calls.push((x.if_condition("pass3"), None))
                    }
                    Ok(calls)
                }
                ItemKind::Method(r) => {
                    // As some bundle generation optimizations are done later in the generation if
                    // the context is resolvable at compile time (always false or always true),
                    // we need to force the context of the main branches of forks to be non
                    // resolvable at compile time by making it an expression
                    let computed_context = match r.resolved_foreach_state {
                        Some(ForeachResolvedState::Main) => {
                            Condition::Expression(context.to_string())
                        }
                        _ => context,
                    };
                    let method: Vec<(Promise, Option<Bundle>)> =
                        vec![method_call(technique_id, r, computed_context)?];
                    Ok(method)
                }
                _ => todo!(),
            }
        }

        // main bundle containing the methods
        let mut main_bundle = Bundle::agent(technique.id.clone())
            .parameters(technique.params.iter().map(|p| p.name.clone()).collect());
        // separate bundles for each method call
        let mut call_bundles = vec![];
        if !Unix::list_resources(resources)?.is_empty() {
            main_bundle.add_promise_group(vec![Promise::string(
                "resources_dir",
                "${this.promise_dirname}/resources",
            )]);
        };
        main_bundle.add_promise_group(vec![
            Promise::class_expression("pass3", "pass2"),
            Promise::class_expression("pass2", "pass1"),
            Promise::class_expression("pass1", "any"),
        ]);
        for item in &technique.items {
            for call in resolve_module(item.clone(), Condition::Defined, &technique.id)? {
                let (use_bundle, bundle) = call;
                main_bundle.add_promise_group(vec![use_bundle]);
                call_bundles.push(bundle)
            }
        }
        let cf_technique = Technique::new()
            .name(&technique.name)
            .version(&technique.version)
            .bundle(main_bundle)
            .bundles(call_bundles.into_iter().flatten().collect());
        trace!("Generated policy:\n{:#?}", cf_technique);

        if standalone {
            let technique_params: Vec<String> = technique
                .params
                .iter()
                .map(|p| p.name.to_string())
                .collect();
            let prelude = Prelude::new(&technique);
            Ok(format!(
                "{}\n{}",
                prelude.compute(technique_params),
                cf_technique
            ))
        } else {
            Ok(cf_technique.to_string())
        }
    }
}
