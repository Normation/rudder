pub mod context;
pub mod enums;
pub mod resource;
pub mod value;

///
/// AST is a big chunk.
/// It contains everything parsed and analysed.
/// First level submodules are for structures contains in AST.
/// The generator submodule contains a generator trait used to generate code.
/// It is then split into one module per agent.
///
use crate::codeindex::{CodeIndex,TmpResourceDef};
use self::context::VarContext;
use self::enums::EnumList;
use self::resource::*;
use self::value::Value;
use crate::ast::context::GlobalContext;
use crate::error::*;
use crate::parser::*;
use std::collections::{HashMap, HashSet};

#[derive(Debug)]
pub struct AST<'src> {
    pub global_context: GlobalContext<'src>,
    pub resources: HashMap<Token<'src>, ResourceDef<'src>>,
    pub variable_declarations: HashMap<Token<'src>, Value<'src>>,
}

// TODO v2: type inference
// TODO check that parameter type match parameter default
// TODO check state call compatibility (no contradictory state)
// TODO if a parameter has a default, next ones must have one too
// TODO more tests
// TODO compatibility metadata (?)

impl<'src> AST<'src> {
    /// Produce the final AST data structure.
    /// Call this when all files have been added.
    pub fn from_code_index(code_index: CodeIndex) -> Result<AST> {
        let CodeIndex {
            enums,
            enum_mappings,
            resources,
            variable_declarations,
            parameter_defaults,
            parents,
            aliases,
        } = code_index;
        let mut var_context = VarContext::new();
        // first create enums since they have no dependencies
        let enum_list = AST::create_enums(&mut var_context, enums, enum_mappings)?;
        // global context depends on enums
        map_results(variable_declarations.iter(), |(variable, value)|
            var_context.new_variable(None, *variable, value.get_type())
        )?;
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
        //let aliases = AST::create_aliases(aliases)?;
        // prepare children list for each resource
        let mut children_list = AST::create_children_list(parents, &resources)?;
        // resources depend on everything else
        let resources = map_hashmap_results(resources.into_iter(), |(rn, rd)| {
            let children = match children_list.remove(&rn) {
                None => HashSet::new(),
                Some(ch) => ch,
            };
            Ok((
                rn,
                ResourceDef::from_resource_declaration(rn, rd, children, &global_context)?,
            ))
        })?;
        Ok(AST {
            global_context,
            resources,
            variable_declarations,
        })
    }

    /// Create global enum list structure from all source code enum and enum mapping declarations.
    /// Fill up the context with a single global variable per ancestor global enum.
    fn create_enums(
        context: &mut VarContext<'src>,
        enums: Vec<PEnum<'src>>,
        enum_mappings: Vec<PEnumMapping<'src>>,
    ) -> Result<EnumList<'src>> {
        let mut enum_list = EnumList::new();
        // First insert all simple enums
        map_results(enums.into_iter(), |e| {
            if e.global {
                context.new_enum_variable(None, e.name, e.name, None)?;
            }
            enum_list.add_enum(e)?;
            Ok(())
        });
        // Then iteratively insert mappings
        let mut map_count = enum_mappings.len();
        let mut mappings = enum_mappings;
        loop {
            // Try inserting every mapping that have an existing ancestor until there is no more
            let mut new_mappings = Vec::new();
            map_results(mappings.into_iter(), |em| {
                if enum_list.enum_exists(em.from) {
                    enum_list.add_mapping(em)?;
                } else {
                    new_mappings.push(em);
                }
                Ok(())
            })?;
            if new_mappings.is_empty() {
                // Yay, finished !
                break;
            } else if map_count == new_mappings.len() {
                // Nothing changed since last loop, we failed !
                map_results(new_mappings.iter(), |em| {
                    fail!(
                        em.to,
                        "Enum {} doesn't exist when trying to define mapping {}",
                        em.from,
                        em.to
                    )
                })?;
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
        map_results(variable_declarations.into_iter(), |(variable, value)| {
            if declarations.contains_key(&variable) {
                fail!(variable, "Variable {} already declared in {}", variable, declarations.entry(variable).key());
            }
            let val = Value::from_pvalue(gc, None, value)?;
            declarations.insert(variable,val);
            Ok(())
        })?;
        Ok(declarations)
    }

    /// Create default values for all resource and state parameters that have defaults.
    fn create_default_values(
        gc: &GlobalContext<'src>,
        parameter_defaults: HashMap<(Token<'src>, Option<Token<'src>>), Vec<Option<PValue<'src>>>>,
    ) -> Result<HashMap<(Token<'src>, Option<Token<'src>>), Vec<Option<Value<'src>>>>> {
        map_hashmap_results(parameter_defaults.into_iter(), |(id, defaults)| {
            Ok((
                id,
                map_vec_results(defaults.into_iter(), |def| {
                    Ok(match def {
                        Some(pvalue) => Some(Value::from_pvalue(gc, None, pvalue)?),
                        None => None,
                    })
                })?,
            ))
        })
    }

