// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use std::path::Path;

use anyhow::Result;

use tracing::trace;

use super::Backend;
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

// TODO support macros at the policy or bundle level
// this will allow conditionals on the agent version
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

    /// To call the technique in standalone policies
    fn prelude(technique_id: TechniqueId, params: Vec<String>) -> String {
        // Static content including parts of the system techniques required to run most techniques,
        // i.e. lib loading and global vars (`g.X`).
        let static_prelude = include_str!("unix/prelude.cf");
        let init = Promise::usebundle("rudder_test_init", None, None, vec![]);
        let policy_mode = Promise::usebundle(
            "set_dry_run_mode",
            None,
            None,
            vec!["${rudder_test_init.dry_run}".to_string()],
        );
        //   "CIS audit/CIS 9.1 configure cron"  usebundle => set_dry_run_mode("true");
        let technique_call = Promise::usebundle(
            technique_id.to_string(),
            None,
            None,
            params
                .iter()
                .map(|p| cfengine::quoted(&format!("${{rudder_test_init.test_case[params][{p}]}}")))
                .collect(),
        );
        let call = Bundle::agent("main").promise_group(vec![init, policy_mode, technique_call]);
        format!("{static_prelude}\n{}", call)
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
                    if let Some(x) = dry_run_mode::push_policy_mode(
                        r.policy_mode_override,
                        format!("push_policy_mode_for_block_{}", r.id),
                    ) {
                        calls.push((x.if_condition("pass3"), None))
                    }
                    for inner in r.items {
                        calls.extend(resolve_module(
                            inner,
                            context.and(&r.condition),
                            technique_id,
                        )?);
                    }
                    if let Some(x) = dry_run_mode::pop_policy_mode(
                        r.policy_mode_override,
                        format!("pop_policy_mode_for_block_{}", r.id),
                    ) {
                        calls.push((x.if_condition("pass3"), None))
                    }
                    Ok(calls)
                }
                ItemKind::Method(r) => {
                    let method: Vec<(Promise, Option<Bundle>)> =
                        vec![method_call(technique_id, r, context)?];
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
        for item in technique.items {
            for call in resolve_module(item, Condition::Defined, &technique.id)? {
                let (use_bundle, bundle) = call;
                main_bundle.add_promise_group(vec![use_bundle]);
                call_bundles.push(bundle)
            }
        }
        let cf_technique = Technique::new()
            .name(technique.name)
            .version(technique.version)
            .bundle(main_bundle)
            .bundles(call_bundles.into_iter().flatten().collect());
        trace!("Generated policy:\n{:#?}", cf_technique);
        Ok(if standalone {
            format!(
                "{}\n{}",
                Unix::prelude(
                    technique.id,
                    technique
                        .params
                        .iter()
                        .map(|p| p.name.to_string())
                        .collect()
                ),
                cf_technique
            )
        } else {
            cf_technique.to_string()
        })
    }
}
