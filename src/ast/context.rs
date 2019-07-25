use std::collections::hash_map;
use std::collections::HashMap;
use crate::error::*;
use crate::parser::{PType, Token};
//use super::enums::EnumList;
//use super::value::Value;

/// Variable are categorized by kinds.
/// This allows segregating variables that can only be used in some places.
#[derive(Debug, PartialEq, Clone)]
pub enum VarKind<'src> {
    //   Enum          item value
    Enum(Token<'src>, Option<Token<'src>>),
    //     type
    Generic(PType),
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

    /// Create a variable in the context.
    /// Used by dedicated variable creation method for each kind.
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

    /// Create a variable of kind enum in this context.
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

    /// Create a generic variable in tis context.
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

    /*/// Create a constant in this context.
        pub fn new_constant(
            &mut self,
            upper_context: Option<&VarContext<'src>>,
            name: Token<'src>,
            ptype: PType,
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
            .new_enum_variable(None, pidentifier_t("var2"), pidentifier_t("enum1"), Some(pidentifier_t("debian")))
            .is_ok());
        let mut c = VarContext::new();
        assert!(c
            .new_enum_variable(Some(&context), pidentifier_t("var3"), pidentifier_t("enum2"), None)
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
