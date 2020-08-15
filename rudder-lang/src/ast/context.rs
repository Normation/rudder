// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use super::value::Value;
use crate::{error::*, parser::Token};
use std::collections::{hash_map, HashMap};
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
    // struct type (string could be token here)
    Struct(HashMap<String,VarKind<'src>>),
}

// TODO forbid variables names like global enum items (or enum type)

/// A context is a list of variables name with their type (and value if they are constant).
/// A context doesn't point to a child or parent context because it would mean holding
/// their reference which would prevent them from being modified.
/// So this reference is asked by methods when they are needed.
#[derive(Debug, Clone)]
pub struct VarContext<'src> {
    variables: HashMap<Token<'src>, VarKind<'src>>,
}

impl<'src> VarContext<'src> {
    /// Constructor
    pub fn new() -> VarContext<'static> {
        VarContext {
            variables: HashMap::new(),
        }
    }

    /// Returns the type of a given variable or None if variable doesn't exist
    pub fn get(&self, key: &Token<'src>) -> Option<VarKind<'src>> {
        self.variables.get(key).map(VarKind::clone)
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
            if let VarKind::Struct(_) = self.variables[&name] {
            } else {
                fail!(
                    name,
                    "Variable {} already defined {}",
                    name,
                    self.variables.entry(name).key()
                );
            }
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
    /// Recursive function, meaning it goes deep to insert an element (possibly nested) and check it does not exist yet
    /// If the full path is exactly the same as any existing global variable, throws an error
    fn push_new_variable(stored_type: &mut HashMap<String,VarKind<'src>>, new_var: Value<'src>) -> Result<()> {
        let (key_to_push, value_to_push) = match new_var {
            // always has a single field
            Value::Struct(new_map) => new_map
                .into_iter()
                .next()
                // Should not happen
                .expect("Parsing should not generate empty struct"),
            _ => {
                // Should not happen
                panic!("Parsing should generate struct");
            }
        };
        match &value_to_push {
            // go next depth level
            Value::Struct(s) => {
                if ! stored_type.contains_key(&key_to_push) {
                    stored_type.insert(key_to_push.clone(),VarKind::Struct(HashMap::new()));
                }
                if let Some(stored_v) = stored_type.get_mut(&key_to_push) {
                    match stored_v {
                        VarKind::Struct(s_type) => {
                            Self::push_new_variable(s_type, value_to_push);
                        },
                        _ => {
                            // TODO we should have a way to have proper token for positioning here
                            fail!(Token::from(""), "Variable {} is the wrong type", key_to_push);
                        }
                    }
                }
            },
            // last level, insert
            _ => {
                if stored_type.contains_key(&key_to_push) {
                    // TODO we should have a way to have proper token for positioning here
                    fail!(Token::from(""), "Variable {} is a duplicate", key_to_push);
                }
                stored_type.insert(key_to_push.to_owned(), VarKind::Generic(value_to_push));
            },
        };
        Ok(())
    }

    /// Create a generic variable in this context.
    pub fn new_variable(
        &mut self,
        upper_context: Option<&VarContext<'src>>,
        name: Token<'src>,
        value: Value<'src>,
    ) -> Result<()> {
        // check first
        self.check_new_var(upper_context, name)?;
        if let Value::Struct(struct_type) = &value {
            // this is the first declaration of a struct typed magic var
            // Maybe we want a more formal declaration (for example "let sys: Struct")
            // or not and let this act as a namespace without the need to implement more
            if ! self.variables.contains_key(&name) {
                self.variables.insert(name, VarKind::Struct(HashMap::new()));
            }
        }
        if let Some(existing_kind) = self.variables.get_mut(&name) {
            match existing_kind {
                VarKind::Struct(existing_var) => {
                    Self::push_new_variable(existing_var, value)?
                }
                _ => { },
            };
        } else {
            self.variables.insert(name, VarKind::Generic(value));
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use maplit::hashmap;
    use crate::parser::{tests::*, *};
    use pretty_assertions::assert_eq;

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

    #[test]
    fn test_context_tree_generator() {
        let string_value = VarKind::Generic(
            Value::from_static_pvalue(PValue::generate_automatic(PType::String))
                .unwrap());
        fn add_variable<'a>(context: &mut VarContext<'a>, input: &'a str) -> Result<()> {
            let (name,val) = pvariable_declaration_t(input);
            let value = Value::from_static_pvalue(val)?;
            context.new_variable(None, name, value)
        }

        let mut context = VarContext::new();

        assert!(add_variable(&mut context, "let sys.windows").is_ok());
        assert!(add_variable(&mut context, "let sys.windows").is_err()); // direct duplicate
        assert!(add_variable(&mut context, "let sys.windows.win7").is_err()); // sub element of a string var
        assert!(add_variable(&mut context, "let sys.linux.debian_9").is_ok()); // push inner into undeclared element
        assert!(add_variable(&mut context, "let sys.linux.debian_10").is_ok());
        assert!(add_variable(&mut context, "let sys.linux.debian_9").is_ok()); // inner non-direct duplicate // TODO should be error but using values in varking::generic makes it too hard to detect
        assert!(add_variable(&mut context, "let sys.long.var.decl.ok").is_ok()); // deep nested element
        assert!(add_variable(&mut context, "let sys.long.var.decl.ok_too").is_ok()); // push deep in nest element
        assert!(add_variable(&mut context, "let sys.long.var.decl2").is_ok()); // post-push deep outer element
        assert!(add_variable(&mut context, "let sys.linux").is_err()); // outtest non-direct duplicate

        let os = hashmap! {
            "long".into() => VarKind::Struct(hashmap! {
                "var".into() => VarKind::Struct(hashmap! {
                    "decl".into() => VarKind::Struct(hashmap! {
                        "ok".into() => string_value.clone(),
                        "ok_too".into() => string_value.clone(),
                    }),
                    "decl2".into() => string_value.clone(),
                }),
            }),
            "linux".into() => VarKind::Struct(hashmap! {
                "debian_9".into() => string_value.clone(),
                "debian_10".into() => string_value.clone(),
            }),
            "windows".into() => string_value.clone(),
        };

        assert_eq!(context.variables[&"sys".into()], VarKind::Struct(os));
    }
}
