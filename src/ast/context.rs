use super::Value;
use crate::error::*;
use crate::parser::{PType, Token};
use std::collections::hash_map;
use std::collections::HashMap;

// variable kind
#[derive(Debug, PartialEq)]
pub enum VarKind<'src> {
    //       Resource type (File, ...) TODO do we want that ?
    //Resource(String),
    //   Enum          item value
    Enum(Token<'src>, Option<Token<'src>>),
    //     type
    Generic(PType),
    //       value
    Constant(Value<'src>),
}

// TODO forbid variables names like global enum items (or enum type)

#[derive(Debug)]
pub struct VarContext<'src> {
    variables: HashMap<Token<'src>, VarKind<'src>>,
}

impl<'src> VarContext<'src> {
    pub fn new() -> VarContext<'static> {
        VarContext {
            variables: HashMap::new(),
        }
    }

    pub fn iter(&self) -> hash_map::Iter<Token<'src>, VarKind<'src>> {
        self.variables.iter()
    }

    fn new_var(
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

    pub fn new_enum_variable(
        &mut self,
        upper_context: Option<&VarContext<'src>>,
        name: Token<'src>,
        enum1: Token<'src>,
        value: Option<Token<'src>>,
    ) -> Result<()> {
        self.new_var(upper_context, name)?;
        self.variables.insert(name, VarKind::Enum(enum1, value));
        Ok(())
    }

    pub fn new_variable(
        &mut self,
        upper_context: Option<&VarContext<'src>>,
        name: Token<'src>,
        ptype: PType,
    ) -> Result<()> {
        self.new_var(upper_context, name)?;
        self.variables.insert(name, VarKind::Generic(ptype));
        Ok(())
    }

    pub fn new_constant(
        &mut self,
        upper_context: Option<&VarContext<'src>>,
        name: Token<'src>,
        value: Value<'src>,
    ) -> Result<()> {
        self.new_var(upper_context, name)?;
        self.variables.insert(name, VarKind::Constant(value));
        Ok(())
    }

    // return a copy to avoid reference lifetime problem later
    pub fn get_variable<'b>(
        &'b self,
        upper_context: Option<&'b VarContext<'src>>,
        name: Token<'src>,
    ) -> Option<&'b VarKind<'src>> {
        self.variables
            .get(&name)
            //.cloned() TODO ??
            .or_else(|| match upper_context {
                None => None,
                Some(gc) => gc.get_variable(None, name),
            })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::parser::*;

    // test utilities
    fn ident(string: &str) -> Token {
        pidentifier(pinput("", string)).unwrap().1
    }

    #[test]
    fn test_context() {
        let mut gc = VarContext::new();
        assert!(gc
            .new_enum_variable(None, ident("var1"), ident("enum1"), None)
            .is_ok());
        assert!(gc
            .new_enum_variable(None, ident("var2"), ident("enum1"), Some(ident("debian")))
            .is_ok());
        let mut c = VarContext::new();
        assert!(c
            .new_enum_variable(Some(&gc), ident("var3"), ident("enum2"), None)
            .is_ok());
        assert!(c
            .new_enum_variable(
                Some(&gc),
                ident("var4"),
                ident("enum1"),
                Some(ident("ubuntu"))
            )
            .is_ok());

        assert_eq!(
            c.get_variable(Some(&gc), ident("var3")),
            Some(&VarKind::Enum(ident("enum2"), None))
        );
        assert_eq!(
            c.get_variable(Some(&gc), ident("var2")),
            Some(&VarKind::Enum(ident("enum1"), Some(ident("debian"))))
        );
        assert_eq!(
            gc.get_variable(None, ident("var1")),
            Some(&VarKind::Enum(ident("enum1"), None))
        );
        assert_eq!(gc.get_variable(None, ident("var4")), None);
    }
}
