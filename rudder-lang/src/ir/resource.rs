// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use super::{
    context::{Type, VarContext},
    enums::{EnumExpression, EnumList},
    value::*,
    variable::*,
};
use crate::{error::*, parser::*};
use std::collections::{HashMap, HashSet};
use std::rc::Rc;
use toml::map::Map as TomlMap;
use toml::Value as TomlValue;

///! There are 2 kinds of functions return
///! - Result: could not return data, fatal to the caller
///! - Error vec: data partially created, you may continue

/// Create single final metadata table from parsed metadata list
///TODO this should be somewhere else
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
    parameter_defaults: &[Option<Constant<'src>>],
) -> Result<Vec<Parameter<'src>>> {
    if pparameters.len() != parameter_defaults.len() {
        // this happens when a resource or a state has been defined twice
        // just ignore since the error is handled somewhere else and we don't have error context here
        return Ok(Vec::new());
    }
    map_vec_results(
        pparameters.into_iter().zip(parameter_defaults.iter()),
        |(p, d)| Parameter::from_pparameter(p, d),
    )
}

/// Create a local context from a list of parameters
fn create_default_context<'src>(
    parent_context: Rc<VarContext<'src>>,
    parameters: &[Parameter<'src>],
) -> (Vec<Error>, VarContext<'src>) {
    let mut context = VarContext::new(Some(parent_context));
    let mut errors = Vec::new();
    for p in parameters.iter() {
        if let Err(e) = context.add_variable_declaration(p.name, p.type_.clone()) {
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
    pub context: Rc<VarContext<'src>>,
    pub variable_definitions: VariableDefList<'src>,
    pub is_dependency: bool,
}

impl<'src> ResourceDef<'src> {
    pub fn from_presourcedef(
        resource_declaration: PResourceDef<'src>,
        pstates: Vec<PStateDef<'src>>,
        mut children: HashSet<Token<'src>>,
        parent_context: Rc<VarContext<'src>>,
        parameter_defaults: &HashMap<
            (Token<'src>, Option<Token<'src>>),
            Vec<Option<Constant<'src>>>,
        >,
        enum_list: &EnumList<'src>,
        is_dependency: bool,
    ) -> (Vec<Error>, Option<Self>) {
        let PResourceDef {
            name,
            metadata: pmetadata,
            parameters: pparameters,
            variable_definitions,
            variable_extensions,
            is_dependency,
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
        // Create context from parent
        let (mut errors2, mut mut_context) =
            create_default_context(parent_context, parameters.as_slice());
        errors.append(&mut errors2);
        // Add variables
        let mut vars = VariableDefList::new();
        for var in variable_definitions {
            match vars.append(var, &mut mut_context, enum_list) {
                Err(e) => errors.push(e),
                _ => {}
            }
        }
        for var in variable_extensions {
            match vars.extend(var, &mut mut_context, enum_list) {
                Err(e) => errors.push(e),
                _ => {}
            }
        }
        let context = Rc::new(mut_context);
        for st in pstates {
            let state_name = st.name;
            let (err, state) = StateDef::from_pstate_def(
                st,
                name,
                &mut children,
                context.clone(),
                parameter_defaults,
                enum_list,
                is_dependency,
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
                context,
                variable_definitions: vars,
                is_dependency,
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
    pub context: Rc<VarContext<'src>>,
    pub is_dependency: bool,
    //pub is_alias: bool,
}

impl<'src> StateDef<'src> {
    pub fn from_pstate_def(
        pstate: PStateDef<'src>,
        resource_name: Token<'src>,
        children: &mut HashSet<Token<'src>>,
        parent_context: Rc<VarContext<'src>>,
        parameter_defaults: &HashMap<
            (Token<'src>, Option<Token<'src>>),
            Vec<Option<Constant<'src>>>,
        >,
        enum_list: &EnumList<'src>,
        is_dependency: bool,
    ) -> (Vec<Error>, Option<Self>) {
        // create final version of metadata and parameters
        let parameters = match create_parameters(
            pstate.parameters,
            &parameter_defaults[&(resource_name, Some(pstate.name))],
        ) {
            Ok(p) => p,
            Err(e) => return (vec![e], None),
        };
        let (mut errors, metadata) = create_metadata(pstate.metadata);
        let (errs, mut context) = create_default_context(parent_context, &parameters);
        errors.extend(errs);
        // create final version of statements
        let mut statements = Vec::new();
        for st0 in pstate.statements {
            match Statement::from_pstatement(
                &mut context,
                children,
                st0,
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
                context: Rc::new(context),
                is_dependency,
            }),
        )
    }

    pub fn class_parameter_index(&self, method_name: &str) -> Result<usize> {
        match self.metadata.get("class_parameter_index") {
            Some(TomlValue::Integer(n)) => Ok(*n as usize),
            Some(_) => Err(Error::new(format!(
                "Expected 'class_parameter_index' metadata to be a number for '{}' method",
                method_name
            ))),
            // default, 0, ie resource parameter. Useful for rudderlang declarations that do not need class_parameters
            None => Ok(0),
        }
    }

    pub fn supported_targets(&self, method_name: &str) -> Result<Vec<String>> {
        match self.metadata.get("supported_targets") {
            Some(TomlValue::Array(parameters)) => parameters
                .iter()
                .map(|p| match p {
                    TomlValue::String(s) => Ok(s.to_owned()),
                    _ => Err(Error::new(
                        format!("Expected 'supported_targets' metadata elements to be strings for '{}' method", method_name),
                    ))
                })
                .collect::<Result<Vec<String>>>(),
            Some(_) => Err(Error::new(format!("Expected 'supported_targets' metadata to be an array for '{}' method", method_name))),
            None => Err(Error::new(format!("Expected 'supported_targets' metadata for '{}' method", method_name)))
        }
    }

    pub fn get_method_aliases(&self) -> Result<Vec<String>> {
        if let Some(aliases) = self
            .metadata
            .get("method_aliases")
            .and_then(|v| v.as_array())
        {
            return Ok(aliases
                .iter()
                .filter_map(|v| v.as_str().map(String::from))
                .collect::<Vec<String>>());
        }

        return Err(Error::new(format!(
            "Expected 'method_aliases' metadata to be an array of strings"
        )));
    }
}

/// A single parameter for a resource or a state
#[derive(Debug)]
pub struct Parameter<'src> {
    pub name: Token<'src>,
    pub type_: Type<'src>,
}

impl<'src> Parameter<'src> {
    pub fn from_pparameter(p: PParameter<'src>, default: &Option<Constant<'src>>) -> Result<Self> {
        let type_ = Type::from_ptype(p.ptype, Vec::new())?;
        if let Some(val) = default {
            if type_ != Type::from_constant(val) {
                fail!(
                    p.name,
                    "Default value for {} doesn't match its type",
                    p.name
                );
            }
        }
        Ok(Parameter {
            name: p.name,
            type_,
        })
    }

    /// returns an error if the value has an incompatible type
    // TODO handle variables
    pub fn value_match(&self, param_ref: &Value) -> Result<()> {
        let param_type = Type::from_value(param_ref);
        if self.type_ != param_type {
            fail!(
                self.name,
                "Parameter {} is of type {:?}",
                self.name,
                self.type_,
            );
        }
        Ok(())
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
#[derive(Debug, PartialEq)]
#[allow(clippy::large_enum_variant)]
pub enum Statement<'src> {
    // TODO should we split variable definition and enum definition ? this would probably help generators
    VariableDefinition(VariableDef<'src>),
    // Define a condition variable to handle condition_from_* gm exception
    ConditionVariableDefinition(CondVariableDef<'src>),
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
    param_defaults: &HashMap<(Token<'src>, Option<Token<'src>>), Vec<Option<Constant<'src>>>>,
    params: &mut Vec<Value<'src>>,
) -> Result<()> {
    let emptyvec = Vec::new();
    let defaults = param_defaults.get(&(resource, state)).unwrap_or(&emptyvec);
    let diff = defaults.len() as i32 - params.len() as i32;
    if diff > 0 {
        map_results(defaults.iter().skip(params.len()), |param| {
            if let Some(p) = param {
                (*params).push(p.into());
            };
            Ok(())
        })?;
    }
    Ok(())
}

fn string_value<'src>(
    context: &VarContext<'src>,
    enum_list: &EnumList<'src>,
    pvalue: PValue<'src>,
) -> Result<Value<'src>> {
    let value = Value::from_pvalue(enum_list, context, pvalue)?;
    // check that definition use existing variables
    value.context_check(context)?;
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
        parameter_defaults: &HashMap<
            (Token<'src>, Option<Token<'src>>),
            Vec<Option<Constant<'src>>>,
        >,
        enum_list: &EnumList<'src>,
    ) -> Result<Self> {
        Ok(match st {
            PStatement::VariableDefinition(def) => {
                let var = VariableDef::from_pvariable_definition(def, context, enum_list)?;
                Statement::VariableDefinition(var)
            }
            PStatement::VariableExtension(ext) => {
                fail!(
                    ext.name,
                    "Variable extensions are not supported in states at {}",
                    ext.name
                );
            }
            PStatement::ConditionVariableDefinition(def) => {
                let mut var =
                    CondVariableDef::from_pcond_variable_definition(def, context, enum_list)?;
                children.insert(var.resource);
                // condition method has 1 class_parameter that can be constructed from PCondVariableDef
                push_default_parameters(
                    var.resource,
                    None,
                    parameter_defaults,
                    &mut var.resource_params,
                )?;
                push_default_parameters(
                    var.resource,
                    Some(var.state),
                    parameter_defaults,
                    &mut var.state_params,
                )?;
                // check that parameters use existing variables
                map_results(var.resource_params.iter(), |p| p.context_check(context))?;
                map_results(var.state_params.iter(), |p| p.context_check(context))?;

                Statement::ConditionVariableDefinition(var)
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
                    context.add_variable_declaration(
                        out_var,
                        Type::Enum(Token::new("internal", "outcome")),
                    )?;
                }
                children.insert(resource);
                let mut resource_params = map_vec_results(resource_params.into_iter(), |v| {
                    Value::from_pvalue(enum_list, context, v)
                })?;
                push_default_parameters(resource, None, parameter_defaults, &mut resource_params)?;
                let mut state_params = map_vec_results(state_params.into_iter(), |v| {
                    Value::from_pvalue(enum_list, context, v)
                })?;
                push_default_parameters(
                    resource,
                    Some(state),
                    parameter_defaults,
                    &mut state_params,
                )?;
                // check that parameters use existing variables
                map_results(resource_params.iter(), |p| p.context_check(context))?;
                map_results(state_params.iter(), |p| p.context_check(context))?;
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
            PStatement::Fail(f) => Statement::Fail(string_value(context, enum_list, f)?),
            PStatement::LogDebug(l) => Statement::LogDebug(string_value(context, enum_list, l)?),
            PStatement::LogInfo(l) => Statement::LogInfo(string_value(context, enum_list, l)?),
            PStatement::LogWarn(l) => Statement::LogWarn(string_value(context, enum_list, l)?),
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
                    let expr = enum_list.canonify_expression(context, exp)?;
                    Ok((
                        expr,
                        map_vec_results(sts.into_iter(), |st| {
                            Statement::from_pstatement(
                                context,
                                children,
                                st,
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
