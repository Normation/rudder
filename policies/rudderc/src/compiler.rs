// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2022 Normation SAS

use std::{
    collections::HashMap,
    path::{Path, PathBuf},
};

use anyhow::{anyhow, bail, Context, Result};
use log::warn;
use rudder_commons::Target;

use crate::{
    backends::{backend, metadata_backend, Backend},
    doc,
    frontends::{
        methods::{method::MethodInfo, reader::read_lib},
        yaml,
    },
    ir::technique::{Method, ResourceKind},
    logs::ok_output,
};

fn read_methods(libraries: &[PathBuf]) -> Result<HashMap<String, MethodInfo>> {
    let mut methods = HashMap::new();
    for library in libraries {
        let add = read_lib(library)?;
        let len = add.len();
        for m in add {
            methods.insert(m.bundle_name.clone(), m);
        }
        ok_output("Read", format!("{} methods ({})", len, library.display()))
    }
    Ok(methods)
}

/// Compute the output of the file
pub fn compile(libraries: &[PathBuf], input: &Path, target: Target) -> Result<String> {
    let mut policy = yaml::read(input)?;
    let methods = read_methods(libraries)?;
    let methods = Box::new(methods);
    // Get a static reference to allow easier usage. Methods don't change
    // during execution.
    let methods: &'static mut HashMap<String, MethodInfo> = Box::leak(methods);

    ok_output(
        "Compiling",
        format!(
            "{} v{} [{}] ({})",
            policy.name,
            policy.version,
            target,
            input.display()
        ),
    );

    // Inject methods info into policy
    // Also check consistency (parameters, constraints, etc.)
    methods_metadata(&mut policy.resources, methods)?;

    // TODO other checks and optimizations here

    backend(target).generate(policy)
}

/// Compile metadata file
pub fn metadata(input: &Path) -> Result<String> {
    let policy = yaml::read(input)?;
    ok_output(
        "Generating metadata",
        format!("{} v{} ({})", policy.name, policy.version, input.display()),
    );
    metadata_backend().generate(policy)
}

/// Compute the output of the JSON file for the webapp
///
/// It replaces the legacy `generic_methods.json` produced by `ncf.py`.
pub fn methods_description(libraries: &[PathBuf]) -> Result<String> {
    let methods = read_methods(libraries)?;
    ok_output("Generating", "resources description".to_owned());
    // FIXME: sort output to limit changes
    serde_json::to_string_pretty(&methods).context("Serializing resources")
}

pub fn methods_documentation(libraries: &[PathBuf]) -> Result<String> {
    let methods = read_methods(libraries)?;
    ok_output("Generating", "resources documentation".to_owned());
    // Sort methods
    let mut methods: Vec<_> = methods.into_iter().collect();
    methods.sort_by(|x, y| x.0.cmp(&y.0));

    let mut out = String::new();
    for (_, m) in methods {
        out.push_str(&doc::render(m)?);
    }
    Ok(out)
}

/// Inject metadata information into method calls
fn methods_metadata(
    resources: &mut Vec<ResourceKind>,
    info: &'static HashMap<String, MethodInfo>,
) -> Result<()> {
    for r in resources {
        match r {
            ResourceKind::Method(m) => {
                m.info = Some(
                    info.get(&m.method)
                        .ok_or_else(|| anyhow!("Unknown method '{}'", m.method))?,
                );
                if m.name.is_empty() {
                    m.name = m.info.unwrap().name.clone();
                }
                check_method(m)?;
            }
            ResourceKind::Block(b) => methods_metadata(&mut b.resources, info)?,
            ResourceKind::Resource(_) => (),
        };
    }
    Ok(())
}

/// Check method call consistency
fn check_method(method: &mut Method) -> Result<()> {
    for p in &method.info.unwrap().parameter {
        // Empty value if missing and allow_empty_string
        match method.params.get(&p.name) {
            Some(_) => (),
            None if p.constraints.allow_empty_string => {
                method.params.insert(p.name.clone(), "".to_string());
            }
            _ => bail!("Missing parameter in '{}': '{}'", method.name, p.name),
        }
        // Now let's check constraints!
        //
        // We skip values containing variables, using the `${` `}` markers
        let value = method.params.get(&p.name).unwrap();
        if !value.contains("${") {
            p.constraints.is_valid(value).context(format!(
                "Invalid parameter in '{}': '{}'",
                method.name.clone(),
                p.name.clone()
            ))?;
        }
    }
    // Now let's check for unexpected parameters
    for p_name in method.params.keys() {
        if !method
            .info
            .unwrap()
            .parameter
            .iter()
            .any(|info_p| info_p.name == *p_name)
        {
            warn!("Unexpected parameter '{}' in '{}'", p_name, method.name)
        }
    }
    Ok(())
}
