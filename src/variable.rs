use crate::error::*;
use crate::parser::{PToken};
use std::collections::HashMap;
use std::collections::HashSet;

// variable kind
#[derive(Debug)]
pub enum VarKind<'a> {
    //       Resource type (File, ...)
    Resource(String),
    //   Enum          value
    Enum(PToken<'a>,Option<PToken<'a>>),
    Generic(VarType<'a>),
}

// classic variable type
// including value for a constant/known a compile time
#[derive(Debug)]
pub enum VarType<'a> {
    String(Option<String>),
// to make sure we have a reference in this struct because there will be one some day
    #[allow(dead_code)]
    XX(PToken<'a>),
//    List(Option<String>), // TODO string -> real type
//    Dict(Option<String>), // TODO string -> real type
}

#[derive(Debug)]
pub struct VarContext<'a> {
    //                 name       kind/type
    variables: HashMap<PToken<'a>,VarKind<'a>>,
}


impl<'a> VarContext<'a> {
    pub fn new() -> VarContext<'static> {
        VarContext { variables: HashMap::new() }
    }

    pub fn new_enum_variable(&mut self, name: PToken<'a>, enum1: PToken<'a>, value: Option<PToken<'a>>) -> Result<()> {
        if self.variables.contains_key(&name) {
            fail!(name, "Variable {} already defined at {}", name, self.variables.entry(name).key());
        }
        // Add to context
        self.variables.insert(name,VarKind::Enum(enum1,value));
        Ok(())
    }

    pub fn get_variable(&self, name: PToken) -> Option<VarKind> {
        self.variables.get(name)
    }
}

// fn new_generic_variable(name: PToken, value: Option<PValue>) -> Result
// fn new_enum_variable(name: PToken, type: Option<PToken>, value: Option<PToken>) -> Result
// fn new_resource_variable(name: PToken, type: Option<PToken>, value: Option<PToken>) -> Result


