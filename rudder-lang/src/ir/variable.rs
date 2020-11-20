use super::context::*;
use super::enums::EnumList;
use super::resource::create_metadata;
use super::value::*;
use crate::{error::*, ir::resource::StateDeclaration, parser::*};

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
    pub fn from_pvariable_definition(
        var: PVariableDef<'src>,
        context: &mut VarContext<'src>,
        enum_list: &EnumList<'src>,
    ) -> Result<Self> {
        let PVariableDef {
            metadata,
            name,
            value,
        } = var;
        let value = ComplexValue::from_pcomplex_value(enum_list, &context, value)?;
        value.context_check(context)?;
        context.add_variable_declaration(name, Type::from_complex_value(&value)?)?;
        let (error, metadata) = create_metadata(metadata);
        if !error.is_empty() {
            return Err(Error::from_vec(error));
        }
        Ok(Self {
            metadata,
            name,
            value,
        })
    }
}

#[derive(Debug)]
pub struct CondVariableDef<'src> {
    pub metadata: TomlMap<String, TomlValue>,
    pub name: Token<'src>,
    pub resource: Token<'src>,
    // there is only one actually but easier to deal with it as a vec
    pub resource_params: Vec<Value<'src>>,
    pub state: Token<'src>,
    pub state_params: Vec<Value<'src>>,
}

impl<'src> CondVariableDef<'src> {
    pub fn from_pcond_variable_definition(
        var: PCondVariableDef<'src>,
        context: &mut VarContext<'src>,
        enum_list: &EnumList<'src>,
    ) -> Result<Self> {
        let PCondVariableDef {
            metadata,
            name,
            resource,
            state,
            state_params,
        } = var;
        let (error, metadata) = create_metadata(metadata);
        context.add_variable_declaration(name, Type::Enum(Token::new("corelib.rl", "boolean")))?;
        if !error.is_empty() {
            return Err(Error::from_vec(error));
        }
        let resource_params = vec![Value::String(StringObject::from_pstring(
            name,
            format!(
                "{}_${{report_data.canonified_directive_id}}",
                name.fragment()
            ),
        )?)];
        let state_params = map_vec_results(state_params.into_iter(), |v| {
            Value::from_pvalue(enum_list, context, v)
        })?;
        Ok(Self {
            metadata,
            name,
            resource,
            resource_params,
            state,
            state_params,
        })
    }

    pub fn to_method(&self) -> StateDeclaration {
        StateDeclaration {
            // field is not generated so value does not matter
            source: self.resource,
            metadata: self.metadata.clone(),
            mode: PCallMode::Condition,
            resource: self.resource,
            resource_params: self.resource_params.clone(),
            state: self.state,
            state_params: self.state_params.clone(),
            outcome: None,
        }
    }
}

#[derive(Debug)]
pub struct VariableDefList<'src> {
    definitions: HashMap<Token<'src>, VariableDef<'src>>,
}

impl<'src> VariableDefList<'src> {
    pub fn new() -> Self {
        Self {
            definitions: HashMap::new(),
        }
    }

    pub fn append(
        &mut self,
        var: PVariableDef<'src>,
        context: &mut VarContext<'src>,
        enum_list: &EnumList<'src>,
    ) -> Result<()> {
        let def = VariableDef::from_pvariable_definition(var, context, enum_list)?;
        // definition should exist in the hashmap since the above contains a context check
        self.definitions.insert(def.name.clone(), def);
        Ok(())
    }

    pub fn extend(
        &mut self,
        var: PVariableExt<'src>,
        context: &mut VarContext<'src>,
        enum_list: &EnumList<'src>,
    ) -> Result<()> {
        let PVariableExt { name, value } = var;
        match self.definitions.get_mut(&name) {
            None => fail!(name, "Variable {} has never been defined", name),
            Some(v) => v.value.extend(enum_list, context, value),
        }
    }

    // TODO
    //pub fn iterate(&self) -> TODO
}
