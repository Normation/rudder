mod context;
mod enums;
pub  mod generators;
//mod strings;

///
/// AST is a big chunk.
/// It contains everything parsed and analysed.
/// First level submodules are for structures contains in AST.
/// The generator submodule contains a generator trait used to generate code.
/// It is then split into one module per agent.
///

use self::context::VarContext;
use self::enums::{EnumList,EnumExpression};
use crate::error::*;
use crate::parser::*;
use std::collections::HashMap;

// TODO out of order enum mapping definition

#[derive(Debug)]
pub struct AST<'a> {
    enum_list: EnumList<'a>, //TODO Alt<HashMap,EnumList>
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

// Represents 2 alternative data structure types
// This is meant to hold in the same structure the data before and after it has been processed.
// Once the data is in the second state, there is no reason to go back to first state
#[derive(Debug)]
enum Alt<T,U> {
    // Fist state (usually before calling finalize)
    Temporary(T),
    // Second state (usually after calling finalize)
    Final(U),
}

impl <T,U> Alt<T,U> {
    fn temporary(&mut self) -> &mut T {
        match self {
            Alt::Temporary(t) => t,
            _ => panic!("BUG! You must call temporary on a Temporary Alt"),
        }
    }
    fn get_final(&self) -> &U {
        match self {
            Alt::Final(u) => u,
            _ => panic!("BUG! You must get final on a Final Alt"),
        }
    }
}

#[derive(Debug)]
struct StateDef<'a> {
    metadata: HashMap<Token<'a>, PValue<'a>>,
    parameters: Vec<Parameter<'a>>,
    statements: Alt<Vec<PStatement<'a>>,Vec<Statement<'a>>>,
    variables: VarContext<'a>,
}

