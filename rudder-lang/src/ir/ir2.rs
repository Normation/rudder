// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use super::{context::*, enums::EnumList, resource::*, value::*, ir1::IR1};
use crate::{error::*, parser::*};
use std::{
    cmp::Ordering, rc::Rc,
    collections::{HashMap, HashSet},
};

// TODO v2: type inference, compatibility metadata
// TODO aliases
// TODO check state call compatibility


/// An IR2 is the second intermediate representation
/// It takes everything from IR1 plus :
/// - every identifier cannot be a reserved word
/// - a variable name cannot conflict with something else (resource, enum, constant, ...)
/// - resource and state call must point to existing elements
/// - cases must be complete an non overlapping
/// - resource cannot make a loop (it cannot contain itself)
/// This is currently identical to IR1, but it may diverge in the future
/// Moreover having a different structure helps having clear compilation steps and smaller tests.
#[derive(Debug)]
pub struct IR2<'src> {
    // the context is used for variable lookup whereas variable_definitions is used for code generation
    pub context: Rc<VarContext<'src>>,
    pub enum_list: EnumList<'src>,
    pub variable_definitions: HashMap<Token<'src>, ComplexValue<'src>>,
    pub parameter_defaults: HashMap<(Token<'src>, Option<Token<'src>>), Vec<Option<Constant<'src>>>>, // also used as parameter list since that's all we have
    pub resource_list: HashSet<Token<'src>>,
    pub resources: HashMap<Token<'src>, ResourceDef<'src>>,
}

impl<'src> IR2<'src> {
    pub fn from_ir1(ir1: IR1<'src>) -> Result<Self> {
        let IR1 { errors, context, enum_list, variable_definitions, parameter_defaults, resource_list, resources} = ir1;
        let ir2 = IR2 { context, enum_list, variable_definitions, parameter_defaults, resource_list, resources };

        // Analyze step 1: no prerequisite
        let mut errors = Vec::new();
        // analyze resources
        for (rn, resource) in ir2.resources.iter() {
            // check resource name
            errors.push(ir2.invalid_identifier_check(*rn));
            for (sn, state) in resource.states.iter() {
                // check status name
                errors.push(ir2.invalid_identifier_check(*sn));
                for st in state.statements.iter() {
                    // check for resources and state existence
                    // check for matching parameter and type
                    errors.push(ir2.binding_check(st));
                    // check for variable names in statements
                    errors.push(ir2.invalid_variable_statement_check(st));
                    // check for enum expression validity
                    errors.push(ir2.enum_expression_check(&state.context, st));
                    // check for case validity
                    errors.push(ir2.cases_check(&state.context, st, true));
                }
            }
        }
        // analyze global vars
        // TODO what about local var ?
        for (name, _value) in ir2.context.iter() {
            // check for invalid variable name
            errors.push(ir2.invalid_variable_check(*name, true));
        }
        // analyse enum names
        for e in ir2.enum_list.enum_iter() {
            errors.push(ir2.invalid_identifier_check(*e));
        }
        // analyse enum items
        for e in ir2.enum_list.global_item_iter() {
            errors.push(ir2.invalid_identifier_check(*e));
        }
        // Stop here if there is any error
        fix_results(errors.into_iter())?;

        // Analyze step 2: step 1 must have passed
        errors = Vec::new();
        for (rname, resource) in ir2.resources.iter() {
            // check that resource definition is not recursive
            errors.push(ir2.children_check(*rname, &resource.children, 0));
        }
        fix_results(errors.into_iter())?;
        Ok(ir2)
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

    /// binding_check function dependence: compares library and user function's parameters. if diff, output an error
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

    // same a above but for the variable definition statement
    fn invalid_variable_statement_check(&self, st: &Statement<'src>) -> Result<()> {
        match st {
            Statement::VariableDefinition(var) => self.invalid_variable_check(var.name, false),
            _ => Ok(()),
        }
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
            Statement::VariableDefinition(var) => {
                if !first_level {
                    fail!(
                        var.name,
                        "Variable definition {} within case are forbidden at the moment",
                        var.name
                    ); // because it is hard to check that variables are always defined
                }
            }
            _ => {}
        }
        Ok(())
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
