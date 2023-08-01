// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2022 Normation SAS

use std::{
    collections::HashSet,
    path::Path,
    sync::atomic::{AtomicBool, AtomicUsize, Ordering},
};

use anyhow::{anyhow, bail, Context, Result};
use boon::{Compiler, Schemas};
use rudder_commons::{is_canonified, logs::ok_output, methods::Methods, Target};
use serde_json::Value;
use tracing::{error, warn};

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

// Count of user errors detected when reading the technique
static USER_ERROR_COUNT: AtomicUsize = AtomicUsize::new(0);
// Have we returned an error only based on user errors?
// Allows setting special return code.
static EXIT_ON_USER_ERROR: AtomicBool = AtomicBool::new(false);

pub fn user_error() {
    USER_ERROR_COUNT.fetch_add(1, Ordering::SeqCst);
}

pub fn exit_on_user_error() {
    EXIT_ON_USER_ERROR.store(true, Ordering::SeqCst);
}

pub fn get_user_error_count() -> usize {
    USER_ERROR_COUNT.load(Ordering::SeqCst)
}

pub fn is_exit_on_user_error() -> bool {
    EXIT_ON_USER_ERROR.load(Ordering::SeqCst)
}

/// Read technique and augment with data from libraries
///
/// Don't return early on user error but display an error message
pub fn read_technique(methods: &'static Methods, input: &str) -> Result<Technique> {
    // JSON schema validity
    let schema_url = "https://docs.rudder.io/schemas/technique.schema.json";
    let schema: Value = serde_json::from_str(include_str!("./technique.schema.json")).unwrap();
    let mut schemas = Schemas::new();
    let mut compiler = Compiler::new();
    compiler.add_resource(schema_url, schema).unwrap();
    let sch_index = compiler.compile(schema_url, &mut schemas).unwrap();
    // then load technique file
    let instance: Value = serde_yaml::from_str(input)?;
    // ... and validate
    let result = schemas.validate(&instance, sch_index);
    if let Err(error) = result {
        // :# gives error details
        error!("{error:#}");
        user_error();
    }

    // Deserialize into `Technique`
    // Here return early as we can't do much if parsing failed,
    // plus serde already displays as many errors as possible
    let mut policy = frontends::read(input)?;
    // Inject methods info into policy
    // Also check consistency (parameters, constraints, etc.)
    methods_metadata(&mut policy.items, methods)?;
    for p in &mut policy.params {
        check_parameter(p)?;
    }
    check_ids_unicity(&policy)?;
    check_parameter_unicity(&policy)?;

    let error_count = get_user_error_count();
    if error_count > 0 {
        exit_on_user_error();
        bail!("{error_count} error(s) were encountered when reading technique");
    }
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
        error!(
            "Technique parameter name '{}' must be canonified",
            param.name
        );
        user_error();
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
            _ => {
                error!("Missing parameter in '{}': '{}'", method.name, p.name);
                user_error()
            }
        }
        // Now let's check constraints!
        //
        // We skip values containing variables, using the `${` `}` markers
        let value = method.params.get(&p.name).unwrap();
        if !value.contains("${") {
            let res = p.constraints.is_valid(value).context(format!(
                "Invalid parameter in '{}': '{}'",
                method.name.clone(),
                p.name.clone()
            ));
            if let Err(e) = res {
                error!("{:?}", e);
                user_error()
            }
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
                    error!(
                        "Unknown id '{}' of focused report in block '{}'",
                        id, block.name
                    );
                    user_error()
                }
            } else {
                error!("Missing id of focused report in block '{}'", block.name);
                user_error()
            }
        }
        m => {
            if block.reporting.id.is_some() {
                error!(
                    "Reporting mode {} does not expect an id in block '{}'",
                    m, block.name
                );
                user_error()
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
            error!("Duplicate id '{}'", &id);
            user_error()
        }
    }
    Ok(())
}

/// Ensure all parameters have a unique name
fn check_parameter_unicity(technique: &Technique) -> Result<()> {
    let mut names = HashSet::new();
    for name in technique.params.iter().map(|p| p.name.clone()) {
        if !names.insert(name.clone()) {
            error!("Duplicate parameter name '{}'", &name);
            user_error()
        }
    }
    Ok(())
}
