use super::context::{VarContext,VarKind};
use super::enums::*;
use super::value::Value;
use crate::error::*;
use crate::parser::*;
use std::collections::HashMap;

/// Utility functions

/// Create final metadata from parsed metadata
fn create_metadata<'src>(
    pmetadata: Vec<PMetadata<'src>>,
) -> Result<HashMap<Token<'src>, Value<'src>>> {
    // TODO check for uniqueness and concat comments
    map_hashmap_results(
        pmetadata
            .into_iter(),
            |meta| Ok((meta.key, Value::from_static_pvalue(meta.value)?)),
    )
}

/// Create function/resource/state parameter definition from parsed parameters.
fn create_parameters<'src>(
    pparameters: Vec<PParameter<'src>>,
    parameter_defaults: &[Option<Value<'src>>],
) -> Result<Vec<Parameter<'src>>> {
    map_vec_results(
        pparameters
            .into_iter()
            .zip(parameter_defaults.iter()),
        |(p, d)| Parameter::from_pparameter(p, d)
    )
}

/// Create a local context from a list of parameters
fn create_default_context<'src>(
    global_context: &VarContext<'src>,
    resource_parameters: &[Parameter<'src>],
    parameters: &[Parameter<'src>],
) -> Result<VarContext<'src>> {
    let mut context = VarContext::new();
    for p in resource_parameters.iter().chain(parameters.iter()) {
        context.new_variable(Some(global_context), p.name, p.ptype)?;
    }
    Ok(context)
}

/// Resource definition with associated metadata and states.
#[derive(Debug)]
pub struct ResourceDef<'src> {
    pub metadata: HashMap<Token<'src>, Value<'src>>,
    pub parameters: Vec<Parameter<'src>>,
    pub states: HashMap<Token<'src>, StateDef<'src>>,
//    pub children: HashSet<Token<'src>>,
}

