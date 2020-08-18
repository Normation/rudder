// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use super::{
    context::{VarContext, VarType},
    enums::{EnumExpression, EnumList},
    value::Value,
};
use crate::{error::*, parser::*};
use std::collections::{HashMap, HashSet};
use toml::map::Map as TomlMap;
use toml::Value as TomlValue;

///! There are 2 kinds of functions return
///! - Result: could not return data, fatal to the caller
///! - Error vec: data partially created, you may continue

/// Create single final metadata table from parsed metadata list
pub fn create_metadata(pmetadata: Vec<PMetadata>) -> (Vec<Error>, TomlMap<String, TomlValue>) {
    let mut errors = Vec::new();
    let mut output = TomlMap::new();
    for meta in pmetadata {
        // check that we have a real key=value table
        let table = match meta.values {
            TomlValue::Table(t) => t,
            _ => {
                errors.push(err!(
                    meta.source,
                    "Metadata syntax error, must be a key=value"
                ));
                continue;
            }
        };
        for (key, value) in table {
            // Check for uniqueness and concat comments
            match output.entry(key) {
                toml::map::Entry::Occupied(mut entry) => {
                    let key = entry.key();
                    if key == "comment" {
                        // if this is an existing comment, just concatenate it
                        let comment = match entry.get() {
                            TomlValue::String(s1) => match value {
                                TomlValue::String(s2) => s1.to_owned() + &s2,
                                _ => {
                                    errors.push(err!(
                                        meta.source,
                                        "Comment metadata must be of type string"
                                    ));
                                    continue;
                                }
                            },
                            _ => {
                                errors.push(err!(
                                    meta.source,
                                    "Existing comment metadata must be of type string"
                                ));
                                continue;
                            }
                        };
                        entry.insert(TomlValue::String(comment));
                    } else {
                        // if this is an existing key, there is an error
                        errors.push(err!(meta.source, "metadata {} already defined", key,));
                    }
                }
                toml::map::Entry::Vacant(entry) => {
                    // Just append new values
                    entry.insert(value);
                }
            };
        }
    }
    (errors, output)
}

/// Create function/resource/state parameter definition from parsed parameters.
fn create_parameters<'src>(
    pparameters: Vec<PParameter<'src>>,
    parameter_defaults: &[Option<Value<'src>>],
) -> Result<Vec<Parameter<'src>>> {
    if pparameters.len() != parameter_defaults.len() {
        panic!(
            "BUG: parameter count should not differ from default count: expected {}, found {}",
            parameter_defaults.len(),
            pparameters.len()
        );
    }
    map_vec_results(
        pparameters.into_iter().zip(parameter_defaults.iter()),
        |(p, d)| Parameter::from_pparameter(p, d),
    )
}

/// Create a local context from a list of parameters
fn create_default_context<'src>(
    global_context: &VarContext<'src>,
    resource_parameters: &[Parameter<'src>],
    parameters: &[Parameter<'src>],
) -> (Vec<Error>, VarContext<'src>) {
    let mut context = VarContext::new();
    let mut errors = Vec::new();
    for p in resource_parameters.iter().chain(parameters.iter()) {
        if let Err(e) = context.add_variable(Some(global_context), p.name, &p.value) {
            errors.push(e);
        }
    }
    (errors, context)
}

/// Resource definition with associated metadata and states.
#[derive(Debug)]
pub struct ResourceDef<'src> {
    pub name: &'src str,
    pub metadata: TomlMap<String, TomlValue>,
    pub parameters: Vec<Parameter<'src>>,
    pub states: HashMap<Token<'src>, StateDef<'src>>,
    pub children: HashSet<Token<'src>>,
}

impl<'src> ResourceDef<'src> {
    pub fn from_presourcedef(
        resource_declaration: PResourceDef<'src>,
        pstates: Vec<PStateDef<'src>>,
        mut children: HashSet<Token<'src>>,
        context: &VarContext<'src>,
        parameter_defaults: &HashMap<(Token<'src>, Option<Token<'src>>), Vec<Option<Value<'src>>>>,
        enum_list: &EnumList<'src>,
    ) -> (Vec<Error>, Option<ResourceDef<'src>>) {
        let PResourceDef {
            name,
            metadata: pmetadata,
            parameters: pparameters,
        } = resource_declaration;
        // create final version of parameters
        let parameters = match create_parameters(pparameters, &parameter_defaults[&(name, None)]) {
            Ok(p) => p,
            Err(e) => return (vec![e], None),
        };
        // create metadata and start error vector
        let (mut errors, metadata) = create_metadata(pmetadata);
        // create final version of states
        let mut states = HashMap::new();
        for st in pstates {
            let state_name = st.name;
            let (err, state) = StateDef::from_pstate_def(
                st,
                name,
                &mut children,
                &parameters,
                context,
                parameter_defaults,
                enum_list,
            );
            errors.extend(err);
            if let Some(st) = state {
                states.insert(state_name, st);
            }
        }
        (
            errors,
            Some(ResourceDef {
                name: *name,
                metadata,
                parameters,
                states,
                children,
            }),
        )
    }
}

