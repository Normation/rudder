pub mod generators;
mod context;
mod enums;
mod preast;
mod value;
mod resource;

///
/// AST is a big chunk.
/// It contains everything parsed and analysed.
/// First level submodules are for structures contains in AST.
/// The generator submodule contains a generator trait used to generate code.
/// It is then split into one module per agent.
///
pub use self::preast::PreAST;
use self::context::VarContext;
use self::enums::{EnumExpression, EnumList};
use self::preast::PreResources;
use self::value::Value;
use self::resource::*;
use crate::error::*;
use crate::parser::*;
use std::collections::{HashMap, HashSet};

#[derive(Debug)]
pub struct AST<'src> {
    enum_list: EnumList<'src>,
    resources: HashMap<Token<'src>, Resources<'src>>,
    variables: VarContext<'src>,
}


// TODO global variables
// TODO type inference
// TODO check that parameter type match parameter default
// TODO put default parameter in calls
// TODO check state call compatibility
// TODO if a parameter has a default, next ones must have one too
// TODO more tests

impl<'src> AST<'src> {
    /// Produce the final AST data structure.
    /// Call this when all files have been added.
    /// This does everything that could not be done with partial data (ex: global binding)
    pub fn from_pre_ast(pre_ast: PreAST<'src>) -> Result<AST<'src>> {
        let PreAST {
            mut enum_list,
            mut enum_mapping,
            pre_resources,
            variables: global_variables,
            parameter_defaults,
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
                let PStateDef {
                    name,
                    resource_name,
                    parameters,
                    statements,
                    ..
                } = st;
                if states.contains_key(&name) {
                    fail!(
                        name,
                        "State {} for resource {} has already been defined in {}",
                        name,
                        resource_name,
                        states.entry(name).key()
                    );
                } else {
                    let parameters = fix_vec_results(parameters
                        .into_iter()
                        .zip(parameter_defaults[&(resource_name, Some(name))].iter())
                        .map(|(p,d)| Parameter::from_pparameter(p,d) )
                    )?;
                    let mut variables = VarContext::new();
                    for param in parameters.iter() {
                        variables.new_variable(Some(&global_variables), param.name, param.ptype)?;
                    }
                    let statements = fix_vec_results(statements.into_iter().map(|st0| {
                        Statement::fom_pstatement(
                            &enum_list,
                            Some(&global_variables),
                            &mut variables,
                            &mut children,
                            &parameter_defaults,
                            st0,
                        )
                    }))?;
                    let state = StateDef {
                        metadata: meta,
                        parameters,
                        statements,
                        variables,
                    };
                    states.insert(name, state);
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
                            None => {
                                fail!(name, "State {} does not exist for resource {}", name, res)
                            }
                            Some(state) => {
                                // Assume default parameter replacement and type inference if any has already be done
                                match_parameters(&state.parameters, &params, *name)
                            }
                        }
                    }
                }
            }
            Statement::Case(_name, cases) => fix_results(
                cases
                    .iter()
                    .map(|(_c, sts)| fix_results(sts.iter().map(|st| self.binding_check(st)))),
            ),
            _ => Ok(()),
        }
    }

    fn cases_check(
        &self,
        variables: &VarContext,
        statement: &Statement,
        first_level: bool,
    ) -> Result<()> {
        match statement {
            Statement::Case(keyword, cases) => {
                if first_level {
                    // default must be the last one
                    match cases.split_last() {
                        None => fail!(keyword, "Case list is empty in {}", keyword),
                        Some((_last, case_list)) => {
                            if case_list.iter().any(|(cond, _)| cond.is_default()) {
                                fail!(
                                    keyword,
                                    "Default value must be the last case in { }",
                                    keyword
                                )
                            }
                        }
                    }
                    fix_results(cases.iter().flat_map(|(_cond, sts)| {
                        sts.iter().map(|st| self.cases_check(variables, st, false))
                    }))?;
                } else {
                    fail!(
                        keyword,
                        "Case within case are forbidden at the moment in {}",
                        keyword
                    ); // just because it is hard to generate
                }
            }
            Statement::VariableDefinition(v, _) => {
                if !first_level {
                    fail!(
                        v,
                        "Variable definition {} within case are forbidden at the moment",
                        v
                    ); // because it is hard to check that variables are always defined
                }
            }
            _ => {}
        }
        Ok(())
    }

    fn enum_expression_check(&self, variables: &VarContext, statement: &Statement) -> Result<()> {
        match statement {
            Statement::Case(case, cases) => {
                self.enum_list
                    .evaluate(Some(&self.variables), variables, cases, *case)?;
                fix_results(cases.iter().flat_map(|(_cond, sts)| {
                    sts.iter()
                        .map(|st| self.enum_expression_check(variables, st))
                }))
            }
            _ => Ok(()),
        }
    }

    fn metadata_check(&self, metadata: &HashMap<Token<'src>, Value<'src>>) -> Result<()> {
        fix_results(metadata.iter().map(|(k, v)| {
            match v {
                Value::String(s) => {
                    if !s.is_empty() {
                        // we don't what else we can do so fail to keep a better behaviour for later
                        fail!(
                            k,
                            "Metadata {} has a value that contains variables, this is not allowed",
                            k
                        );
                    }
                }
            }
            Ok(())
        }))
    }

    fn children_check(
        &self,
        name: Token<'src>,
        children: &HashSet<Token<'src>>,
        depth: u32,
    ) -> Result<()> {
        // This can be costly but since there is no guarantee the graph is connected solution is not obvious
        for child in children {
            if *child == name {
                fail!(
                    *child,
                    "Resource {} is recursive because it configures itself via {}",
                    name,
                    *child
                );
            } else {
                // family > 100 children will have check skipped
                if depth >= 100 {
                    // must return OK to stop check in case of real recursion of one child (there is no error yet)
                    return Ok(());
                }
                self.children_check(name, &self.resources[child].children, depth + 1)?;
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
    fn invalid_identifier_check(&self, name: Token<'src>) -> Result<()> {
        if vec![
            "string",
            "int",
            "struct",
            "list",
            "if",
            "case",
            "enum",
            "global",
            "default",
            "resource",
            "state",
            "fail",
            "log",
            "return",
            "noop",
            "format",
            "comment",
            "dict",
            "json",
            "enforce",
            "condition",
            "audit let",
        ]
        .contains(&name.fragment())
        {
            fail!(
                name,
                "Name {} is a reserved keyword and cannot be used here",
                name
            );
        }
        Ok(())
    }

    // an invalid variable is :
    // - invalid identifier
    // - an enum name / except global enum var
    // - a global enum item name
    // - a resource name
    // - true / false
    fn invalid_variable_check(&self, name: Token<'src>, global: bool) -> Result<()> {
        self.invalid_identifier_check(name)?;
        if self.enum_list.enum_exists(name) && (!global || !self.enum_list.is_global(name)) {
            // there is a global variable for each global enum
            fail!(
                name,
                "Variable name {} cannot be used because it is an enum name",
                name
            );
        }
        if let Some(e) = self.enum_list.global_values.get(&name) {
            fail!(
                name,
                "Variable name {} cannot be used because it is an item of the global enum {}",
                name,
                e
            );
        }
        if self.resources.contains_key(&name) {
            fail!(
                name,
                "Variable name {} cannot be used because it is an resource name",
                name
            );
        }
        if vec!["true", "false"].contains(&name.fragment()) {
            fail!(
                name,
                "Variable name {} cannot be used because it is a boolean identifier",
                name
            );
        }
        Ok(())
    }

    // same a above but for the variable definition statement
    fn invalid_variable_statement_check(&self, st: &Statement<'src>) -> Result<()> {
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
        for (name, _value) in self.variables.iter() {
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
            errors.push(self.children_check(*rname, &resource.children, 0));
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