impl<'src> ResourceDef<'src> {
    pub fn from_presourcedef(
        resource_declaration: PResourceDef<'src>,
        pstates: Vec<PStateDef<'src>>,
        context: &VarContext<'src>,
        parameter_defaults: &HashMap<(Token<'src>, Option<Token<'src>>), Vec<Option<Value<'src>>>>,
        enum_list: &EnumList<'src>,
    ) -> Result<ResourceDef<'src>> {
        let PResourceDef {
            name,
            metadata: pmetadata,
            parameters: pparameters,
            //states: pstates,
        } = resource_declaration;
        // create final version of metadata and parameters
        let metadata = create_metadata(pmetadata)?;
        let parameters = create_parameters(
            pparameters,
            &parameter_defaults[&(name, None)],
        )?;
        // create final version of states
        let states = map_hashmap_results(pstates.into_iter(), |st| {

            Ok((
                st.name.clone(),
                StateDef::from_pstate_def(
                    st,
                    name,
                    &parameters,
                    context,
                    parameter_defaults,
                    enum_list,
                )?,
            ))
        })?;
        let states = HashMap::new();
        // fill up children from parents declaration
        Ok(ResourceDef {
            metadata,
            parameters,
            states,
//            children,
        })
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
        resource_parameters: &[Parameter<'src>],
//        children: &mut HashSet<Token<'src>>,
        global_context: &VarContext<'src>,
        parameter_defaults: &HashMap<(Token<'src>, Option<Token<'src>>), Vec<Option<Value<'src>>>>,
        enum_list: &EnumList<'src>,
    ) -> Result<StateDef<'src>> {
        // create final version of metadata and parameters
        let metadata = create_metadata(pstate.metadata)?;
        let parameters = create_parameters(
            pstate.parameters,
            &parameter_defaults[&(resource_name, Some(pstate.name))],
        )?;
        let mut context = create_default_context(
            global_context,
            &resource_parameters,
            &parameters,
        )?;
        // create final version of statements
        let mut statements = Vec::new();
        for st0 in pstate.statements {
            statements.push(Statement::fom_pstatement(&mut context, st0, global_context, parameter_defaults, enum_list)?);
            //statements.push(Statement::fom_pstatement(&mut context, children, st0, global_context, parameter_defaults, enum_list)?);
        }
        Ok(StateDef {
            metadata,
            parameters,
            statements,
            context,
        })
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
#[derive(Debug)]
pub enum Statement<'src> {
    Comment(PComment<'src>),
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
//        children: &'b mut HashSet<Token<'src>>,
        st: PStatement<'src>,
        global_context: &'b VarContext<'src>,
        parameter_defaults: &HashMap<(Token<'src>, Option<Token<'src>>), Vec<Option<Value<'src>>>>,
        enum_list: &EnumList<'src>,
    ) -> Result<Statement<'src>> {
        //let getter: fn(Token<'src>) -> Option<VarKind<'src>> = |k| context.variables.get(&k).or_else(|| global_context.variables.get(&k)).map(VarKind::clone);
        Ok(match st {
            PStatement::VariableDefinition(pmetadata, var, val) => {
                let getter = |k| context.variables.get(&k).or_else(|| global_context.variables.get(&k)).map(VarKind::clone);
                let value = Value::from_pvalue(enum_list, &getter, val)?;
                match value.get_type() {
                    PType::Boolean => context.new_enum_variable(Some(global_context), var, Token::new("stdlib", "boolean"), None)?,
                    _ => {
                        // check that definition use existing variables
                        value.context_check(&getter)?;
                        context.new_variable(Some(global_context), var, value.get_type())?;
                    },
                }
                let metadata = create_metadata(pmetadata)?;
                Statement::VariableDefinition(metadata, var, value)
            }
            PStatement::StateDeclaration(PStateDeclaration { metadata, mode, resource, resource_params, state, state_params, outcome }) => {
                if let Some(out_var) = outcome {
                    // outcome must be defined, token comes from internal compilation, no value known a compile time
                    context.new_enum_variable(
                        Some(global_context),
                        out_var,
                        Token::new("internal", "outcome"),
                        None,
                    )?;
                }
                //children.insert(resource);
                let getter = |k| context.variables.get(&k).or_else(|| global_context.variables.get(&k)).map(VarKind::clone);
                let mut resource_params =
                    map_vec_results(resource_params.into_iter(), |v| Value::from_pvalue(enum_list, &getter, v))?;
                let emptyvec = Vec::new();
                let res_defaults = &parameter_defaults.get(&(resource, None)).unwrap_or(&emptyvec);
                let res_missing = res_defaults.len() as i32 - resource_params.len() as i32;
                if res_missing > 0 {
                    map_results(
                        res_defaults.iter()
                                    .skip(resource_params.len()),
                                    |param| {
                                        match param {
                                            Some(p) => resource_params.push(p.clone()),
                                            None => fail!(resource, "Resources instance of {} is missing parameters and there is no default values for them", resource),
                                        };
                                        Ok(())
                                    }
                    )?;
                } else if res_missing < 0 {
                    fail!(
                        resource,
                        "Resources instance of {} has too many parameters, expecting {}, got {}",
                        resource,
                        res_defaults.len(),
                        resource_params.len()
                    );
                }
                let mut state_params =
                    map_vec_results(state_params.into_iter(), |v| Value::from_pvalue(enum_list, &getter, v))?;
                let st_defaults = &parameter_defaults.get(&(resource, Some(state))).unwrap_or(&emptyvec);
                let st_missing = st_defaults.len() as i32 - state_params.len() as i32;
                if st_missing > 0 {
                    map_results(
                        st_defaults.iter()
                                   .skip(state_params.len()),
                                   |param| {
                                       match param {
                                           Some(p) => state_params.push(p.clone()),
                                           None => fail!(state, "Resources state instance of {} is missing parameters and there is no default values for them", state),
                                       };
                                       Ok(())
                                   }
                    )?;
                } else if st_missing < 0 {
                    fail!(state, "Resources state instance of {} has too many parameters, expecting {}, got {}", state, st_defaults.len(), state_params.len());
                }
                // check that parameters use existing variables
                map_results(
                    resource_params
                        .iter(),
                        |p| p.context_check(&getter),
                )?;
                map_results(
                    state_params
                        .iter(),
                        |p| p.context_check(&getter),
                )?;
                let metadata = create_metadata(metadata)?;
                Statement::StateDeclaration(StateDeclaration { metadata, mode, resource, resource_params, state, state_params, outcome })
            }
            PStatement::Fail(f) => {
                let getter = |k| context.variables.get(&k).or_else(|| global_context.variables.get(&k)).map(VarKind::clone);
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
                let getter = |k| context.variables.get(&k).or_else(|| global_context.variables.get(&k)).map(VarKind::clone);
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
            PStatement::Case(case, v) => {
                Statement::Case(
                    case,
                    map_vec_results(v.into_iter(), |(exp, sts)| {
                        let getter = |k| context.variables.get(&k).or_else(|| global_context.variables.get(&k)).map(VarKind::clone);
                        let expr = enum_list.canonify_expression(&getter, exp)?;
                        Ok((
                            expr,
                            map_vec_results(sts.into_iter(), |st| {
                                Statement::fom_pstatement(context, st, global_context, parameter_defaults, enum_list)
                                //Statement::fom_pstatement(context, children, st, global_context, parameter_defaults, enum_list)
                            })?,
                        ))
                    })?,
                )
            }
        })
    }
}
