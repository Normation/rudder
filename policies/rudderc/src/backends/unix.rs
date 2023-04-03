// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use std::path::Path;

use anyhow::Result;
use log::trace;

use super::Backend;
use crate::{
    backends::unix::{
        cfengine::{bundle::Bundle, promise::Promise},
        ncf::technique::Technique,
    },
    ir::{
        self,
        condition::Condition,
        technique::{Id, ItemKind},
    },
};

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

    /// To call the technique in standalone policies
    fn prelude(technique_id: Id, params: Vec<String>) -> String {
        // Static content including parts of the system tehcniques required to run most techniques,
        // i.e. lib loading and global vars (`g.X`).
        let static_prelude = include_str!("unix/prelude.cf");
        let init = Promise::usebundle("rudder_test_init", None, None, vec![]);
        let technique = Promise::usebundle(
            technique_id.to_string(),
            None,
            None,
            params
                .iter()
                .map(|p| cfengine::quoted(&format!("${{rudder_test_init.test_case[params][{p}]}}")))
                .collect(),
        );
        let call = Bundle::agent("main").promise_group(vec![init, technique]);
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
        fn resolve_module(r: ItemKind, context: Condition) -> Result<Vec<(Promise, Bundle)>> {
            match r {
                ItemKind::Block(r) => {
                    let mut calls: Vec<(Promise, Bundle)> = vec![];
                    for inner in r.items {
                        calls.extend(resolve_module(inner, context.and(&r.condition))?);
                    }
                    Ok(calls)
                }
                ItemKind::Method(r) => {
                    let method: Vec<(Promise, Bundle)> = vec![r.try_into()?];
                    Ok(method)
                }
                _ => todo!(),
            }
        }

        // main bundle containing the methods
        let mut main_bundle = Bundle::agent(technique.id.clone()).parameters(
            technique
                .parameters
                .iter()
                .map(|p| p.name.clone())
                .collect(),
        );
        // separate bundles for each method call
        let mut call_bundles = vec![];
        if !Unix::list_resources(resources)?.is_empty() {
            main_bundle.add_promise_group(vec![Promise::string(
                "resources_dir",
                "${this.promise_dirname}/resources",
            )]);
        };
        main_bundle.add_promise_group(vec![
            Promise::slist(
                "args",
                technique
                    .parameters
                    .iter()
                    .map(|p| format!("${{{}}}", &p.name))
                    .collect(),
            ),
            Promise::string_raw("report_param", r#"join("_", args)"#),
            Promise::string_raw(
                "full_class_prefix",
                format!("canonify(\"{}_${{report_param}}\")", technique.id),
            ),
            Promise::string_raw(
                "class_prefix",
                r#"string_head("${full_class_prefix}", "1000")"#,
            ),
        ]);
        for item in technique.items {
            for call in resolve_module(item, Condition::Defined)? {
                let (use_bundle, bundle) = call;
                main_bundle.add_promise_group(vec![use_bundle]);
                call_bundles.push(bundle)
            }
        }
        let cf_technique = Technique::new()
            .name(technique.name)
            .version(technique.version)
            .bundle(main_bundle)
            .bundles(call_bundles);
        trace!("Generated policy:\n{:#?}", cf_technique);
        Ok(if standalone {
            format!(
                "{}\n{}",
                Unix::prelude(
                    technique.id,
                    technique
                        .parameters
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
