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
use std::collections::{HashMap,HashSet};

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
    children: HashSet<Token<'a>>,
}

#[derive(Debug)]
struct StateDef<'a> {
    metadata: HashMap<Token<'a>, Value<'a>>,
    parameters: Vec<Parameter<'a>>,
    statements: Vec<Statement<'a>>,
    variables: VarContext<'a>,
}

#[derive(Debug,PartialEq)]
pub struct StringObject<'a> {
    pos: Token<'a>,
    strs: Vec<String>,
    vars: Vec<String>,
}
impl<'a> StringObject<'a> {
    pub fn from_pstring(pos: Token<'a>, s: String) -> Result<StringObject> {
        let (strs,vars) = parse_string(&s[..])?;
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

#[derive(Debug,PartialEq)]
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

    pub fn context_check(&self, gc: Option<&VarContext<'a>>, context: &VarContext<'a>) -> Result<()> {
        match self {
            Value::String(s) => fix_results(s.vars.iter().map(|v|
                match context.get_variable(gc, Token::new("",v)) {
                    None => fail!(s.pos, "Variable {} does not exist at {}",v, s.pos),
                    _ => Ok(()),
                }
            )),
        }
    }

    pub fn get_type(&self) -> PType {
        match self {
            Value::String(_) => PType::TString,
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
            (t, _v) => fail!(self.name, "Parameter {} is not of the type {:?}", self.name, t),
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
    //   keyword    list of condition          then
    Case(Token<'a>, Vec<(EnumExpression<'a>, Vec<Statement<'a>>)>),
    // Stop engine
    Fail(Value<'a>),
    // Inform the user of something
    Log(Value<'a>),
    // Return a specific outcome
    Return(Token<'a>),
    // Do nothing
    Noop,
}
impl<'a> Statement<'a> {
    fn fom_pstatement<'b>(
        enum_list: &'b EnumList<'a>,
        gc: Option<&'b VarContext<'a>>,
        context: &'b mut VarContext<'a>,
        children: &'b mut HashSet<Token<'a>>,
        st: PStatement<'a>,
    ) -> Result<Statement<'a>> {
        Ok(match st {
            PStatement::Comment(c) => Statement::Comment(c),
            PStatement::VariableDefinition(var, val) => {
                let value = Value::from_pvalue(val)?;
                // check that definition use existing variables
                value.context_check(gc, context)?;
                context.new_variable(gc, var, value.get_type())?;
                Statement::VariableDefinition(var, value)
            }
            PStatement::StateCall(mode, res, res_params, st, params, out) => {
                if let Some(out_var) = out {
                    // outcome must be defined, token comes from internal compilation, no value known a compile time
                    context.new_enum_variable(gc, out_var, Token::new("internal", "outcome"), None)?;
                }
                children.insert(res);
                let res_parameters = fix_vec_results(res_params.into_iter().map(|v| Value::from_pvalue(v)))?;
                let parameters = fix_vec_results(params.into_iter().map(|v| Value::from_pvalue(v)))?;
                // check that parameters use existing variables
                fix_results(res_parameters.iter().map(|p| p.context_check(gc, context)))?;
                fix_results(parameters.iter().map(|p| p.context_check(gc, context)))?;
                Statement::StateCall(mode, res, res_parameters, st, parameters, out)
            }
            PStatement::Fail(f) => {
                let value = Value::from_pvalue(f)?;
                // check that definition use existing variables
                value.context_check(gc, context)?;
                // we must fail with a string
                match &value {
                    Value::String(_) => (),
                    // _ => fail!(Token::new("", "TODO"), "Fail only accept a strong parameter"),
                }
                Statement::Fail(value)
            },
            PStatement::Log(l) => {
                let value = Value::from_pvalue(l)?;
                // check that definition use existing variables
                value.context_check(gc, context)?;
                // we must fail with a string
                match &value {
                    Value::String(_) => (),
                    // _ => fail!(Token::new("", "TODO"), "Fail only accept a strong parameter"),
                }
                Statement::Log(value)
            },
            PStatement::Return(r) => {
                if  r == Token::new("", "kept") ||
                    r == Token::new("", "repaired") ||
                    r == Token::new("", "error") {
                    Statement::Return(r) // TODO must be an outcome
                } else {
                    fail!(r, "Can only return an outcome (kept, repaired or error) instead of {}",r)
                }
            },
            PStatement::Noop => Statement::Noop,
            PStatement::Case(case, v) => {
                Statement::Case(case, fix_vec_results(v.into_iter().map(|(exp_str, sts)| {
                    let exp = parse_enum_expression(exp_str)?;
                    Ok((
                        enum_list.canonify_expression(gc, context, exp)?,
                        fix_vec_results(
                            sts.into_iter()
                                .map(|st| Statement::fom_pstatement(enum_list, gc, context, children, st)),
                        )?,
                    ))
                }))?)
            }
        })
    }
}

// TODO global variables
// TODO type inference
// TODO check that parameter type match parameter default
// TODO put default parameter in calls

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
            let mut children = HashSet::new();
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
                    let parameters = fix_vec_results(st.parameters.into_iter().map(Parameter::from_pparameter))?;
                    let mut variables = VarContext::new();
                    for param in parameters.iter() {
                        variables.new_variable(Some(&global_variables), param.name, param.ptype)?;
                    }
                    let statements = fix_vec_results(st.statements.into_iter().map(|st0| {
                        Statement::fom_pstatement(
                            &enum_list,
                            Some(&global_variables),
                            &mut variables,
                            &mut children,
                            st0,
                        )
                    }))?;
                    let state = StateDef {
                        metadata: meta,
                        parameters,
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
                children,
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
            },
            Statement::Case(_name, cases) => {
                fix_results(cases.iter().map(|(_c,sts)|
                    fix_results(sts.iter().map(|st|
                        self.binding_check(st)
                    ))
                ))
            },
            _ => Ok(()),
        }
    }

    fn cases_check(&self, variables: &VarContext, statement: &Statement, first_level: bool) -> Result<()> {
        match statement {
            Statement::Case(keyword, cases) => {
                if first_level {
                    // default must be the last one
                    match cases.split_last() {
                        None => fail!(keyword, "Case list is empty in {}", keyword),
                        Some((_last,case_list)) => {
                            if case_list.iter().any(|(cond,_)| cond.is_default()) {
                                fail!(keyword, "Default value must be the last case in { }", keyword)
                            }
                        }
                    }
                    fix_results(cases.iter().flat_map(|(_cond, sts)| {
                        sts.iter()
                            .map(|st| self.cases_check(variables, st, false))
                    }))?;
                } else {
                    fail!(keyword,"Case within case are forbidden at the moment in {}", keyword); // just because it is hard to generate
                }

            },
            Statement::VariableDefinition(v,_) => {
                if !first_level {
                    fail!(v,"Variable definition {} within case are forbidden at the moment", v); // because it is hard to check that variables are always defined
                }
            },
            _ => {}
        }
        Ok(())
    }

    fn enum_expression_check(&self, variables: &VarContext, statement: &Statement) -> Result<()> {
        match statement {
            Statement::Case(case, cases) => {
                self.enum_list.evaluate(
                    Some(&self.variables),
                    variables,
                    cases,
                    *case,
                )?;
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

    fn children_check(&self, name: Token<'a>, children: &HashSet<Token<'a>>, depth: u32) -> Result<()> {
        // This can be costly but since there is no guarantee the graph is connected solution is not obvious
        for child in children {
            if *child == name {
                fail!(*child, "Resource {} is recursive because it configures itself via {}", name, *child);
            } else {
                // family > 100 children will have check skipped
                if depth >= 100 {
                    // must return OK to stop check in case of real recursion of one child (there is no error yet)
                    return Ok(())
                }
                self.children_check(name, &self.resources[child].children, depth+1)?;
            }
        }
        Ok(())
    }

    // invalid enum
    // invalid enum item
    // invalid resource
    // invalid state
    // -> invalid identifier

    // and invalid identifier is
    // - invalid namespace TODO
    // - a type name : string int struct list
    // - an existing keyword in the language: if case enum global default resource state fail log return noop
    // - a reserved keyword for future language: format comment dict json enforce condition audit let
    fn invalid_identifier_check(&self, name: Token<'a>) -> Result<()> {
        if vec!["string", "int", "struct", "list", "if", "case", "enum", "global",
                "default", "resource", "state", "fail", "log", "return", "noop",
                "format", "comment", "dict", "json", "enforce", "condition",
                "audit let"].contains(&name.fragment()) {
            fail!(name, "Name {} is a reserved keyword and cannot be used here", name);
        }
        Ok(())
    }

    // an invalid variable is :
    // - invalid identifier
    // - an enum name / except global enum var
    // - a global enum item name
    // - a resource name
    // - true / false
    fn invalid_variable_check(&self, name: Token<'a>, global: bool) -> Result<()> {
        self.invalid_identifier_check(name)?;
        if self.enum_list.enum_exists(name) {
            if !global || !self.enum_list.is_global(name) { // there is a global variable for each global enum
                fail!(name, "Variable name {} cannot be used because it is an enum name", name);
            }
        }
        if let Some(e) = self.enum_list.global_values.get(&name) {
            fail!(name, "Variable name {} cannot be used because it is an item of the global enum {}", name, e);
        }
        if self.resources.contains_key(&name) {
            fail!(name, "Variable name {} cannot be used because it is an resource name", name);
        }
        if vec!["true", "false"].contains(&name.fragment()) {
            fail!(name, "Variable name {} cannot be used because it is a boolean identifier", name);
        }
        Ok(())
    }

    // same a above but for the variable definition statement
    fn invalid_variable_statement_check(&self, st: &Statement<'a>) -> Result<()> {
        match st {
            Statement::VariableDefinition(name, _) => self.invalid_variable_check(*name, false),
            _ => Ok(()),
        }
    }


    pub fn analyze(&self) -> Result<()> {
        // Analyze step 1: no prerequisite
        let mut errors = Vec::new();
        // analyze resources
        for (rn, resource) in self.resources.iter() {
            // check resource name
            errors.push(self.invalid_identifier_check(*rn));
            // check that metadata does not contain any variable reference
            errors.push(self.metadata_check(&resource.metadata));
            for (sn, state) in resource.states.iter() {
                // check status name
                errors.push(self.invalid_identifier_check(*sn));
                // check that metadata does not contain any variable reference
                errors.push(self.metadata_check(&state.metadata));
                for st in state.statements.iter() {
                    // check for resources and state existence
                    // check for matching parameter and type
                    errors.push(self.binding_check(st));
                    // check for variable names in statements
                    errors.push(self.invalid_variable_statement_check(st));
                    // check for enum expression validity
                    errors.push(self.enum_expression_check(&state.variables, st));
                    // check for case validity
                    errors.push(self.cases_check(&state.variables, st, true));
                }
            }
        }
        // analyze global vars
        for (name,_value) in self.variables.iter() {
            // check for invalid variable name
            errors.push(self.invalid_variable_check(*name, true));
        }
        // analyse enums
        for (e, (_global, items)) in self.enum_list.iter() {
            // check for invalid enum name
            errors.push(self.invalid_identifier_check(*e));
            // check for invalid item name
            for i in items.iter() {
                errors.push(self.invalid_identifier_check(*i));
            }
        }
        // Stop here if there is any error
        fix_results(errors.into_iter())?;

        // Analyze step 2: step 1 must have passed
        errors = Vec::new();
        for (rname, resource) in self.resources.iter() {
            // check that resource definition is not recursive
            errors.push(self.children_check(*rname, &resource.children,0));
        }
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
