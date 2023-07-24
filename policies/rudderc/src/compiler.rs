// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2022 Normation SAS

use std::{collections::HashSet, path::Path};

use anyhow::{anyhow, bail, Context, Result};
use log::warn;
use rudder_commons::{is_canonified, logs::ok_output, methods::Methods, Target};

use crate::{
    backends::{backend, metadata::Metadata, Backend},
    frontends,
    ir::{
        technique::{
            Block, BlockReportingMode, Id, ItemKind, Method, Parameter, ParameterType, PasswordType,
        },
        Technique,
    },
    RESOURCES_DIR,
};

/// Read technique and augment with data from libraries
pub fn read_technique(methods: &'static Methods, input: &str) -> Result<Technique> {
    let mut policy = frontends::read(input)?;
    // Inject methods info into policy
    // Also check consistency (parameters, constraints, etc.)
    methods_metadata(&mut policy.items, methods)?;
    for p in &mut policy.params {
        check_parameter(p)?;
    }
    check_ids_unicity(&policy)?;
    check_parameter_unicity(&policy)?;
    Ok(policy)
}

/// Compute the output of the file
pub fn compile(policy: Technique, target: Target, src: &Path, standalone: bool) -> Result<String> {
    ok_output(
        "Compiling",
        format!("{} v{} [{}]", policy.name, policy.version, target,),
    );
    let resources_path = src.parent().unwrap().join(RESOURCES_DIR);
    backend(target).generate(policy, resources_path.as_path(), standalone)
}

/// Compile metadata file
pub fn metadata(policy: Technique, src: &Path) -> Result<String> {
    ok_output(
        "Generating",
        format!("{} v{} [Metadata]", policy.name, policy.version,),
    );
    let resources_path = src.parent().unwrap().join(RESOURCES_DIR);
    Metadata.generate(policy, resources_path.as_path(), false)
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
///
/// Fix constraints if necessary.
fn check_parameter(param: &mut Parameter) -> Result<()> {
    if !is_canonified(&param.name) {
        bail!(
            "Technique parameter name '{}' must be canonified",
            param.name
        )
    }
    // Only allow modern hashes if not specified
    if param._type == ParameterType::Password && param.constraints.password_hashes.is_none() {
        param.constraints.password_hashes = Some(PasswordType::acceptable())
    };
    Ok(())
}

/// Check method call consistency
fn check_method(method: &mut Method) -> Result<()> {
    for p in &method.info.unwrap().parameter {
        // Empty value if missing and allow_empty
        match method.params.get(&p.name) {
            Some(_) => (),
            None if p.constraints.allow_empty => {
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
    fn is_id_child(r: &ItemKind, id: &Id) -> bool {
        match r {
            ItemKind::Block(r) => {
                if &r.id == id {
                    true
                } else {
                    r.items.iter().map(|r| is_id_child(r, id)).any(|t| t)
                }
            }
            ItemKind::Method(r) => &r.id == id,
            _ => todo!(),
        }
    }

    match &block.reporting.mode {
        BlockReportingMode::Focus => {
            if let Some(ref id) = block.reporting.id {
                // check the id is valid
                if block.items.iter().map(|r| is_id_child(r, id)).all(|t| !t) {
                    bail!(
                        "Unknown id '{}' of focused report in block '{}'",
                        id,
                        block.name
                    )
                }
            } else {
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

/// Check id unicity
// TODO: Could be more efficient...
fn check_ids_unicity(technique: &Technique) -> Result<()> {
    fn get_ids(r: &ItemKind) -> Vec<Id> {
        match r {
            ItemKind::Block(r) => {
                let mut ids = vec![r.id.clone()];
                ids.extend(r.items.iter().flat_map(get_ids));
                ids
            }
            ItemKind::Method(r) => vec![r.id.clone()],
            _ => todo!(),
        }
    }
    let mut ids = HashSet::new();
    for id in technique
        .items
        .iter()
        .flat_map(get_ids)
        .chain(technique.params.iter().map(|p| p.id.clone()))
    {
        if !ids.insert(id.clone()) {
            bail!("Duplicate id '{}'", &id);
        }
    }
    Ok(())
}

/// Ensure all parameters have a unique name
fn check_parameter_unicity(technique: &Technique) -> Result<()> {
    let mut names = HashSet::new();
    for name in technique.params.iter().map(|p| p.name.clone()) {
        if !names.insert(name.clone()) {
            bail!("Duplicate parameter name '{}'", &name);
        }
    }
    Ok(())
}
