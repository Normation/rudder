// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use super::value::*;
use crate::{error::*, parser::Token, parser::PType};
use std::collections::{hash_map, HashMap};
use std::rc::Rc;
use std::fmt;
use std::fmt::{Display, Formatter};

/// Types some data can take
/// TODO: isn't this the same as a PType
#[derive(Debug, PartialEq, Clone)]
pub enum Type<'src> {
    Enum(Token<'src>),
    String,
    Number,
    Boolean,
    List,                                   // TODO should be subtypable / generic like struct
    Struct(HashMap<String, Type<'src>>), // Token instead of string ?
}

impl<'src> Display for Type<'src> {
    fn fmt(&self, f: &mut Formatter<'_>) -> fmt::Result {
        write!(f, "{}", match self {
            Type::Enum(t) => format!("enum {}",t),
            Type::String => "string".into(),
            Type::Number => "number".into(),
            Type::Boolean => "boolean".into(),
            Type::List => "list".into(),
            // TODO proper struct type format
            Type::Struct(s) => "struct".into(),
        })
    }
}

impl<'src> Type<'src> {
    /// Create a type from parsed type
    pub fn from_ptype(
        type_: Option<PType<'src>>,
        mut sub_elts: Vec<Token<'src>>,
    ) -> Result<Self> {
        Ok(if sub_elts.len() == 0 {
            match type_ {
                None => Type::String, // default type is String
                Some(PType::String) => Type::String,
                Some(PType::Number) => Type::Number,
                Some(PType::Boolean) => Type::Boolean,
                Some(PType::Struct) => Type::Struct(HashMap::new()),
                Some(PType::List) => Type::List,
                _ => panic!("Phantom type should never be created !")
            }
        } else {
            // this is a struct sub part
            let first = sub_elts.remove(0);
            let sub = Type::from_ptype(type_, sub_elts)?;
            let mut map = HashMap::new();
            map.insert(String::from(*first), sub);
            Type::Struct(map)
        })
    }

    /// Find the type of a given value
    pub fn from_value(val: &Value<'src>) -> Self {
        match val {
            Value::String(_) => Type::String,
            Value::Number(_, _) => Type::Number,
            Value::Boolean(_, _) => Type::Boolean,
            Value::EnumExpression(_) => Type::Boolean,
            Value::List(_) => Type::List,
            Value::Struct(s) => {
                let spec = s
                    .iter()
                    .map(|(k, v)| (k.clone(), Type::from_value(v)))
                    .collect::<HashMap<String, Type<'src>>>();
                Type::Struct(spec)
            }
        }
    }

    /// Find the type of a given complex value
    pub fn from_complex_value(val: &ComplexValue<'src>) -> Result<Self> {
        // just keep first item since type is checked later
        Ok(Type::from_value(val.first_value()?))
    }

    /// Find the type of a given constant
    pub fn from_constant(val: &Constant<'src>) -> Self {
        match val {
            Constant::String(_, _) => Type::String,
            Constant::Number(_, _) => Type::Number,
            Constant::Boolean(_, _) => Type::Boolean,
            Constant::List(_) => Type::List,
            Constant::Struct(s) => {
                let spec = s
                    .iter()
                    .map(|(k, v)| (k.clone(), Type::from_constant(v)))
                    .collect::<HashMap<String, Type<'src>>>();
                Type::Struct(spec)
            }
        }
    }
}

// TODO forbid variables names like global enum items (or enum type)

/// A context is a list of variables name with their type (and value if they are constant).
/// A context doesn't point to a child or parent context because it would mean holding
/// their reference which would prevent them from being modified.
/// So this reference is asked by methods when they are needed.
#[derive(Debug, Clone)]
pub struct VarContext<'src> {
    parent: Option<Rc<VarContext<'src>>>,
    types: HashMap<Token<'src>, Type<'src>>,
    definitions: HashMap<Token<'src>, Value<'src>>,
}

impl<'src> VarContext<'src> {
    /// Constructor
    pub fn new(parent: Option<Rc<VarContext>>) -> VarContext {
        VarContext {
            parent,
            types: HashMap::new(),
            definitions: HashMap::new(),
        }
    }

