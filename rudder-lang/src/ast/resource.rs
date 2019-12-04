// Copyright 2019 Normation SAS
//
// This file is part of Rudder.
//
// Rudder is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// In accordance with the terms of section 7 (7. Additional Terms.) of
// the GNU General Public License version 3, the copyright holders add
// the following Additional permissions:
// Notwithstanding to the terms of section 5 (5. Conveying Modified Source
// Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
// Public License version 3, when you create a Related Module, this
// Related Module is not considered as a part of the work and may be
// distributed under the license agreement of your choice.
// A "Related Module" means a set of sources files including their
// documentation that, without modification of the Source Code, enables
// supplementary functions or services in addition to those offered by
// the Software.
//
// Rudder is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

use super::context::{VarContext, VarKind};
use super::enums::*;
use super::value::Value;
use crate::error::*;
use crate::parser::*;
use std::collections::{HashMap, HashSet, hash_map::Entry};
// use std::collections::hash_map::Entry;

///! There are 2 kinds of functions return
///! - Result: could not return data, fatal to the caller
///! - Error vec: data partialy created, you may continue

/// Create final metadata from parsed metadata
fn create_metadata<'src>(
    pmetadata: Vec<PMetadata<'src>>,
) -> (Vec<Error>, HashMap<Token<'src>, Value<'src>>) {
    let mut errors = Vec::new();
    let mut metadata = HashMap::new();
    for meta in pmetadata {
        let value = match Value::from_static_pvalue(meta.value) {
            Err(e) => {
                errors.push(e);
                continue;
            }
            Ok(v) => v,
        };
        // Check for uniqueness and concat comments
        match metadata.entry(meta.key) {
            Entry::Occupied(mut entry) => {
                let key: Token = *entry.key();
                if key.fragment() == "comment" {
                    match entry.get_mut() {
                        Value::String(ref mut o1) => match value {
                            Value::String(o2) => o1.append(o2),
                            _ => errors.push(err!(meta.key, "Comment metadata must be of type string")),
                        },
                        _ => errors.push(err!(
                            key,
                            "Existing comment metadata must be of type string"
                        )),
                    };
                } else {
                    errors.push(err!(
                        meta.key,
                        "metadata {} already defined at {}",
                        &meta.key,
                        key,
                    ));
                }
            },
            Entry::Vacant(entry) => {
                entry.insert(value);
            },
        };
        // if metadata.contains_key(&meta.key) {
        //     if meta.key.fragment() == "comment" {
        //         match metadata.get_mut(&meta.key).unwrap() {
        //             Value::String(o1) => match value {
        //                 Value::String(o2) => o1.append(o2),
        //                 _ => errors.push(err!(meta.key, "Comment metadata must be of type string")),
        //             },
        //             _ => errors.push(err!(
        //                 meta.key,
        //                 "Existing comment metadata must be of type string"
        //             )),
        //         }
        //     } else {
        //         errors.push(err!(
        //             meta.key,
        //             "metadata {} already defined at {}",
        //             &meta.key,
        //             metadata.entry(meta.key).key()
        //         ));
        //     }
        // } else {
        //     metadata.insert(meta.key, value);
        // }
    }
    (errors, metadata)
}

/// Create function/resource/state parameter definition from parsed parameters.
fn create_parameters<'src>(
    pparameters: Vec<PParameter<'src>>,
    parameter_defaults: &[Option<Value<'src>>],
) -> Result<Vec<Parameter<'src>>> {
    if pparameters.len() != parameter_defaults.len() {
        panic!("BUG: parameter count should not differ from default count");
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
        if let Err(e) = context.new_variable(Some(global_context), p.name, p.ptype) {
            errors.push(e);
        }
    }
    (errors, context)
}

/// Resource definition with associated metadata and states.
#[derive(Debug)]
pub struct ResourceDef<'src> {
    pub metadata: HashMap<Token<'src>, Value<'src>>,
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
    pub metadata: HashMap<Token<'src>, Value<'src>>,
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
            match Statement::fom_pstatement(
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
    pub ptype: PType,
}