/// State definition and associated metadata
#[derive(Debug)]
pub struct StateDef<'src> {
    pub name: &'src str,
    pub metadata: TomlMap<String, TomlValue>,
    pub parameters: Vec<Parameter<'src>>,
    pub statements: Vec<Statement<'src>>,
    pub context: VarContext<'src>,
    //pub is_alias: bool,
}

impl<'src> StateDef<'src> {
    pub fn from_pstate_def(
        pstate: PStateDef<'src>,
        resource_name: Token<'src>,
        children: &mut HashSet<Token<'src>>,
        resource_parameters: &[Parameter<'src>],
        global_context: &VarContext<'src>,
        parameter_defaults: &HashMap<(Token<'src>, Option<Token<'src>>), Vec<Option<Value<'src>>>>,
        enum_list: &EnumList<'src>,
    ) -> (Vec<Error>, Option<StateDef<'src>>) {
        // create final version of metadata and parameters
        let parameters = match create_parameters(
            pstate.parameters,
            &parameter_defaults[&(resource_name, Some(pstate.name))],
        ) {
            Ok(p) => p,
            Err(e) => return (vec![e], None),
        };
        let (mut errors, metadata) = create_metadata(pstate.metadata);
        let (errs, mut context) =
            create_default_context(global_context, &resource_parameters, &parameters);
        errors.extend(errs);
        // create final version of statements
        let mut statements = Vec::new();
        for st0 in pstate.statements {
            match Statement::from_pstatement(
                &mut context,
                children,
                st0,
                global_context,
                parameter_defaults,
                enum_list,
            ) {
                Err(e) => errors.push(e),
                Ok(st) => statements.push(st),
            }
        }
        (
            errors,
            Some(StateDef {
                name: *pstate.name,
                metadata,
                parameters,
                statements,
                context,
            }),
        )
    }
}

/// A single parameter for a resource or a state
#[derive(Debug)]
pub struct Parameter<'src> {
    pub name: Token<'src>,
    pub value: Value<'src>,
}

impl<'src> Parameter<'src> {
    pub fn from_pparameter(
        p: PParameter<'src>,
        default: &Option<Value<'src>>,
    ) -> Result<Parameter<'src>> {
        let value = match p.ptype {
            Some(t) => Value::from_static_pvalue(t)?,
            None => match default {
                Some(val) => val.clone(),
                // if no default value, define an empty string
                // may be better to store an option directly
                None => Value::from_static_pvalue(PValue::generate_automatic(PType::String))?,
            },
        };
        Ok(Parameter {
            name: p.name,
            value,
        })
    }

    /// returns an error if the value has an incompatible type
    pub fn value_match(&self, param_ref: &Value) -> Result<()> {
        match (&self.value, param_ref) {
            (Value::String(_), Value::String(_)) => Ok(()),
            (t, _v) => fail!(
                self.name,
                "Parameter {} is not of the type {:?}",
                self.name,
                t
            ),
        }
    }
}

/// A State Declaration is a given required state on a given resource
#[derive(Debug, PartialEq)]
pub struct StateDeclaration<'src> {
    pub source: Token<'src>,
    pub metadata: TomlMap<String, TomlValue>,
    pub mode: PCallMode,
    pub resource: Token<'src>,
    pub resource_params: Vec<Value<'src>>,
    pub state: Token<'src>,
    pub state_params: Vec<Value<'src>>,
    pub outcome: Option<Token<'src>>,
}

