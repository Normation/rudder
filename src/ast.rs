mod context;
mod enums;
pub mod generators;
mod preast;

///
/// AST is a big chunk.
/// It contains everything parsed and analysed.
/// First level submodules are for structures contains in AST.
/// The generator submodule contains a generator trait used to generate code.
/// It is then split into one module per agent.
///
use self::context::VarContext;
use self::enums::{EnumExpression, EnumList};
pub use self::preast::PreAST;
use self::preast::PreResources;
use crate::error::*;
use crate::parser::*;
use std::collections::HashMap;

#[derive(Debug)]
pub struct AST<'a> {
    enum_list: EnumList<'a>,
    resources: HashMap<Token<'a>, Resources<'a>>,
    variables: VarContext<'a>,
}

#[derive(Debug)]
struct Resources<'a> {
    metadata: HashMap<Token<'a>, Value<'a>>,
    parameters: Vec<Parameter<'a>>,
    states: HashMap<Token<'a>, StateDef<'a>>,
    //TODO children: HashSet<Token>
}

#[derive(Debug)]
struct StateDef<'a> {
    metadata: HashMap<Token<'a>, Value<'a>>,
    parameters: Vec<Parameter<'a>>,
    statements: Vec<Statement<'a>>,
    variables: VarContext<'a>,
}

#[derive(Debug)]
pub struct StringObject<'a> {
    pos: Token<'a>,
    strs: Vec<String>,
    vars: Vec<String>,
}
impl<'a> StringObject<'a> {
    pub fn from_pstring(pos: Token<'a>, s: String) -> Result<StringObject> {
        let (strs,vars) = parse_string2(&s[..])?;
        Ok(StringObject {pos, strs, vars})
    }
    pub fn format<SF,VF>(&self, str_formatter: SF, var_formatter: VF) -> String
        // string, is_a_suffix, is_a_prefix
        where SF: Fn(&str) -> String,
              VF: Fn(&str) -> String {
        let mut output = String::new();
        let (last, elts) = self.strs.split_last().unwrap(); // strs cannot be empty
        let it = elts.iter().zip(self.vars.iter());
        for (s,v) in it {
            output.push_str(&str_formatter(s));
            output.push_str(&var_formatter(v));
        }
        output.push_str(&str_formatter(last));
        output
    }
    pub fn is_empty(&self) -> bool { self.vars.is_empty() }
}