    /// Returns the type of a given variable or None if variable doesn't exist
    pub fn get_type(&self, key: &Token<'src>) -> Option<Type<'src>> {
        // clone should not be necessary, but i don't know how to handle lifetime hell without it
        self.types
            .get(key)
            .map(Type::clone)
            .or_else(
                || self.parent.clone()
                    .and_then(|p| p.get_type(key))
            )
    }

    /// Iterator over all variables of this context.
    pub fn iter(&self) -> hash_map::Iter<Token<'src>, Type<'src>> {
        self.types.iter()
    }

    fn must_not_exist(&self, name: &Token<'src>) -> Result<()> {
        // disallow variable shadowing (TODO is that what we want ?)
        if let Some(parent) = &self.parent {
            parent.must_not_exist(name)?;
        }
        // disallow variable redefinition
        if self.get_type(name).is_some() {
            fail!(
                name,
                "Variable {} hides existing an variable {}",
                name,
                self.types.get_key_value(&name).unwrap().0
            );
        }
        Ok(())
    }

    /// Add a knew variable knowing its type
    pub fn add_variable_declaration(
        &mut self,
        name: Token<'src>,
        type_: Type<'src>,
    ) -> Result<()> {
        // disallow variable shadowing (TODO is that what we want ?)
        if let Some(parent) = &self.parent {
            parent.must_not_exist(&name)?;
        }

        // disallow variable redefinition except for struct which extends the structure
        if self.types.contains_key(&name) {
            let current = self.types.get_mut(&name).unwrap();
            match current {
                Type::Struct(desc) => match type_ {
                    Type::Struct(new_desc) => {
                        VarContext::extend_struct(name, desc, new_desc)?;
                    }
                    _ => fail!(
                        name,
                        "Variable {} extends a struct {} but is not a struct",
                        name,
                        self.types.entry(name).key()
                    ),
                },
                _ => fail!(
                    name,
                    "Variable {} redefines an existing variable {}",
                    name,
                    self.types.entry(name).key()
                ),
            }
        } else {
            self.types.insert(name, type_);
        }
        Ok(())
    }

    /// extend a struct description with another struct description (recursive)
    fn extend_struct(
        name: Token<'src>,
        desc: &mut HashMap<String, Type<'src>>,
        new_desc: HashMap<String, Type<'src>>,
    ) -> Result<()> {
        for (k, v) in new_desc {
            match desc.get_mut(&k) {
                None => {
                    desc.insert(k, v);
                }
                Some(Type::Struct(subtype)) => match v {
                    Type::Struct(new_subtype) => {
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
        let mut context = VarContext::new(None);
        assert!(context
            .add_variable_declaration(
                pidentifier_t("var1"),
                Type::Enum(pidentifier_t("enum1"))
            )
            .is_ok());
        assert!(context
            .add_variable_declaration(
                pidentifier_t("var2"),
                Type::Enum(pidentifier_t("enum1"))
            )
            .is_ok());
        let mut c = VarContext::new(Some(Rc::new(context)));
        assert!(c
            .add_variable_declaration(
                pidentifier_t("var3"),
                Type::Enum(pidentifier_t("enum2"))
            )
            .is_ok());
        assert!(c
            .add_variable_declaration(
                pidentifier_t("var4"),
                Type::Enum(pidentifier_t("enum1"))
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
                type_,
            } = pvariable_declaration_t(input);
            let type_ = Type::from_ptype(type_, sub_elts).unwrap();
            context.add_variable_declaration(name, type_)
        }

        let mut context = VarContext::new(None);

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
            "long".into() => Type::Struct(hashmap! {
                "var".into() => Type::Struct(hashmap! {
                    "decl".into() => Type::Struct(hashmap! {
                        "ok".into() => Type::String,
                        "ok_too".into() => Type::String,
                    }),
                    "decl2".into() => Type::String,
                }),
            }),
            "linux".into() => Type::Struct(hashmap! {
                "debian_9".into() => Type::String,
                "debian_10".into() => Type::String,
            }),
            "windows".into() => Type::String,
        };

        assert_eq!(context.types[&"sys".into()], Type::Struct(os));
    }
}