    /// Create Aliases from parsed aliases
    fn create_aliases(aliases: Vec<PAliasDef<'src>>) 
        -> Result<HashMap<Token<'src>,(Vec<Token<'src>>,HashMap<Token<'src>,Vec<Token<'src>>>)>> {
        // find sub resource
        // find sub state
        // 
        unimplemented!()
    }

    /// Produce the statically declared list of children for each resource.
    /// This will be extended with the dynamically generated one from state declarations.
    fn create_children_list(parents: Vec<(Token<'src>, Token<'src>)>,
                        resources: &HashMap<Token<'src>, TmpResourceDef<'src>>) -> Result<HashMap<Token<'src>,HashSet<Token<'src>>>> {
        let mut children_list = HashMap::new();
        for (child,parent) in parents {
            if !resources.contains_key(&parent) {
                fail!(child, "Resource {} declares {} as a parent, but it doesn't exist", child, parent);
            }
            children_list.entry(parent).or_insert(HashSet::new());
            children_list.get_mut(&parent).unwrap().insert(child);
        }
        Ok(children_list)
    }

    fn binding_check(&self, statement: &Statement) -> Result<()> {
        match statement {
            Statement::StateCall(_, _mode, res, res_params, name, params, _out) => {
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
            Statement::Case(_name, cases) => map_results(
                cases.iter(),
                |(_c, sts)| map_results(sts.iter(),|st| self.binding_check(st))
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
                    fix_results(cases.iter().flat_map( |(_cond, sts)| {
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
            Statement::VariableDefinition(_, v, _) => {
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
                    sts.iter().map(|st| self.enum_expression_check(variables, st))
                }))
            }
            _ => Ok(()),
        }
    }

    fn metadata_sub_check(k: &Token<'src>, v: &Value<'src>) -> Result<()> {
        match v {
            Value::String(s) => {
                if !s.is_static() {
                    // we don't what else we can do so fail to keep a better behaviour for later
                    fail!(
                        k,
                        "Metadata {} has a value that contains variables, this is not allowed",
                        k
                    );
                }
                Ok(())
            },
            Value::Number(_,_) => Ok(()),
            Value::EnumExpression(e) => fail!(k, "Metadata {} contains an enum expression, this is not allowed", k),
            Value::List(l) => map_results(l.iter(),|v| AST::metadata_sub_check(k,v)),
            Value::Struct(s) => map_results(s.iter(),|(_,v)| AST::metadata_sub_check(k,v)),
        }
    }
    fn metadata_check(&self, metadata: &HashMap<Token<'src>, Value<'src>>) -> Result<()> {
        map_results(metadata.iter(),|(k, v)| {
            AST::metadata_sub_check(k,v)
        })
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
            // TODO
            //"string",
            "int",
            "struct",
            "list",
            "if",
            "case",
            "enum",
            "global",
            "default",
            "resource",
            //"state",
            "fail",
            "log",
            "return",
            "noop",
            "format",
            "comment",
            //"dict",
            "json",
            "enforce",
            //"condition",
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
            Statement::VariableDefinition(_, name, _) => self.invalid_variable_check(*name, false),
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
