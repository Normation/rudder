use crate::error::*;
use crate::parser::Token;
use std::collections::HashMap;

// variable kind
#[derive(Debug, PartialEq)]
pub enum VarKind<'a> {
    //       Resource type (File, ...)
    Resource(String),
    //   Enum          value
    Enum(Token<'a>, Option<Token<'a>>),
    Generic(VarType<'a>),
}

// classic variable type
// including value for a constant/known a compile time
#[derive(Debug, PartialEq)]
pub enum VarType<'a> {
    String(Option<String>),
    // to make sure we have a reference in this struct because there will be one some day
    #[allow(dead_code)]
    XX(Token<'a>),
    //    List(Option<String>), // TODO string -> real type
    //    Dict(Option<String>), // TODO string -> real type
}

#[derive(Debug)]
pub struct VarContext<'a> {
    variables: HashMap<Token<'a>, VarKind<'a>>,
    global_context: Option<&'a VarContext<'a>>,
}

impl<'a> VarContext<'a> {
    pub fn new_global() -> VarContext<'static> {
        VarContext {
            variables: HashMap::new(),
            global_context: None,
        }
    }

    pub fn new_local(global: &'a VarContext<'a>) -> VarContext<'a> {
        VarContext {
            variables: HashMap::new(),
            global_context: Some(global),
        }
    }

    pub fn new_enum_variable(
        &mut self,
        name: Token<'a>,
        enum1: Token<'a>,
        value: Option<Token<'a>>,
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
        match self.global_context {
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
        self.variables.insert(name, VarKind::Enum(enum1, value));
        Ok(())
    }

    pub fn get_variable(&self, name: Token<'a>) -> Option<&VarKind> {
        self.variables
            .get(&name)
            .or_else(|| match self.global_context {
                None => None,
                Some(gc) => gc.get_variable(name),
            })
    }
}

//    fn new_enum_variable(&mut self, name: Token<'a>, enum1: Token<'a>, value: Option<Token<'a>>) -> Result<()>;
//    //fn pub fn new_genric_variable(&mut self, name: Token<'a>, value: Option<PValue>) -> Result;
//    //fn new_resource_variable(name: Token, type: Option<Token>, value: Option<Token>) -> Result
//    fn get_variable(&self, name: Token) -> Option<VarKind>;

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
        let mut gc = VarContext::new_global();
        assert!(gc
            .new_enum_variable(ident("var1"), ident("enum1"), None)
            .is_ok());
        assert!(gc
            .new_enum_variable(ident("var2"), ident("enum1"), Some(ident("debian")))
            .is_ok());
        let mut c = VarContext::new_local(&gc);
        assert!(c
            .new_enum_variable(ident("var3"), ident("enum2"), None)
            .is_ok());
        assert!(c
            .new_enum_variable(ident("var4"), ident("enum1"), Some(ident("ubuntu")))
            .is_ok());

        assert_eq!(
            c.get_variable(ident("var3")),
            Some(&VarKind::Enum(ident("enum2"), None))
        );
        assert_eq!(
            c.get_variable(ident("var2")),
            Some(&VarKind::Enum(ident("enum1"), Some(ident("debian"))))
        );
        assert_eq!(
            gc.get_variable(ident("var1")),
            Some(&VarKind::Enum(ident("enum1"), None))
        );
        assert_eq!(gc.get_variable(ident("var4")), None);
    }
}
