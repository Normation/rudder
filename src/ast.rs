mod context;
mod enums;
pub mod generators;
mod preast;
//mod strings;

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

// TODO out of order enum mapping definition

#[derive(Debug)]
pub struct AST<'a> {
    enum_list: EnumList<'a>,
    resources: HashMap<Token<'a>, Resources<'a>>,
    variables: VarContext<'a>,
}

#[derive(Debug)]
struct Resources<'a> {
    metadata: HashMap<Token<'a>, PValue<'a>>,
    parameters: Vec<Parameter<'a>>,
    states: HashMap<Token<'a>, StateDef<'a>>,
    //TODO child: HashSet<Token>
}

#[derive(Debug)]
struct StateDef<'a> {
    metadata: HashMap<Token<'a>, PValue<'a>>,
    parameters: Vec<Parameter<'a>>,
    statements: Vec<Statement<'a>>,
    variables: VarContext<'a>,
}

#[derive(Debug)]
pub struct Parameter<'a> {
    name: Token<'a>,
    ptype: PType,
    default: Option<PValue<'a>>,
}
impl<'a> Parameter<'a> {
    fn new(p: PParameter<'a>) -> Parameter<'a> {
        let ptype = match p.ptype {
            Some(t) => t,
            None => {
                if let Some(val) = &p.default {
                    // guess from default
                    match val {
                        PValue::String(_) => PType::TString,
                        _ => panic!("Unknown value type"), // TODO remove
                    }
                } else {
                    // Nothing -> String
                    PType::TString
                }
            }
        };
        Parameter {
            name: p.name,
            ptype,
            default: p.default,
        }
    }

    fn value_match(&self, param_ref: &PValue) -> Result<()> {
        match (&self.ptype, param_ref) {
            (PType::TString, PValue::String(_)) => Ok(()),
            (t, _v) => fail!(Token::new("x", "y"), "Parameter is not of the type {:?}", t), // TODO we need a Token to position PValues and a display trait
        }
    }
}

#[derive(Debug)]
enum Statement<'a> {
    Comment(PComment<'a>),
    StateCall(
        Option<Token<'a>>, // outcome
        PCallMode,         // mode
        PResourceRef<'a>,  // resource
        Token<'a>,         // state name
        Vec<PValue<'a>>,   // parameters
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
        c: &'b VarContext<'a>,
        st: PStatement<'a>,
    ) -> Result<Statement<'a>> {
        Ok(match st {
            PStatement::Comment(c) => Statement::Comment(c),
            PStatement::StateCall(out, mode, res, st, params) => {
                Statement::StateCall(out, mode, res, st, params)
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
                if enum_list.exists(em.from) {
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
                            &variables,
                            st0,
                        )
                    }))?;
                    let state = StateDef {
                        metadata: meta,
                        parameters: st.parameters.into_iter().map(Parameter::new).collect(),
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
            Statement::StateCall(_out, _mode, res, name, params) => {
                match self.resources.get(&res.name) {
                    None => fail!(res.name, "Resource type {} does not exist", res.name),
                    Some(resource) => {
                        // Assume default parameter replacement and type inference if any has already be done
                        match_parameters(&resource.parameters, &res.parameters, res.name)?;
                        match resource.states.get(&name) {
                            None => fail!(
                                name,
                                "State {} does not exist for resource {}",
                                name,
                                res.name
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

    pub fn analyze(&self) -> Result<()> {
        fix_results(self.resources.iter().flat_map(|(_rn, resource)| {
            resource.states.iter().flat_map(|(_sn, state)| {
                state.statements.iter().map(move |st| {
                    // Not sure why move is needed here
                    // check for resources and state existence
                    // check for matching parameter and type
                    self.binding_check(st)?;
                    //TODO check for enum expression validity
                    self.enum_expression_check(&state.variables, st)?;
                    Ok(())
                })
            })
        }))
        // TODO check for string syntax and interpolation validity
    }
}

fn match_parameters(pdef: &[Parameter], pref: &[PValue], identifier: Token) -> Result<()> {
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
