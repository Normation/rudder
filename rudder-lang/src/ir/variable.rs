use super::value::*;
use super::context::*;
use super::enums::EnumList;
use crate::{error::*, parser::*};
use super::resource::create_metadata;

use std::collections::HashMap;
use toml::map::Map as TomlMap;
use toml::Value as TomlValue;

#[derive(Debug)]
pub struct VariableDef<'src> {
    metadata: TomlMap<String, TomlValue>,
    pub name: Token<'src>,
    value: ComplexValue<'src>,
}

impl<'src> VariableDef<'src> {
    pub fn from_pvariable_definition(var: PVariableDef<'src>, context: &mut VarContext<'src>, enum_list: &EnumList<'src>) -> Result<Self> {
        let PVariableDef { metadata, name, value } = var;
        let value = ComplexValue::from_pcomplex_value(enum_list, &context, value)?;
        value.context_check(context)?;
        context.add_variable_declaration(name, Type::from_complex_value(&value)?)?;
        let (error,metadata) = create_metadata(metadata);
        if !error.is_empty() {
            return Err(Error::from_vec(error));
        }
        Ok(VariableDef { metadata, name, value })
    }
}

#[derive(Debug)]
pub struct VariableDefList<'src> {
    definitions: HashMap<Token<'src>, VariableDef<'src>>,
}

impl<'src> VariableDefList<'src> {
    pub fn new() -> Self {
        VariableDefList { definitions: HashMap::new() }
    }

    pub fn append(&mut self, var: PVariableDef<'src>, context: &mut VarContext<'src>, enum_list: &EnumList<'src>,) -> Result<()> {
        let def = VariableDef::from_pvariable_definition(var, context, enum_list)?;
        // definition should exist in the hashmap since the above contains a context check
        self.definitions.insert(def.name.clone(), def);
        Ok(())
    }

    pub fn extend(&mut self, var: PVariableExt<'src>, context: &mut VarContext<'src>, enum_list: &EnumList<'src>,) -> Result<()> {
        let PVariableExt { name, value } = var;
        match self.definitions.get_mut(&name) {
            None => fail!(name, "Variable {} has never been defined", name),
            Some(v) => v.value.extend(enum_list, context, value),
        }
    }

    // TODO
    //pub fn iterate(&self) -> TODO
}