/// A single statement within a state definition
// TODO error reporting
#[derive(Debug)]
#[allow(clippy::large_enum_variant)]
pub enum Statement<'src> {
    // TODO should we split variable definition and enum definition ? this would probably help generators
    VariableDefinition(TomlMap<String, TomlValue>, Token<'src>, Value<'src>),
    // one state
    StateDeclaration(StateDeclaration<'src>),
    //   keyword    list of condition          then
    Case(
        Token<'src>,
        Vec<(EnumExpression<'src>, Vec<Statement<'src>>)>,
    ),
    // Stop engine
    Fail(Value<'src>),
    // Inform the user of something
    LogDebug(Value<'src>),
    LogInfo(Value<'src>),
    LogWarn(Value<'src>),
    // Return a specific outcome
    Return(Token<'src>),
    // Do nothing
    Noop,
}

// Seek for input-filled parameters of a corresponding resource or depending state, and fill potentially missing parameters with default ones
fn push_default_parameters<'src>(
    resource: Token<'src>,
    state: Option<Token<'src>>,
    param_defaults: &HashMap<(Token<'src>, Option<Token<'src>>), Vec<Option<Value<'src>>>>,
    params: &mut Vec<Value<'src>>,
) -> Result<()> {
    let emptyvec = Vec::new();
    let defaults = param_defaults.get(&(resource, state)).unwrap_or(&emptyvec);
    let diff = defaults.len() as i32 - params.len() as i32;
    if diff > 0 {
        map_results(defaults.iter().skip(params.len()), |param| {
            if let Some(p) = param {
                (*params).push(p.clone());
            };
            Ok(())
        })?;
    }
    Ok(())
}

fn string_value<'src, VG>(
    getter: &VG,
    enum_list: &EnumList<'src>,
    pvalue: PValue<'src>,
) -> Result<Value<'src>>
where
    VG: Fn(Token<'src>) -> Option<VarType<'src>>,
{
    let value = Value::from_pvalue(enum_list, &getter, pvalue)?;
    // check that definition use existing variables
    value.context_check(&getter)?;
    // we must have a string
    match &value {
        Value::String(_) => Ok(value),
        _ => unimplemented!(), // TODO must fail here with a message
    }
}

impl<'src> Statement<'src> {
    pub fn from_pstatement<'b>(
        context: &'b mut VarContext<'src>,
        children: &'b mut HashSet<Token<'src>>,
        st: PStatement<'src>,
        global_context: &'b VarContext<'src>,
        parameter_defaults: &HashMap<(Token<'src>, Option<Token<'src>>), Vec<Option<Value<'src>>>>,
        enum_list: &EnumList<'src>,
    ) -> Result<Statement<'src>> {
        // we must not capture the context in this common getter since it is used
        // as mutable elsewhere in this function
        let common_getter =
            |ctx: &VarContext<'src>, k: Token<'src>| ctx.get(&k).or_else(|| global_context.get(&k));
        Ok(match st {
            PStatement::VariableDefinition(PVariableDef {
                metadata,
                name,
                value,
            }) => {
                let value =
                    Value::from_pvalue(enum_list, &{ |x| common_getter(context, x) }, value)?;
                match value {
                    Value::Boolean(_, _) => {
                        context.add_variable(Some(global_context), name, VarType::Boolean)?
                    }
                    _ => {
                        // check that definition use existing variables
                        value.context_check(&{ |x| common_getter(context, x) })?;
                        context.add_variable(Some(global_context), name, &value)?;
                    }
                }
                let (mut _errors, metadata) = create_metadata(metadata);
                Statement::VariableDefinition(metadata, name, value)
            }
            PStatement::StateDeclaration(PStateDeclaration {
                source,
                metadata,
                mode,
                resource,
                resource_params,
                state,
                state_params,
                outcome,
            }) => {
                if let Some(out_var) = outcome {
                    // outcome must be defined, token comes from internal compilation, no value known a compile time
                    context.add_variable(
                        Some(global_context),
                        out_var,
                        VarType::Enum(Token::new("internal", "outcome")),
                    )?;
                }
                children.insert(resource);
                let mut resource_params = map_vec_results(resource_params.into_iter(), |v| {
                    Value::from_pvalue(enum_list, &{ |x| common_getter(context, x) }, v)
                })?;
                push_default_parameters(resource, None, parameter_defaults, &mut resource_params)?;
                let mut state_params = map_vec_results(state_params.into_iter(), |v| {
                    Value::from_pvalue(enum_list, &{ |x| common_getter(context, x) }, v)
                })?;
                push_default_parameters(
                    resource,
                    Some(state),
                    parameter_defaults,
                    &mut state_params,
                )?;
                // check that parameters use existing variables
                map_results(resource_params.iter(), |p| {
                    p.context_check(&{ |x| common_getter(context, x) })
                })?;
                map_results(state_params.iter(), |p| {
                    p.context_check(&{ |x| common_getter(context, x) })
                })?;
                let (mut _errors, metadata) = create_metadata(metadata);
                Statement::StateDeclaration(StateDeclaration {
                    source,
                    metadata,
                    mode,
                    resource,
                    resource_params,
                    state,
                    state_params,
                    outcome,
                })
            }
            PStatement::Fail(f) => Statement::Fail(string_value(
                &{ |x| common_getter(context, x) },
                enum_list,
                f,
            )?),
            PStatement::LogDebug(l) => Statement::LogDebug(string_value(
                &{ |x| common_getter(context, x) },
                enum_list,
                l,
            )?),
            PStatement::LogInfo(l) => Statement::LogInfo(string_value(
                &{ |x| common_getter(context, x) },
                enum_list,
                l,
            )?),
            PStatement::LogWarn(l) => Statement::LogWarn(string_value(
                &{ |x| common_getter(context, x) },
                enum_list,
                l,
            )?),
            PStatement::Return(r) => {
                if r == Token::new("", "kept")
                    || r == Token::new("", "repaired")
                    || r == Token::new("", "error")
                {
                    Statement::Return(r)
                } else {
                    fail!(
                        r,
                        "Can only return an outcome (kept, repaired or error) instead of {}",
                        r
                    )
                }
            }
            PStatement::Noop => Statement::Noop,
            PStatement::Case(case, v) => Statement::Case(
                case,
                map_vec_results(v.into_iter(), |(exp, sts)| {
                    let expr =
                        enum_list.canonify_expression(&{ |x| common_getter(context, x) }, exp)?;
                    Ok((
                        expr,
                        map_vec_results(sts.into_iter(), |st| {
                            Statement::from_pstatement(
                                context,
                                children,
                                st,
                                global_context,
                                parameter_defaults,
                                enum_list,
                            )
                        })?,
                    ))
                })?,
            ),
        })
    }
}