#[derive(Debug)]
struct Parameter<'a> {
    pub name: Token<'a>,
    pub ptype: PType,
    pub default: Option<PValue<'a>>,
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
    fn fom_pstatement<'b>(enum_list: &'b EnumList<'a>, gc: Option<&'b VarContext<'a>>, c: &'b VarContext<'a>, st: PStatement<'a>) -> Result<Statement<'a>> {
        Ok(match st {
            PStatement::Comment(c) => Statement::Comment(c),
            PStatement::StateCall(out,mode,res,st,params) => Statement::StateCall(out,mode,res,st,params),
            PStatement::Fail(f) => Statement::Fail(f),
            PStatement::Log(l) => Statement::Log(l),
            PStatement::Return(r) => Statement::Return(r),
            PStatement::Noop => Statement::Noop,
            PStatement::Case(v) => Statement::Case(
                fix_vec_results(
                    v.into_iter().map(|(exp, sts)|
                        Ok((
                            enum_list.canonify_expression(gc, c, exp)?,
                            fix_vec_results(
                                sts.into_iter().map(|st| {
                                    Statement::fom_pstatement(enum_list, gc, c, st)
                                })
                            )?
                        ))
                    )
                )?
            ),
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
    pub fn new() -> AST<'static> {
        AST {
            enum_list: EnumList::new(),
            resources: HashMap::new(),
            variables: VarContext::new(),
        }
    }

    /// Add a file parsed with the top level parser.
    /// Call this once for each file before calling finalize.
    pub fn add_parsed_file(&mut self, filename: &'a str, file: PFile<'a>) -> Result<()> {
        if file.header.version != 0 {
            panic!("Multiple format not supported yet");
        }
        let mut current_metadata: HashMap<Token<'a>, PValue<'a>> = HashMap::new();
        fix_results(file.code.into_iter().map(|decl| {
            match decl {
                PDeclaration::Comment(c) => {
                    // comment are concatenated and are considered metadata
                    if current_metadata.contains_key(&Token::new("comment", filename)) {
                        current_metadata
                            .entry(Token::new("comment", filename))
                            .and_modify(|e| {
                                *e = match e {
                                    PValue::String(st) => PValue::String(st.to_string() + c),
                                    _ => panic!("Comment is not a string, this should not happen"),
                                }
                            });
                    } else {
                        current_metadata.insert("comment".into(), PValue::String(c.to_string()));
                    }
                }
                PDeclaration::Metadata(m) => {
                    // metadata must not be called "comment"
                    if m.key == Token::from("comment") {
                        fail!(m.key, "Metadata name '{}' is forbidden", m.key);
                    }
                    if current_metadata.contains_key(&m.key) {
                        fail!(
                            m.key,
                            "Metadata name '{}' is already defined at {}",
                            m.key,
                            current_metadata.entry(m.key).key()
                        );
                    }
                    current_metadata.insert(m.key, m.value);
                }
                PDeclaration::Resource(rd) => {
                    if self.resources.contains_key(&rd.name) {
                        fail!(
                            rd.name,
                            "Resource {} has already been defined in {}",
                            rd.name,
                            self.resources.entry(rd.name).key()
                        );
                    }
                    let resource = Resources {
                        metadata: current_metadata.drain().collect(), // Move the content without moving the structure
                        parameters: rd.parameters.into_iter().map(Parameter::new).collect(),
                        states: HashMap::new(),
                    };
                    self.resources.insert(rd.name, resource);
                    // Reset metadata
                    current_metadata = HashMap::new();
                }
                PDeclaration::State(st) => {
                    if let Some(rd) = self.resources.get_mut(&st.resource_name) {
                        if rd.states.contains_key(&st.name) {
                            fail!(
                                st.name,
                                "State {} for resource {} has already been defined in {}",
                                st.name,
                                st.resource_name,
                                rd.states.entry(st.name).key()
                            );
                        }
                        let state = StateDef {
                            metadata: current_metadata.drain().collect(), // Move the content without moving the structure
                            parameters: st.parameters.into_iter().map(Parameter::new).collect(),
                            statements: Alt::Temporary(st.statements),
                            variables: VarContext::new(),
                        };
                        rd.states.insert(st.name, state);
                        // Reset metadata
                        current_metadata = HashMap::new();
                    } else {
                        fail!(
                            st.resource_name,
                            "Resource {} has not been defined for {}",
                            st.resource_name,
                            st.name
                        );
                    }
                }
                PDeclaration::Enum(e) => {
                    if e.global {
                        self.variables.new_enum_variable(None, e.name, e.name, None)?;
                    }
                    self.enum_list.add_enum(e)?;
                    // Discard metadata
                    // TODO warn if there is some ignored metadata
                    current_metadata = HashMap::new();
                }
                PDeclaration::Mapping(em) => {
                    // TODO add a global variable for global mapping
                    self.enum_list.add_mapping(em)?;
                    // Discard metadata
                    // TODO warn if there is some ignored metadata
                    current_metadata = HashMap::new();
                }
            };
            Ok(())
        }))
    }

    /// Produce the final AST data structure.
    /// Call this when all files have been added.
    /// This does everything that could not be done with partial data (ex: global binding)
    pub fn finalize(self) -> Result<Self> {
        let AST { enum_list, mut resources, variables } = self;
         fix_results(resources.iter_mut().flat_map(|(_rn, resource)| {
            resource.states.iter_mut().map(|(_sn, state)| {
                let sts: Vec<PStatement> = state.statements.temporary().drain(..).collect(); // copy of the temporary vector to work around mut borrowing
                state.statements = Alt::Final(
                    fix_vec_results(sts.into_iter().map(|st| {
                        Statement::fom_pstatement(&enum_list, Some(&variables), &state.variables, st)
                    }))?
                );
                Ok(())
            })
        }))?;
        Ok(AST { enum_list, resources, variables })
    }

    fn state_call_check(&self, statement: &PStatement) -> Result<()> {
        /*match statement {
            PStatement::StateCall(_out, _mode, res, name, params) => {
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
                                match_parameters(&state.parameters, &params, *name)?;
                            }
                        }
                    }
                }
            }
        }*/
        Ok(())
    }

    pub fn analyze(&self) -> Result<()> {
        /*fix_results(self.resources.iter().flat_map(|(_rn, resource)| {
        resource.states.iter().flat_map(|(_sn, state)| {
            state.statements.get_final().iter().map(|st| {
                // check for resources and state existence
                // check for matching parameter and type
                self.state_call_check(st)?;
                // check for enum expression validity and completeness
                self.enum_expression_check(st)?;
                Ok(())
            })
        })
    }))*/
        Ok(())
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

