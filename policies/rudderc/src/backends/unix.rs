// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use anyhow::Result;
use log::trace;

use super::Backend;
use crate::{
    backends::unix::{
        cfengine::{bundle::Bundle, promise::Promise},
        ncf::technique::Technique,
    },
    ir::{self, condition::Condition, technique::ItemKind},
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
}

impl Backend for Unix {
    fn generate(&self, technique: ir::Technique) -> Result<String> {
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
        let mut main_bundle = Bundle::agent(technique.id.clone());
        // separate bundles for each method call
        let mut call_bundles = vec![];
        if !technique.files.is_empty() {
            main_bundle.add_promise_group(vec![Promise::string(
                "modules_dir",
                "${this.promise_dirname}/modules",
            )]);
        };
        for item in technique.items {
            for call in resolve_module(item, Condition::Defined)? {
                let (use_bundle, bundle) = call;
                main_bundle.add_promise_group(vec![use_bundle]);
                call_bundles.push(bundle)
            }
        }
        let technique = Technique::new()
            .name(technique.name)
            .version(technique.version)
            .bundle(main_bundle)
            .bundles(call_bundles);
        trace!("Generated policy:\n{:#?}", technique);
        Ok(technique.to_string())
    }
}
