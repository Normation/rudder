mod codeindex;
mod context;
mod enums;
pub mod generators;
mod resource;
mod value;

///
/// AST is a big chunk.
/// It contains everything parsed and analysed.
/// First level submodules are for structures contains in AST.
/// The generator submodule contains a generator trait used to generate code.
/// It is then split into one module per agent.
///
pub use self::codeindex::CodeIndex;
use self::context::VarContext;
use self::enums::{EnumExpression, EnumList};
use self::resource::*;
use self::value::Value;
use crate::ast::context::GlobalContext;
use crate::error::*;
use crate::parser::*;
use std::collections::{HashMap, HashSet};

#[derive(Debug)]
pub struct AST<'src> {
    global_context: GlobalContext<'src>,
    resources: HashMap<Token<'src>, ResourceDef<'src>>,
    variable_declarations: HashMap<Token<'src>, Value<'src>>,
}

// TODO type inference
// TODO check that parameter type match parameter default
// TODO check state call compatibility
// TODO if a parameter has a default, next ones must have one too
// TODO more tests
// TODO compatibility metadata

impl<'src> AST<'src> {
    /// Create global enum list structure from all source code enum and enum mapping declarations.
    /// Fill up the context with a single global variable per ancestor global enum.
    fn create_enums(
        context: &mut VarContext<'src>,
        enums: Vec<PEnum<'src>>,
        enum_mappings: Vec<PEnumMapping<'src>>,
    ) -> Result<EnumList<'src>> {
        let mut enum_list = EnumList::new();
        // First insert all simple enums
        fix_results(enums.into_iter().map(|e| {
            if e.global {
                context.new_enum_variable(None, e.name, e.name, None)?;
            }
            enum_list.add_enum(e)?;
            Ok(())
        }))?;
        // Then iteratively insert mappings
        let mut map_count = enum_mappings.len();
        let mut mappings = enum_mappings;
        loop {
            // Try inserting every mapping that have an existing ancestor until there is no more
            let mut new_mappings = Vec::new();
            fix_results(mappings.into_iter().map(|em| {
                if enum_list.enum_exists(em.from) {
                    enum_list.add_mapping(em)?;
                } else {
                    new_mappings.push(em);
                }
                Ok(())
            }))?;
            if new_mappings.is_empty() {
                // Yay, finished !
                break;
            } else if map_count == new_mappings.len() {
                // Nothing changed since last loop, we failed !
                fix_results(new_mappings.iter().map(|em| {
                    fail!(
                        em.to,
                        "Enum {} doesn't exist when trying to define mapping {}",
                        em.from,
                        em.to
                    )
                }))?;
            }
            mappings = new_mappings;
            map_count = mappings.len();
        }
        // check that everything is now OK
        enum_list.mapping_check()?;
        Ok(enum_list)
    }

    /// Create global declarations structures and add global variables to context.
    /// Fill up the context with global variables.
    fn create_declarations(
        gc: &GlobalContext<'src>,
        variable_declarations: Vec<(Token<'src>, PValue<'src>)>,
    ) -> Result<HashMap<Token<'src>, Value<'src>>> {
        let mut declarations = HashMap::new();
        fix_results(variable_declarations.into_iter().map(|(variable, value)| {
            if declarations.contains_key(&variable) {
                fail!(variable, "Variable {} already declared in {}", variable, declarations.entry(variable).key());
            }
            let val = Value::from_pvalue(gc, None, value)?;
            declarations.insert(variable,val);
            Ok(())
        }))?;
        Ok(declarations)
    }

    /// Create default values for all resource and state parameters that have defaults.
    fn create_default_values(
        gc: &GlobalContext<'src>,
        parameter_defaults: HashMap<(Token<'src>, Option<Token<'src>>), Vec<Option<PValue<'src>>>>,
    ) -> Result<HashMap<(Token<'src>, Option<Token<'src>>), Vec<Option<Value<'src>>>>> {
        fix_map_results(parameter_defaults.into_iter().map(|(id, defaults)| {
            Ok((
                id,
                fix_vec_results(defaults.into_iter().map(|def| {
                    Ok(match def {
                        Some(pvalue) => Some(Value::from_pvalue(gc, None, pvalue)?),
                        None => None,
                    })
                }))?,
            ))
        }))
    }

    /// Produce the final AST data structure.
    /// Call this when all files have been added.
    pub fn from_code_index(code_index: CodeIndex) -> Result<AST> {
        let CodeIndex {
            enums,
            enum_mappings,
            resources,
            variable_declarations,
            parameter_defaults,
        } = code_index;
        let mut var_context = VarContext::new();
        // first create enums since they have no dependencies
        let enum_list = AST::create_enums(&mut var_context, enums, enum_mappings)?;
        // global context depends on enums
        fix_results(variable_declarations.iter().map(|(variable, value)|
            var_context.new_variable(None, *variable, value.get_type())
        ))?;
        let mut global_context = GlobalContext {
            var_context,
            enum_list,
            parameter_defaults: HashMap::new(),
        };
        // variable declaration depends on global context and enums
        let variable_declarations =
            AST::create_declarations(&global_context, variable_declarations)?;
        // default parameters depend on globals
        let parameter_defaults =
            AST::create_default_values(&global_context, parameter_defaults)?;
        global_context.parameter_defaults = parameter_defaults;
        // resources depend on everything else
        let resources = fix_map_results(resources.into_iter().map(|(rn, rd)| {
            Ok((
                rn,
                ResourceDef::from_resource_declaration(rn, rd, &global_context)?,
            ))
        }))?;
        Ok(AST {
            global_context,
            resources,
            variable_declarations,
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
                self.global_context.enum_list.evaluate(
                    &self.global_context,
                    Some(variables),
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
                },
                Value::EnumExpression(e) => unimplemented!() // TODO e.token
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
        if self.global_context.enum_list.enum_exists(name)
            && (!global || !self.global_context.enum_list.is_global(name))
        {
            // there is a global variable for each global enum
            fail!(
                name,
                "Variable name {} cannot be used because it is an enum name",
                name
            );
        }
        if let Some(e) = self.global_context.enum_list.global_values.get(&name) {
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
                    errors.push(self.enum_expression_check(&state.context, st));
                    // check for case validity
                    errors.push(self.cases_check(&state.context, st, true));
                }
            }
        }
        // analyze global vars
        // TODO what about local var ?
        for (name, _value) in self.global_context.var_context.iter() {
            // check for invalid variable name
            errors.push(self.invalid_variable_check(*name, true));
        }
        // analyse enums
        for (e, (_global, items)) in self.global_context.enum_list.iter() {
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
