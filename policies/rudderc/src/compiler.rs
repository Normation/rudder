// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2022 Normation SAS

use std::{
    collections::HashSet,
    path::Path,
    sync::atomic::{AtomicBool, AtomicUsize, Ordering},
};

use anyhow::{Context, Result, anyhow, bail};
use boon::{Compiler, Schemas};
use rudder_commons::{Target, is_canonified, logs::ok_output, methods::Methods};
use serde_json::Value;
use tracing::{error, warn};

use crate::ir::technique::{DeserItem, DeserTechnique, ForeachResolvedState};
use crate::{
    RESOURCES_DIR,
    backends::{Backend, backend, metadata::Metadata},
    frontends,
    ir::{
        Technique,
        technique::{
            Block, BlockReportingMode, Id, ItemKind, Method, Parameter, ParameterType, PasswordType,
        },
        value::Expression,
    },
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
pub fn read_technique(
    methods: &'static Methods,
    input: &str,
    resolve_loops: bool,
) -> Result<Technique> {
    // Deserialize into `Technique`
    // Here return early as we can't do much if parsing failed,
    // plus serde already displays as many errors as possible
    let mut policy = frontends::read(input, resolve_loops)?;
    // Inject methods info into policy
    // Also check consistency (parameters, constraints, etc.)
    methods_metadata(&mut policy.items, methods)?;
    for p in &mut policy.params {
        check_parameter(p)?;
    }
    check_ids_unicity(&policy)?;
    check_parameter_unicity(&policy)?;

    // JSON schema validity
    // We do it in the end as error messages are a bit cryptic
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

    // Final check
    let error_count = get_user_error_count();
    if error_count > 0 {
        exit_on_user_error();
        bail!("{error_count} error(s) were encountered when reading technique");
    }
    Ok(policy)
}

// Sanity check on the output to prevent generating broken output.
// We can easily end up with non-printable chars here as double-quoted YAML strings
// interpret backslash escaped control characters.
// TODO: Move the check earlier in YAML lint
// TODO: See if quick_xml should encode these anyway
fn check_for_control_chars(s: String) -> Result<String> {
    // \n, \r and \t are allowed in XML
    match s
        .chars()
        .find(|c| c.is_ascii_control() && !['\n', '\r', '\t'].contains(c))
    {
        Some(c) => bail!(
            "Output contains a forbidden control character '{}', stopping. This can happen when using escape sequence in YAML double quotes.",
            c.escape_default()
        ),
        None => Ok(s),
    }
}

/// Compute the output of the file
pub fn compile(policy: Technique, target: Target, src: &Path, standalone: bool) -> Result<String> {
    ok_output(
        "Compiling",
        format!("{} v{} [{}]", policy.name, policy.version, target,),
    );
    let resources_path = src.parent().unwrap().join(RESOURCES_DIR);
    backend(target)
        .generate(policy, resources_path.as_path(), standalone)
        .and_then(check_for_control_chars)
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
                    m.name.clone_from(&m.info.unwrap().name);
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
                error!(
                    "Missing parameter '{}' in method call '{}' for method '{}'",
                    p.name, method.name, method.method
                );
                user_error()
            }
        }
        // Now let's check constraints!
        //
        // We skip values containing variables, using the `${` `}` markers
        let value = method.params.get(&p.name).unwrap();
        if !value.contains("${") {
            let res = p.constraints.is_valid(value).context(format!(
                "Invalid parameter '{}' in method call '{}' for method '{}'",
                p.name.clone(),
                method.name.clone(),
                method.method
            ));
            if let Err(e) = res {
                error!("{:?}", e);
                user_error()
            }
        }
        lint_expression(value)?;
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
            error!("Unexpected parameter '{}' in '{}'", p_name, method.name);
            user_error()
        }
    }

    if let Some(d) = &method.info.unwrap().deprecated {
        warn!(
            "Deprecated method '{}' ({}): {d}",
            method.method, method.name
        );
    }

    // Check report parameter
    lint_expression(&method.name)?;

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
                    r.items.iter().any(|r| is_id_child(r, id))
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
        // Only keep the id on non-virtual items
        match r {
            ItemKind::Block(r) => match r.resolved_foreach_state {
                Some(ForeachResolvedState::Virtual) => vec![],
                Some(ForeachResolvedState::Main) => {
                    let mut ids = vec![r.id.clone()];
                    ids.extend(r.items.iter().flat_map(get_ids));
                    ids
                }
                None => {
                    let mut ids = vec![r.id.clone()];
                    ids.extend(r.items.iter().flat_map(get_ids));
                    ids
                }
            },
            ItemKind::Method(r) => match r.resolved_foreach_state {
                Some(ForeachResolvedState::Virtual) => vec![],
                Some(ForeachResolvedState::Main) => vec![r.id.clone()],
                None => vec![r.id.clone()],
            },
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

/// Ensure all foreach keys are coherent in a given loop
/// This is checked earlier than the other validations as it needs the loops metadata that is
/// lost when the Yaml is converted to a Technique object
pub fn check_foreach_keys_consistency(technique: &DeserTechnique) -> Result<()> {
    fn check_deser_item_foreach_consistency(item: &DeserItem) -> Result<()> {
        if let Some(c) = item.foreach_columns_order.clone() {
            let expected_keys: HashSet<&String> = c.iter().collect();
            match &item.foreach {
                None => bail!(
                    "Item with id {} has a non empty foreach column order but does not have any foreach defined",
                    item.id
                ),
                Some(f) => f.iter().try_for_each(|m| {
                    let k: HashSet<&String> = m.keys().collect();
                    let diff = expected_keys.symmetric_difference(&k).collect::<Vec<_>>();
                    if diff.is_empty() {
                        Ok(())
                    } else {
                        bail!(
                            "Item with id {} has inconsistent uses of foreach keys: {:?}",
                            item.id,
                            &diff
                        );
                    }
                })?,
            }
        }
        let _ = &item
            .items
            .iter()
            .try_for_each(check_deser_item_foreach_consistency)?;
        Ok(())
    }
    technique
        .items
        .iter()
        .try_for_each(check_deser_item_foreach_consistency)
}

fn lint_expression(s: &str) -> Result<()> {
    let res = s.parse::<Expression>();
    match res {
        Err(e) => {
            // We are not sure the parser is totally correct, don't prevent compilation
            warn!("Error parsing '{}': {:?}", s, e);
        }
        Ok(e) => {
            if let Err(e) = e.lint() {
                error!("Error checking '{}': {:?}", s, e);
            }
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::check_for_control_chars;

    #[test]
    fn it_checks_for_forbidden_chars() {
        // Backspace
        assert!(check_for_control_chars("\x08".to_string()).is_err());
        assert!(
            check_for_control_chars("This is \r\n\t a À @ 🥰 normal string".to_string()).is_ok()
        );
    }
}
