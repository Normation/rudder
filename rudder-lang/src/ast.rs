// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

pub mod context;
pub mod enum_tree;
pub mod enums;
pub mod resource;
pub mod value;

use self::{
    context::{VarContext, VarKind},
    enums::EnumList,
    resource::*,
    value::Value,
};
use crate::{error::*, parser::*};
use std::{
    cmp::Ordering,
    collections::{HashMap, HashSet},
};

// TODO v2: type inference, compatibility metadata
// TODO aliases
// TODO check that parameter type match parameter default
// TODO check state call compatibility

#[derive(Debug)]
pub struct AST<'src> {
    errors: Vec<Error>,
    pub context: VarContext<'src>,
    pub enum_list: EnumList<'src>,
    pub variable_declarations: HashMap<Token<'src>, Value<'src>>,
    pub parameter_defaults: HashMap<(Token<'src>, Option<Token<'src>>), Vec<Option<Value<'src>>>>, // also used as parameter list since that's all we have
    pub resource_list: HashSet<Token<'src>>,
    pub resources: HashMap<Token<'src>, ResourceDef<'src>>,
}

pub fn vec_collect_results<I, F, X, Y>(it: I, f: F, errors: &mut Vec<Error>) -> Vec<Y>
where
    I: Iterator<Item = X>,
    F: FnMut(X) -> Result<Y>,
{
    it.map(f)
        .filter(|r| {
            if let Err(e) = r {
                errors.push(e.clone());
                false
            } else {
                true
            }
        })
        .map(Result::unwrap)
        .collect()
}
impl<'src> AST<'src> {
    /// Produce the final AST data structure.
    /// Call this when all files have been parsed.
    pub fn from_past(past: PAST) -> Result<AST> {
        let PAST {
            enum_aliases,
            enums,
            sub_enums,
            resources,
            states,
            variable_declarations,
            parameter_defaults,
            parents,
            aliases: _aliases,
        } = past;
        let mut ast = AST::new();
        ast.add_enums(enums);
        ast.add_sub_enums(sub_enums);
        ast.add_enum_aliases(enum_aliases);
        ast.add_variables(variable_declarations);
        ast.add_default_values(parameter_defaults);
        ast.add_resource_list(&resources);
        let children = ast.create_children_list(parents);
        ast.add_resources(resources, states, children);
        //ast.add_aliases(aliases);
        if ast.errors.is_empty() {
            Ok(ast)
        } else {
            Err(Error::from_vec(ast.errors))
        }
    }
    fn new() -> AST<'src> {
        AST {
            errors: Vec::new(),
            context: VarContext::new(),
            enum_list: EnumList::new(),
            variable_declarations: HashMap::new(),
            parameter_defaults: HashMap::new(),
            resource_list: HashSet::new(),
            resources: HashMap::new(),
        }
    }

    /// Insert all initial enums
    fn add_enums(&mut self, enums: Vec<PEnum<'src>>) {
        for en in enums {
            if en.global {
                if let Err(e) = self.context.new_enum_variable(None, en.name, en.name, None) {
                    self.errors.push(e);
                }
            }
            if let Err(e) = self.enum_list.add_enum(en) {
                self.errors.push(e);
            }
        }
    }

    /// Insert all sub enum
    fn add_sub_enums(&mut self, mut sub_enums: Vec<PSubEnum<'src>>) {
        loop {
            let map_count = sub_enums.len();
            // Try inserting every mapping that have an existing ancestor until there is no more
            let mut new_enums = Vec::new();
            for se in sub_enums {
                match self.enum_list.extend_enum(se) {
                    Ok(Some(e)) => new_enums.push(e),
                    Ok(None) => {}
                    Err(e) => self.errors.push(e),
                }
            }
            if new_enums.is_empty() {
                // Yay, finished !
                break;
            } else if map_count == new_enums.len() {
                // Nothing changed since last loop, we failed !
                for se in new_enums {
                    match se.enum_name {
                        // This should be a global enum item
                        None => self.errors.push(err!(
                            se.name,
                            "Global enum item {} not found when trying to define sub enum {}",
                            se.name,
                            get_suggestion_message(
                                se.name.fragment(),
                                self.enum_list.global_item_iter()
                            )
                        )),
                        // We know in which enum we shoud be
                        Some(name) => self.errors.push(err!(
                            se.name,
                            "Enum {} item {} not found when trying to define sub enum {}",
                            name,
                            se.name,
                            get_suggestion_message(
                                se.name.fragment(),
                                self.enum_list.enum_item_iter(name)
                            )
                        )),
                    }
                }
                break;
            }
            sub_enums = new_enums;
        }
    }

    /// Insert all enums aliases
    fn add_enum_aliases(&mut self, aliases: Vec<PEnumAlias<'src>>) {
        for alias in aliases {
            if let Err(e) = self.enum_list.add_alias(alias) {
                self.errors.push(e);
            }
        }
    }

    /// Insert variables types into the variables context
    /// Insert the variables definition into the global declaration space
    fn add_variables(&mut self, variable_declarations: Vec<(Token<'src>, PValue<'src>)>) {
        for (variable, value) in variable_declarations {
            if let Err(e) = self.context.new_variable(
                None,
                variable,
                Value::from_static_pvalue(value.clone()).unwrap(),
            ) {
                self.errors.push(e);
            }
            let getter = |k| self.context.variables.get(&k).map(VarKind::clone);
            match Value::from_pvalue(&self.enum_list, &getter, value) {
                Err(e) => self.errors.push(e),
                Ok(val) => {
                    self.variable_declarations.insert(variable, val);
                }
            }
        }
    }

    /// Compute default parameter values
    fn add_default_values(
        &mut self,
        parameter_defaults: Vec<(Token<'src>, Option<Token<'src>>, Vec<Option<PValue<'src>>>)>,
    ) {
        for (resource, state, defaults) in parameter_defaults {
            // parameters with default values must be the last ones
            if let Err(e) = defaults.iter()
                .fold(Ok(None), |status, pv|
                    match (status, pv) {
                        (Ok(None), None)       => Ok(None),
                        (Ok(None), Some(x))    => Ok(Some(x)),
                        (Ok(Some(_)), Some(y)) => Ok(Some(y)),
                        //default followed by non default
                        (Ok(Some(_)), None)    => {
                            match state {
                                Some(s) => self.errors.push(err!(s,"Parameter default for state {} followed by parameter without default",s)),
                                None => self.errors.push(err!(resource,"Parameter default for resource {} followed by parameter without default",resource)),
                            };
                            Ok(None)
                        },
                        (Err(e), _)            => Err(e),
                    }
                )
            { self.errors.push(e); } // -> no default values
            self.parameter_defaults.insert(
                (resource, state),
                vec_collect_results(
                    // we could keep only 'Some' parameter if this was not aso used for parameter
                    // counting
                    defaults.into_iter(), //.filter(Option::is_some),
                    |def| {
                        Ok(match def {
                            Some(pvalue) => Some(Value::from_static_pvalue(pvalue)?),
                            None => None,
                        })
                    },
                    &mut self.errors,
                ),
            );
        }
    }

    /// List all resources and detect duplicates
    fn add_resource_list(&mut self, resources: &[PResourceDef<'src>]) {
        for res in resources {
            if self.resource_list.contains(&res.name) {
                self.errors.push(err!(
                    &res.name,
                    "Resource {} already defined at {}",
                    &res.name,
                    self.resource_list.get(&res.name).unwrap()
                ));
            } else {
                self.resource_list.insert(res.name);
            }
        }
    }

    /// Compute manually declared parent/child relationships
    fn create_children_list(
        &mut self,
        parents: Vec<(Token<'src>, Token<'src>)>,
    ) -> HashMap<Token<'src>, HashSet<Token<'src>>> {
        let mut children = HashMap::new();
        for (child, parent) in parents {
            if !self.resource_list.contains(&parent) {
                self.errors.push(err!(
                    &child,
                    "Resource {} declares {} as a parent, but it doesn't exist{}",
                    child,
                    parent,
                    get_suggestion_message(parent.fragment(), self.resource_list.iter()),
                ));
            } else {
                children.entry(parent).or_insert_with(HashSet::new);
                children.get_mut(&parent).unwrap().insert(child);
            }
        }
        children
    }

    /// Create and store resource objects
    fn add_resources(
        &mut self,
        resources: Vec<PResourceDef<'src>>,
        states: Vec<PStateDef<'src>>,
        mut children: HashMap<Token<'src>, HashSet<Token<'src>>>,
    ) {
        // first separate states by resource
        let mut state_list = self
            .resource_list
            .iter()
            .map(|k| (*k, Vec::new()))
            .collect::<HashMap<Token<'src>, Vec<PStateDef<'src>>>>();
        for st in states {
            match state_list.get_mut(&st.resource_name) {
                None => self.errors.push(err!(
                    st.name,
                    "Resource {} has not been defined for state {}{}",
                    st.resource_name,
                    st.name,
                    get_suggestion_message(st.resource_name.fragment(), state_list.keys())
                )),
                Some(v) => v.push(st),
            }
        }
        // now create resources
        for res in resources {
            let name = res.name;
            // or else because we have not stopped on duplicate resources
            let states = state_list.remove(&name).unwrap_or_else(Vec::new);
            let res_children = children.remove(&name).unwrap_or_else(HashSet::new);
            let (errs, resource) = ResourceDef::from_presourcedef(
                // TODO moove param count errors checker out of from_presourcedef
                res,
                states,
                res_children,
                &self.context,
                &self.parameter_defaults,
                &self.enum_list,
            );
            self.errors.extend(errs);
            if let Some(r) = resource {
                self.resources.insert(name, r);
            }
        }
    }

    /// binding_check function dependance: compares library and user function's parameters. if diff, output an error
    fn parameters_count_check(
        &self,
        resource: Token<'src>,
        state: Option<Token<'src>>,
        params: &[Value<'src>],
    ) -> Result<()> {
        let fun_kind = if let Some(st) = state { st } else { resource };
        let emptyvec = Vec::new();
        let defaults = self
            .parameter_defaults
            .get(&(resource, state))
            .unwrap_or(&emptyvec);
        let diff = defaults.len() as i32 - params.len() as i32;
        match diff.cmp(&0) {
            Ordering::Equal => (),
            Ordering::Greater => fail!(
                fun_kind,
                "{} instance of {} is missing parameters and there is no default values for them",
                if state.is_some() {
                    "Resource state"
                } else {
                    "Resource"
                },
                fun_kind
            ),
            Ordering::Less => fail!(
                fun_kind,
                "{} instance of {} has too many parameters, expecting {}, got {}",
                if state.is_some() {
                    "Resource state"
                } else {
                    "Resource"
                },
                fun_kind,
                defaults.len(),
                params.len()
            ),
        }
        Ok(())
    }

    fn binding_check(&self, statement: &Statement) -> Result<()> {
        match statement {
            Statement::StateDeclaration(sd) => {
                match self.resources.get(&sd.resource) {
                    None => fail!(
                        sd.resource,
                        "Resource type {} does not exist{}",
                        sd.resource,
                        get_suggestion_message(sd.resource.fragment(), self.resources.keys()),
                    ),
                    Some(res) => {
                        // Assume default parameter replacement and type inference if any has already be done
                        self.parameters_count_check(sd.resource, None, &sd.resource_params)?;
                        match_parameters(&res.parameters, &sd.resource_params, sd.resource)?;
                        match res.states.get(&sd.state) {
                            None => fail!(
                                sd.state,
                                "State {} does not exist for resource {}{}",
                                sd.state,
                                sd.resource,
                                get_suggestion_message(sd.state.fragment(), res.states.keys()),
                            ),
                            Some(st) => {
                                // Assume default parameter replacement and type inference if any has already be done
                                self.parameters_count_check(
                                    sd.resource,
                                    Some(sd.state),
                                    &sd.state_params,
                                )?;
                                match_parameters(&st.parameters, &sd.state_params, sd.state)
                            }
                        }
                    }
                }
            }
            Statement::Case(_name, cases) => map_results(cases.iter(), |(_c, sts)| {
                map_results(sts.iter(), |st| self.binding_check(st))
            }),
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
                    if cases.is_empty() {
                        fail!(keyword, "Case list must not be empty in { }", keyword)
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

    fn enum_expression_check(&self, context: &VarContext, statement: &Statement) -> Result<()> {
        match statement {
            Statement::Case(case, cases) => {
                let errors = self.enum_list.evaluate(cases, *case);
                if !errors.is_empty() {
                    return Err(Error::from_vec(errors));
                }
                fix_results(cases.iter().flat_map(|(_cond, sts)| {
                    sts.iter().map(|st| self.enum_expression_check(context, st))
                }))
            }
            _ => Ok(()),
        }
    }

    fn metadata_sub_check(k: &Token<'src>, v: &Value<'src>) -> Result<()> {
        match v {
            Value::String(s) => {
                if !s.is_static() {
                    // we don't know what else we can do so fail to keep a better behaviour for later
                    fail!(
                        k,
                        "Metadata {} has a value that contains variables, this is not allowed",
                        k
                    );
                }
                Ok(())
            }
            Value::Number(_, _) => Ok(()),
            Value::Boolean(_, _) => Ok(()), // actually not sure what should be returned here
            Value::EnumExpression(_) => fail!(
                k,
                "Metadata {} contains an enum expression, this is not allowed",
                k
            ),
            Value::List(l) => map_results(l.iter(), |v| AST::metadata_sub_check(k, v)),
            Value::Struct(s) => map_results(s.iter(), |(_, v)| AST::metadata_sub_check(k, v)),
        }
    }
    fn metadata_check(&self, metadata: &HashMap<Token<'src>, Value<'src>>) -> Result<()> {
        map_results(metadata.iter(), |(k, v)| AST::metadata_sub_check(k, v))
    }

    fn children_check(
        &self,
        name: Token<'src>,
        children: &HashSet<Token<'src>>,
        depth: u32,
    ) -> Result<()> {
        // This can be costly but since there is no guarantee the graph is connected, better solution is not obvious
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
                // TODO stored in AST now
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
            // old list
            // "struct", "list", "if", "case", "enum", "global", "default", "resource",
            // "fail", "log", "return", "noop", "format", "comment",
            // "json", "enforce", "audit",
            // TODO
            // header
            "format",
            // enums
            "enum",
            "global",
            "items",
            "alias",
            // types
            "num",
            "struct",
            "list", // "string", "boolean", // should not be used
            // variables
            "let",
            "resource", // "state", // should not be used
            // flow statements
            "if",
            "case",
            "default",
            "nodefault",
            "fail",
            "log",
            "log_debug",
            "log_info",
            "log_warn",
            "return",
            "noop",
            // historical invalid identifiers
            "comment",
            "json",
            "enforce",
            "audit", //"dict", "condition"
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
        if let Some(is_global) = self.enum_list.enum_is_global(name) {
            if !global || !is_global {
                // there is already a global variable for each global enum
                fail!(
                    name,
                    "Variable name {} cannot be used because it is an enum name",
                    name
                );
            }
        }
        if let Some(e) = self.enum_list.global_enum(name) {
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
                "Variable name {} cannot be used because it is a resource name",
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
        for (name, _value) in self.context.iter() {
            // check for invalid variable name
            errors.push(self.invalid_variable_check(*name, true));
        }
        // analyse enum names
        for e in self.enum_list.enum_iter() {
            errors.push(self.invalid_identifier_check(*e));
        }
        // analyse enum items
        for e in self.enum_list.global_item_iter() {
            errors.push(self.invalid_identifier_check(*e));
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
