// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use super::value::Value;
use crate::{error::*, parser::Token};
use std::collections::{hash_map, HashMap};
//use super::enums::EnumList;
//use super::value::Value;

/// Types a variable can take
#[derive(Debug, PartialEq, Clone)]
pub enum VarType<'src> {
    Enum(Token<'src>),
    String,
    Number,
    Boolean,
    List,                                   // TODO should be subtypable / generic like struct
    Struct(HashMap<String, VarType<'src>>), // Token instead of string ?
}

/// Implement converion from value to type (a value already has a type)
impl<'src> From<&Value<'src>> for VarType<'src> {
    fn from(val: &Value<'src>) -> Self {
        match val {
            Value::String(_) => VarType::String,
            Value::Number(_, _) => VarType::Number,
            Value::Boolean(_, _) => VarType::Boolean,
            Value::EnumExpression(_) => VarType::Boolean,
            Value::List(_) => VarType::List,
            Value::Struct(s) => {
                let spec = s
                    .iter()
                    .map(|(k, v)| (k.clone(), VarType::from(v)))
                    .collect::<HashMap<String, VarType<'src>>>();
                VarType::Struct(spec)
            }
        }
    }
}

impl<'src> VarType<'src> {
    /// Create a type from parsed tokens
    pub fn from_ptype(
        var_type: Option<Token<'src>>,
        mut sub_elts: Vec<Token<'src>>,
    ) -> Result<VarType<'src>> {
        Ok(if sub_elts.len() == 0 {
            match var_type {
                None => VarType::String, // default type is String
                Some(name) => {
                    // supported named types
                    if *name == "String" {
                        VarType::String
                    } else if *name == "Number" {
                        VarType::Number
                    } else if *name == "Boolean" {
                        VarType::Boolean
                    } else if *name == "List" {
                        VarType::List
                    } else if *name == "Struct" {
                        VarType::Struct(HashMap::new())
                    } else {
                        fail!(name, "{} is an invalid type", name)
                    }
                }
            }
        } else {
            // this is be a struct sub part
            let first = sub_elts.remove(0);
            let sub = VarType::from_ptype(var_type, sub_elts)?;
            let mut map = HashMap::new();
            map.insert(String::from(*first), sub);
            VarType::Struct(map)
        })
    }
}

// TODO forbid variables names like global enum items (or enum type)

/// A context is a list of variables name with their type (and value if they are constant).
/// A context doesn't point to a child or parent context because it would mean holding
/// their reference which would prevent them from being modified.
/// So this reference is asked by methods when they are needed.
#[derive(Debug, Clone)]
pub struct VarContext<'src> {
    variables: HashMap<Token<'src>, VarType<'src>>,
}