impl<'src> Parameter<'src> {
    pub fn from_pparameter(
        p: PParameter<'src>,
        default: &Option<Value<'src>>,
    ) -> Result<Parameter<'src>> {
        let ptype = match p.ptype {
            Some(t) => t,
            None => {
                if let Some(val) = default {
                    val.get_type()
                } else {
                    // Nothing -> String
                    PType::String
                }
            }
        };
        Ok(Parameter {
            name: p.name,
            ptype,
        })
    }

    /// returns an error if the value has an incompatible type
    pub fn value_match(&self, param_ref: &Value) -> Result<()> {
        match (&self.ptype, param_ref) {
            (PType::String, Value::String(_)) => Ok(()),
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
    pub metadata: HashMap<Token<'src>, Value<'src>>,
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
pub enum Statement<'src> {
    // TODO should we split variable definition and enum definition ? this would probably help generators
    VariableDefinition(HashMap<Token<'src>, Value<'src>>, Token<'src>, Value<'src>),
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
    Log(Value<'src>),
    // Return a specific outcome
    Return(Token<'src>),
    // Do nothing
    Noop,
}
impl<'src> Statement<'src> {
    pub fn fom_pstatement<'b>(
        context: &'b mut VarContext<'src>,
        children: &'b mut HashSet<Token<'src>>,
        st: PStatement<'src>,
        global_context: &'b VarContext<'src>,
        parameter_defaults: &HashMap<(Token<'src>, Option<Token<'src>>), Vec<Option<Value<'src>>>>,
        enum_list: &EnumList<'src>,
    ) -> Result<Statement<'src>> {
        // TODO common getter
        Ok(match st {
            PStatement::VariableDefinition(pmetadata, var, val) => {
                let getter = |k| {
                    context
                        .variables
                        .get(&k)
                        .or_else(|| global_context.variables.get(&k))
                        .map(VarKind::clone)
                };
                let value = Value::from_pvalue(enum_list, &getter, val)?;
                match value.get_type() {
                    PType::Boolean => context.new_enum_variable(
                        Some(global_context),
                        var,
                        Token::new("stdlib", "boolean"),
                        None,
                    )?,
                    _ => {
                        // check that definition use existing variables
                        value.context_check(&getter)?;
                        context.new_variable(Some(global_context), var, value.get_type())?;
                    }
                }
                let (mut _errors, metadata) = create_metadata(pmetadata);
                Statement::VariableDefinition(metadata, var, value)
            }
            PStatement::StateDeclaration(PStateDeclaration {
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
                    context.new_enum_variable(
                        Some(global_context),
                        out_var,
                        Token::new("internal", "outcome"),
                        None,
                    )?;
                }
                children.insert(resource);
                let getter = |k| {
                    context
                        .variables
                        .get(&k)
                        .or_else(|| global_context.variables.get(&k))
                        .map(VarKind::clone)
                };
                let mut resource_params = map_vec_results(resource_params.into_iter(), |v| {
                    Value::from_pvalue(enum_list, &getter, v)
                })?;
                let emptyvec = Vec::new();
                let res_defaults = &parameter_defaults
                    .get(&(resource, None))
                    .unwrap_or(&emptyvec);
                let res_missing = res_defaults.len() as i32 - resource_params.len() as i32;
                if res_missing > 0 {
                    map_results(res_defaults.iter().skip(resource_params.len()), |param| {
                        match param {
                                            Some(p) => resource_params.push(p.clone()),
                                            None => fail!(resource, "Resources instance of {} is missing parameters and there is no default values for them", resource),
                                        };
                        Ok(())
                    })?;
                } else if res_missing < 0 {
                    fail!(
                        resource,
                        "Resources instance of {} has too many parameters, expecting {}, got {}",
                        resource,
                        res_defaults.len(),
                        resource_params.len()
                    );
                }
                let mut state_params = map_vec_results(state_params.into_iter(), |v| {
                    Value::from_pvalue(enum_list, &getter, v)
                })?;
                let st_defaults = &parameter_defaults
                    .get(&(resource, Some(state)))
                    .unwrap_or(&emptyvec);
                let st_missing = st_defaults.len() as i32 - state_params.len() as i32;
                if st_missing > 0 {
                    map_results(st_defaults.iter().skip(state_params.len()), |param| {
                        match param {
                                           Some(p) => state_params.push(p.clone()),
                                           None => fail!(state, "Resources state instance of {} is missing parameters and there is no default values for them", state),
                                       };
                        Ok(())
                    })?;
                } else if st_missing < 0 {
                    fail!(state, "Resources state instance of {} has too many parameters, expecting {}, got {}", state, st_defaults.len(), state_params.len());
                }
                // check that parameters use existing variables
                map_results(resource_params.iter(), |p| p.context_check(&getter))?;
                map_results(state_params.iter(), |p| p.context_check(&getter))?;
                let (mut _errors, metadata) = create_metadata(metadata);
                Statement::StateDeclaration(StateDeclaration {
                    metadata,
                    mode,
                    resource,
                    resource_params,
                    state,
                    state_params,
                    outcome,
                })
            }
            PStatement::Fail(f) => {
                let getter = |k| {
                    context
                        .variables
                        .get(&k)
                        .or_else(|| global_context.variables.get(&k))
                        .map(VarKind::clone)
                };
                let value = Value::from_pvalue(enum_list, &getter, f)?;
                // check that definition use existing variables
                value.context_check(&getter)?;
                // we must fail with a string
                match &value {
                    Value::String(_) => (),
                    _ => unimplemented!(), // TODO must fail here with a message
                }
                Statement::Fail(value)
            }
            PStatement::Log(l) => {
                let getter = |k| {
                    context
                        .variables
                        .get(&k)
                        .or_else(|| global_context.variables.get(&k))
                        .map(VarKind::clone)
                };
                let value = Value::from_pvalue(enum_list, &getter, l)?;
                // check that definition use existing variables
                value.context_check(&getter)?;
                // we must fail with a string
                match &value {
                    Value::String(_) => (),
                    _ => unimplemented!(), // TODO must fail here with a message
                }
                Statement::Log(value)
            }
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
                    let getter = |k| {
                        context
                            .variables
                            .get(&k)
                            .or_else(|| global_context.variables.get(&k))
                            .map(VarKind::clone)
                    };
                    let expr = enum_list.canonify_expression(&getter, exp)?;
                    Ok((
                        expr,
                        map_vec_results(sts.into_iter(), |st| {
                            Statement::fom_pstatement(
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
