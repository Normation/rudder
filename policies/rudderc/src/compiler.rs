// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2022 Normation SAS

use std::{collections::HashMap, path::Path};

use anyhow::{anyhow, bail, Context, Result};
use log::warn;
use rudder_commons::{is_canonified, Target};

use crate::{
    backends::{backend, metadata::Metadata, Backend},
    frontends::{methods::method::MethodInfo, yaml},
    ir::{
        technique::{Block, BlockReportingMode, ItemKind, Method, Parameter},
        Technique,
    },
    logs::ok_output,
    RESOURCES_DIR,
};

pub type Methods = HashMap<String, MethodInfo>;

/// Read technique and augment with data from libraries
fn read_technique(methods: &'static Methods, input: &str) -> Result<Technique> {
    let mut policy = yaml::read(input)?;
    // Inject methods info into policy
    // Also check consistency (parameters, constraints, etc.)
    methods_metadata(&mut policy.items, methods)?;
    for p in policy.parameters.as_slice() {
        check_parameter(p)?;
    }
    Ok(policy)
}

/// Compute the output of the file
pub fn compile(
    methods: &'static Methods,
    input: &str,
    target: Target,
    src: &Path,
) -> Result<String> {
    let policy = read_technique(methods, input)?;
    ok_output(
        "Compiling",
        format!(
            "{} v{} [{}] ({})",
            policy.name,
            policy.version,
            target,
            src.display()
        ),
    );
    let resources_path = src.parent().unwrap().join(RESOURCES_DIR);
    backend(target).generate(policy, resources_path.as_path())
}

/// Compile metadata file
pub fn metadata(methods: &'static Methods, input: &str, src: &Path) -> Result<String> {
    let policy = read_technique(methods, input)?;
    ok_output(
        "Generating",
        format!(
            "{} v{} [metadata] ({})",
            policy.name,
            policy.version,
            src.display()
        ),
    );
    let resources_path = src.parent().unwrap().join(RESOURCES_DIR);
    Metadata.generate(policy, resources_path.as_path())
}

/// Inject metadata information into method calls
fn methods_metadata(modules: &mut Vec<ItemKind>, info: &'static Methods) -> Result<()> {
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