impl<'src> VarContext<'src> {
    /// Constructor
    pub fn new() -> VarContext<'static> {
        VarContext {
            variables: HashMap::new(),
        }
    }

    /// Returns the type of a given variable or None if variable doesn't exist
    pub fn get(&self, key: &Token<'src>) -> Option<VarType<'src>> {
        // clone should not be necessary, but i don't know how to handle lifetime hell without it
        self.variables.get(key).map(VarType::clone)
    }

    /// Iterator over all variables of this context.
    pub fn iter(&self) -> hash_map::Iter<Token<'src>, VarType<'src>> {
        self.variables.iter()
    }

    /// Add a knew variable knowing its type (or its value which its convertible to type)
    pub fn add_variable<T>(
        &mut self,
        upper_context: Option<&VarContext<'src>>, // TODO maybe we should not have an upper context and just clone the context when needed
        name: Token<'src>,
        type_value: T,
    ) -> Result<()>
    where
        T: Into<VarType<'src>>,
    {
        // disallow variable shadowing (TODO is that what we want ?)
        if let Some(gc) = upper_context {
            if gc.variables.contains_key(&name) {
                fail!(
                    name,
                    "Variable {} hides global variable {}",
                    name,
                    gc.variables.get_key_value(&name).unwrap().0
                );
            }
        }
        // disallow variable redefinition except for struct which extends the structure
        if self.variables.contains_key(&name) {
            let current = self.variables.get_mut(&name).unwrap();
            match current {
                VarType::Struct(desc) => match type_value.into() {
                    VarType::Struct(new_desc) => {
                        VarContext::extend_struct(name, desc, new_desc)?;
                    }
                    _ => fail!(
                        name,
                        "Variable {} extends a struct {} but is not a struct",
                        name,
                        self.variables.entry(name).key()
                    ),
                },
                _ => fail!(
                    name,
                    "Variable {} redefines an existing variable {}",
                    name,
                    self.variables.entry(name).key()
                ),
            }
        } else {
            self.variables.insert(name, type_value.into());
        }
        Ok(())
    }

    /// extend a struct description with another struct description (recursive)
    fn extend_struct(
        name: Token<'src>,
        desc: &mut HashMap<String, VarType<'src>>,
        new_desc: HashMap<String, VarType<'src>>,
    ) -> Result<()> {
        for (k, v) in new_desc {
            match desc.get_mut(&k) {
                None => {
                    desc.insert(k, v);
                }
                Some(VarType::Struct(subtype)) => match v {
                    VarType::Struct(new_subtype) => {
                        VarContext::extend_struct(name, subtype, new_subtype)?
                    }
                    _ => fail!(name, "Element {} is defined twice in {}", k, name),
                },
                _ => fail!(name, "Element {} is defined twice in {}", k, name),
            }
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::parser::{tests::*, *};
    use maplit::hashmap;
    use pretty_assertions::assert_eq;

    #[test]
    fn test_context() {
        let mut context = VarContext::new();
        assert!(context
            .add_variable(
                None,
                pidentifier_t("var1"),
                VarType::Enum(pidentifier_t("enum1"))
            )
            .is_ok());
        assert!(context
            .add_variable(
                None,
                pidentifier_t("var2"),
                VarType::Enum(pidentifier_t("enum1"))
            )
            .is_ok());
        let mut c = VarContext::new();
        assert!(c
            .add_variable(
                Some(&context),
                pidentifier_t("var3"),
                VarType::Enum(pidentifier_t("enum2"))
            )
            .is_ok());
        assert!(c
            .add_variable(
                Some(&context),
                pidentifier_t("var4"),
                VarType::Enum(pidentifier_t("enum1"))
            )
            .is_ok());
    }

    #[test]
    fn test_context_tree_generator() {
        fn add_variable<'a>(context: &mut VarContext<'a>, input: &'a str) -> Result<()> {
            let PVariableDecl {
                metadata: _,
                name,
                sub_elts,
                var_type,
            } = pvariable_declaration_t(input);
            let type_ = VarType::from_ptype(var_type, sub_elts).unwrap();
            context.add_variable(None, name, type_)
        }

        let mut context = VarContext::new();

        assert!(add_variable(&mut context, "let sys.windows").is_ok());
        assert!(add_variable(&mut context, "let sys.windows").is_err()); // direct duplicate
        assert!(add_variable(&mut context, "let sys.windows.win7").is_err()); // sub element of a string var
        assert!(add_variable(&mut context, "let sys.linux.debian_9").is_ok()); // push inner into undeclared element
        assert!(add_variable(&mut context, "let sys.linux.debian_10").is_ok());
        assert!(add_variable(&mut context, "let sys.linux.debian_9").is_err()); // inner non-direct duplicate
        assert!(add_variable(&mut context, "let sys.long.var.decl.ok").is_ok()); // deep nested element
        assert!(add_variable(&mut context, "let sys.long.var.decl.ok_too").is_ok()); // push deep in nest element
        assert!(add_variable(&mut context, "let sys.long.var.decl2").is_ok()); // post-push deep outer element
        assert!(add_variable(&mut context, "let sys.linux").is_err()); // outtest non-direct duplicate

        let os = hashmap! {
            "long".into() => VarType::Struct(hashmap! {
                "var".into() => VarType::Struct(hashmap! {
                    "decl".into() => VarType::Struct(hashmap! {
                        "ok".into() => VarType::String,
                        "ok_too".into() => VarType::String,
                    }),
                    "decl2".into() => VarType::String,
                }),
            }),
            "linux".into() => VarType::Struct(hashmap! {
                "debian_9".into() => VarType::String,
                "debian_10".into() => VarType::String,
            }),
            "windows".into() => VarType::String,
        };

        assert_eq!(context.variables[&"sys".into()], VarType::Struct(os));
    }
}