#[derive(Debug)]
pub enum Value<'a> {
    //     position   format  variables
    String(StringObject<'a>),
}
impl<'a> Value<'a> {
    pub fn from_pvalue(pvalue: PValue<'a>) -> Result<Value<'a>> {
        match pvalue {
            PValue::String(pos, s) => Ok(Value::String(StringObject::from_pstring(pos,s)?)),
        }
    }
}

#[derive(Debug)]
pub struct Parameter<'a> {
    name: Token<'a>,
    ptype: PType,
    default: Option<Value<'a>>,
}
impl<'a> Parameter<'a> {
    fn from_pparameter(p: PParameter<'a>) -> Result<Parameter<'a>> {
        let ptype = match p.ptype {
            Some(t) => t,
            None => {
                if let Some(val) = &p.default {
                    // guess from default
                    match val {
                        PValue::String(_, _) => PType::TString,
                    }
                } else {
                    // Nothing -> String
                    PType::TString
                }
            }
        };
        let value = match p.default {
            None => None,
            Some(v) => Some(Value::from_pvalue(v)?),
        };
        Ok(Parameter {
            name: p.name,
            ptype,
            default: value,
        })
    }

    fn value_match(&self, param_ref: &Value) -> Result<()> {
        match (&self.ptype, param_ref) {
            (PType::TString, Value::String(_)) => Ok(()),
            (t, _v) => fail!(Token::new("x", "y"), "Parameter is not of the type {:?}", t), // TODO we need a Token to position PValues and a display trait
        }
    }
}

#[derive(Debug)]
pub enum Statement<'a> {
    Comment(PComment<'a>),
    VariableDefinition(Token<'a>, Value<'a>),
    StateCall(
        PCallMode,         // mode
        Token<'a>,         // resource
        Vec<Value<'a>>,    // resource parameters
        Token<'a>,         // state name
        Vec<Value<'a>>,    // parameters
        Option<Token<'a>>, // outcome
    ),
    //   list of condition          then
    Case(Vec<(EnumExpression<'a>, Vec<Statement<'a>>)>),
    // Stop engine
    Fail(Token<'a>),
    // Inform the user of something
    Log(Token<'a>),
    // Return a specific outcome
    Return(Token<'a>),
    // Do nothing
    Noop,
}
impl<'a> Statement<'a> {
    fn fom_pstatement<'b>(
        enum_list: &'b EnumList<'a>,
        gc: Option<&'b VarContext<'a>>,
        c: &'b mut VarContext<'a>,
        st: PStatement<'a>,
    ) -> Result<Statement<'a>> {
        Ok(match st {
            PStatement::Comment(c) => Statement::Comment(c),
            PStatement::VariableDefinition(var, val) => {
                // TODO c.insert_var(var,val)
                Statement::VariableDefinition(var, Value::from_pvalue(val)?)
            }
            PStatement::StateCall(mode, res, res_params, st, params, out) => {
                if let Some(out_var) = out {
                    // outcome must be defined, token comes from internal compilation, no value known a compile time
                    c.new_enum_variable(gc, out_var, Token::new("internal", "outcome"), None)?;
                }
                let res_parameters = fix_vec_results(res_params.into_iter().map(|v| Value::from_pvalue(v)))?;
                let parameters = fix_vec_results(params.into_iter().map(|v| Value::from_pvalue(v)))?;
                Statement::StateCall(mode, res, res_parameters, st, parameters, out)
            }
            PStatement::Fail(f) => Statement::Fail(f),
            PStatement::Log(l) => Statement::Log(l),
            PStatement::Return(r) => Statement::Return(r),
            PStatement::Noop => Statement::Noop,
            PStatement::Case(v) => {
                Statement::Case(fix_vec_results(v.into_iter().map(|(exp, sts)| {
                    Ok((
                        enum_list.canonify_expression(gc, c, exp)?,
                        fix_vec_results(
                            sts.into_iter()
                                .map(|st| Statement::fom_pstatement(enum_list, gc, c, st)),
                        )?,
                    ))
                }))?)
            }
        })
    }
}

// TODO type inference
// TODO check that parameter type match parameter default
// TODO put default parameter in calls
// TODO forbid case within case
// TODO analyse Resource tree (and disable recursion)
// TODO default must be the last entry in a case

impl<'a> AST<'a> {
    /// Produce the final AST data structure.
    /// Call this when all files have been added.
    /// This does everything that could not be done with partial data (ex: global binding)
    pub fn from_pre_ast(pre_ast: PreAST<'a>) -> Result<AST<'a>> {
        let PreAST {
            mut enum_list,
            mut enum_mapping,
            pre_resources,
            variables: global_variables,
        } = pre_ast;
        // fill enum_list iteratively
        let mut map_count = enum_mapping.len();
        loop {
            let mut new_enum_mapping = Vec::new();
            fix_results(enum_mapping.into_iter().map(|em| {
                if enum_list.enum_exists(em.from) {
                    enum_list.add_mapping(em)?;
                } else {
                    new_enum_mapping.push(em);
                }
                Ok(())
            }))?;
            if new_enum_mapping.is_empty() {
                break;
            } else if map_count == new_enum_mapping.len() {
                fix_results(new_enum_mapping.iter().map(|em| {
                    fail!(
                        em.to,
                        "Enum {} doesn't exist when trying to define mapping {}",
                        em.from,
                        em.to
                    )
                }))?;
            }
            enum_mapping = new_enum_mapping;
            map_count = enum_mapping.len();
        }
        // create new resources struct
        let mut resources = HashMap::new();
        fix_results(pre_resources.into_iter().map(|(rn, rd)| {
            let PreResources {
                metadata,
                parameters,
                pre_states,
            } = rd;
            let mut states = HashMap::new();
            // insert resource states
            #[allow(clippy::map_entry)]
            fix_results(pre_states.into_iter().map(|(meta, st)| {
                if states.contains_key(&st.name) {
                    fail!(
                        st.name,
                        "State {} for resource {} has already been defined in {}",
                        st.name,
                        st.resource_name,
                        states.entry(st.name).key()
                    );
                } else {
                    let mut variables = VarContext::new();
                    let statements = fix_vec_results(st.statements.into_iter().map(|st0| {
                        Statement::fom_pstatement(
                            &enum_list,
                            Some(&global_variables),
                            &mut variables,
                            st0,
                        )
                    }))?;
                    let state = StateDef {
                        metadata: meta,
                        parameters: fix_vec_results(st.parameters.into_iter().map(Parameter::from_pparameter))?,
                        statements,
                        variables,
                    };
                    states.insert(st.name, state);
                }
                Ok(())
            }))?;
            let resource = Resources {
                metadata,
                parameters,
                states,
            };
            resources.insert(rn, resource);
            Ok(())
        }))?;

        Ok(AST {
            enum_list,
            resources,
            variables: global_variables,
        })
    }

    fn binding_check(&self, statement: &Statement) -> Result<()> {
        match statement {
            Statement::StateCall(_mode, res, res_params, name, params, _out) => {
                match self.resources.get(res) {
                    None => fail!(res, "Resource type {} does not exist", res),
                    Some(resource) => {
                        // Assume default parameter replacement and type inference if any has already be done
                        match_parameters(&resource.parameters, res_params, *res)?;
                        match resource.states.get(&name) {
                            None => fail!(
                                name,
                                "State {} does not exist for resource {}",
                                name,
                                res
                            ),
                            Some(state) => {
                                // Assume default parameter replacement and type inference if any has already be done
                                match_parameters(&state.parameters, &params, *name)
                            }
                        }
                    }
                }
            }
            _ => Ok(()),
        }
    }

    fn enum_expression_check(&self, variables: &VarContext, statement: &Statement) -> Result<()> {
        match statement {
            Statement::Case(cases) => {
                self.enum_list.evaluate(
                    Some(&self.variables),
                    variables,
                    cases,
                    Token::new("", ""),
                )?; // TODO token
                fix_results(cases.iter().flat_map(|(_cond, sts)| {
                    sts.iter()
                        .map(|st| self.enum_expression_check(variables, st))
                }))
            }
            _ => Ok(()),
        }
    }

    fn metadata_check(&self, metadata: &HashMap<Token<'a>, Value<'a>>) -> Result<()> {
        fix_results(metadata.iter().map(|(k,v)| {
            match v {
                Value::String(s) => if !s.is_empty() {
                    // we don't what else we can do so fail to keep a better behaviour for later
                    fail!(k, "Metadata {} has a value that contains variables, this is not allowed", k);
                }
            }
            Ok(())
        }))
    }

    pub fn analyze(&self) -> Result<()> {
        let mut errors = Vec::new();
        for (_rn, resource) in self.resources.iter() {
            // check that metadata does not contain any variable reference
            errors.push(self.metadata_check(&resource.metadata));
            for (_sn, state) in resource.states.iter() {
                // check that metadata does not contain any variable reference
                errors.push(self.metadata_check(&state.metadata));
                for st in state.statements.iter() {
                    // check for resources and state existence
                    // check for matching parameter and type
                    errors.push(self.binding_check(st));
                    // check for enum expression validity
                    errors.push(self.enum_expression_check(&state.variables, st));
                }
            }
        }
        // TODO check for string syntax and interpolation validity
        fix_results(errors.into_iter())
    }
}

fn match_parameters(pdef: &[Parameter], pref: &[Value], identifier: Token) -> Result<()> {
    if pdef.len() != pref.len() {
        fail!(
            identifier,
            "Error in call to {}, parameter count do not match, expecting {}, you gave {}",
            identifier,
            pdef.len(),
            pref.len()
        );
    }
    pdef.iter()
        .zip(pref.iter())
        .map(|(p, v)| p.value_match(v))
        .collect()
}
