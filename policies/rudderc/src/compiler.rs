// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2022 Normation SAS

use std::{
    collections::HashMap,
    path::{Path, PathBuf},
};

use anyhow::{anyhow, bail, Context, Result};
use log::warn;
use rudder_commons::{is_canonified, Target};

use crate::ir::technique::{Block, BlockReportingMode, Parameter};
use crate::{
    backends::{backend, metadata_backend, Backend},
    doc,
    frontends::{
        methods::{method::MethodInfo, reader::read_lib},
        yaml,
    },
    ir::technique::{ItemKind, Method},
    logs::ok_output,
};

pub const RESOURCES_DIR: &str = "resources";

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
    methods_metadata(&mut policy.items, methods)?;

    for p in policy.parameters.as_slice() {
        check_parameter(p)?;
    }

    // TODO other checks and optimizations here

    let resources_path = input.parent().unwrap().join(RESOURCES_DIR);
    backend(target).generate(policy, resources_path.as_path())
}

/// Compile metadata file
pub fn metadata(input: &Path) -> Result<String> {
    let policy = yaml::read(input)?;
    ok_output(
        "Generating metadata",
        format!("{} v{} ({})", policy.name, policy.version, input.display()),
    );
    let resources_path = input.parent().unwrap().join(RESOURCES_DIR);
    metadata_backend().generate(policy, resources_path.as_path())
}

/// Compute the output of the JSON file for the webapp
///
/// It replaces the legacy `generic_methods.json` produced by `ncf.py`.
pub fn methods_description(libraries: &[PathBuf]) -> Result<String> {
    let methods = read_methods(libraries)?;
    ok_output("Generating", "modules description".to_owned());
    // FIXME: sort output to limit changes
    serde_json::to_string_pretty(&methods).context("Serializing modules")
}

pub fn methods_documentation(libraries: &[PathBuf]) -> Result<String> {
    let methods = read_methods(libraries)?;
    ok_output("Generating", "modules documentation".to_owned());
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
    modules: &mut Vec<ItemKind>,
    info: &'static HashMap<String, MethodInfo>,
) -> Result<()> {
    for r in modules {
        match r {
            ItemKind::Method(m) => {
                m.info = Some(
                    info.get(&m.method)
                        .ok_or_else(|| anyhow!("Unknown method '{}'", m.method))?,
                );
                if m.name.is_empty() {
                    m.name = m.info.unwrap().name.clone();
                }
                check_method(m)?;
            }
            ItemKind::Block(b) => {
                check_block(b)?;
                methods_metadata(&mut b.items, info)?
            }
            ItemKind::Module(_) => todo!(),
        };
    }
    Ok(())
}

/// Check technique parameter consistency
fn check_parameter(param: &Parameter) -> Result<()> {
    if !is_canonified(&param.name) {
        bail!(
            "Technique parameter name '{}' must be canonified",
            param.name
        )
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

/// Check block consistency
fn check_block(block: &Block) -> Result<()> {
    match &block.reporting.mode {
        BlockReportingMode::Focus => {
            if block.reporting.id.is_none() {
                bail!("Missing id of focused report in block '{}'", block.name)
            }
        }
        m => {
            if block.reporting.id.is_some() {
                bail!(
                    "Reporting mode {} does not expect an id in block '{}'",
                    m,
                    block.name
                )
            }
        }
    }
    Ok(())
}
