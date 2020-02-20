// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use crate::error::*;
use crate::parser::Token;
use super::value::Value;
use std::collections::hash_map;
use std::collections::HashMap;
//use super::enums::EnumList;
//use super::value::Value;

/// Variable are categorized by kinds.
/// This allows segregating variables that can only be used in some places.
#[derive(Debug, PartialEq, Clone)]
pub enum VarKind<'src> {
    //   Enum          item value
    Enum(Token<'src>, Option<Token<'src>>),
    //     type
    Generic(Value<'src>),
}

// TODO forbid variables names like global enum items (or enum type)

/// A context is a list of variables name with their type (and value if they are constant).
/// A context doesn't point to a child or parent context because it would mean holding
/// their reference which would prevent them from being modified.
/// So this reference is asked by methods when they are needed.
#[derive(Debug, Clone)]
pub struct VarContext<'src> {
    pub variables: HashMap<Token<'src>, VarKind<'src>>,
}

impl<'src> VarContext<'src> {
    /// Constructor
    pub fn new() -> VarContext<'static> {
        VarContext {
            variables: HashMap::new(),
        }
    }

    /// Iterator over all variables of this context.
    pub fn iter(&self) -> hash_map::Iter<Token<'src>, VarKind<'src>> {
        self.variables.iter()
    }

    /// Checks whether a variable already exists in a given scope
    fn check_new_var(
        &mut self,
        upper_context: Option<&VarContext<'src>>,
        name: Token<'src>,
    ) -> Result<()> {
        if self.variables.contains_key(&name) {
            fail!(
                name,
                "Variable {} already defined {}",
                name,
                self.variables.entry(name).key()
            );
        }
        // Do not allow a local name to hide a global name
        match upper_context {
            None => {}
            Some(gc) => {
                if gc.variables.contains_key(&name) {
                    fail!(
                        name,
                        "Variable {} hides global variable {}",
                        name,
                        self.variables.entry(name).key()
                    );
                }
            }
        }
        Ok(())
    }

    /// Create a variable of kind enum in this context.
    pub fn new_enum_variable(
        &mut self,
        upper_context: Option<&VarContext<'src>>,
        name: Token<'src>,
        enum1: Token<'src>,
        value: Option<Token<'src>>,
    ) -> Result<()> {
        self.check_new_var(upper_context, name)?;
        self.variables.insert(name, VarKind::Enum(enum1, value));
        Ok(())
    }

    /// Inserts a key, value into its related parent content.
    /// Recursive function, meaning it goes deep to insert an element and check it does not exist yet
    /// If the path is exactly the same as any existing global variable, throws an error
    fn push_new_variable(name: Token<'src>, stored_ctx: &mut Value<'src>, new_var: &Value<'src>) -> Result<()> {
        match stored_ctx {
            Value::Struct(existing_map) => {
                for (stored_k, stored_v) in existing_map.iter_mut() {
                    match new_var {
                        Value::Struct(new_map) => {
                            for (new_k, new_v) in new_map {
                                if stored_k == new_k {
                                    return Self::push_new_variable(name, stored_v, &new_v)
                                } else {
                                    existing_map.insert(new_k.to_owned(), new_v.clone());
                                    return Ok(())
                                }
                            }
                        },
                        // A bit tricky, following fail is a recursion side effect: code is designed to push directly the parent struct
                        // not its String (or else) content. If another type has to be pushed, it means we entered into the recursive condition
                        // therefore the content to push already exists
                        _ => fail!(
                            name,
                            "Variable {} already defined",
                            name,
                        )
                    }
                }
                Ok(())
            },
            // Not really sure which kind of cases this could handle, could maybe panic
            _ => unimplemented!()
        }
    }

    /// Create a generic variable in this context.
    pub fn new_variable(
        &mut self,
        upper_context: Option<&VarContext<'src>>,
        name: Token<'src>,
        value: Value<'src>,
    ) -> Result<()> {
        if let Some(ref mut existing_kind) = self.variables.get_mut(&name) {
            match existing_kind {
                VarKind::Generic(ref mut existing_var) => Self::push_new_variable(name, existing_var, &value)?,
                // For now could as well be a panic since we are not currently allowing splitted enum declarations
                VarKind::Enum(_, _) => unimplemented!()
            };
        } else {
            // now only the second part of this checker makes really sense here since the first check is already done
            // (unless push_or_err_rec destroys the existing variable)
            self.check_new_var(upper_context, name)?;
            self.variables.insert(name, VarKind::Generic(value));
        }
        Ok(())
    }

    /*/// Create a constant in this context.
        pub fn new_constant(
            &mut self,
            upper_context: Option<&VarContext<'src>>,
            name: Token<'src>,
            value: Value<'src>,
        ) -> Result<()> {
            self.new_var(upper_context, name)?;
            self.variables.insert(name, VarKind::Constant(ptype,value));
            Ok(())
        }
    */
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::parser::tests::*;

    #[test]
    fn test_context() {
        let mut context = VarContext::new();
        assert!(context
            .new_enum_variable(None, pidentifier_t("var1"), pidentifier_t("enum1"), None)
            .is_ok());
        assert!(context
            .new_enum_variable(
                None,
                pidentifier_t("var2"),
                pidentifier_t("enum1"),
                Some(pidentifier_t("debian"))
            )
            .is_ok());
        let mut c = VarContext::new();
        assert!(c
            .new_enum_variable(
                Some(&context),
                pidentifier_t("var3"),
                pidentifier_t("enum2"),
                None
            )
            .is_ok());
        assert!(c
            .new_enum_variable(
                Some(&context),
                pidentifier_t("var4"),
                pidentifier_t("enum1"),
                Some(pidentifier_t("ubuntu"))
            )
            .is_ok());
    }
}
