use super::context::VarContext;
use super::enums::EnumList;
use super::{Parameter, Value};
use crate::error::*;
use crate::parser::*;
use std::collections::HashMap;

/// PreAST is a structure that looks like ans AST but is not an AST
/// We need all global data to create the final AST
/// So we store them in a PreAST and create the final AST once we have everything
#[derive(Debug)]
pub struct PreAST<'src> {
    pub enum_list: EnumList<'src>,
    pub enum_mapping: Vec<PEnumMapping<'src>>, // TODO Medatata
    pub pre_resources: HashMap<Token<'src>, PreResources<'src>>,
    pub variables: VarContext<'src>,
}

/// PreResource is the Resource structure for PreAST
#[derive(Debug)]
pub struct PreResources<'src> {
    pub metadata: HashMap<Token<'src>, Value<'src>>,
    pub parameters: Vec<Parameter<'src>>,
    //                   metadata                       state
    pub pre_states: Vec<(HashMap<Token<'src>, Value<'src>>, PStateDef<'src>)>,
}

impl<'src> PreAST<'src> {
    pub fn new() -> PreAST<'static> {
        PreAST {
            enum_list: EnumList::new(),
            enum_mapping: Vec::new(),
            pre_resources: HashMap::new(),
            variables: VarContext::new(),
        }
    }

    /// Add a file parsed with the top level parser.
    /// Call this once for each file before creating AST.
    pub fn add_parsed_file(&mut self, filename: &'src str, file: PFile<'src>) -> Result<()> {
        if file.header.version != 0 {
            panic!("Multiple format not supported yet");
        }
        let mut current_metadata: HashMap<Token<'src>, PValue<'src>> = HashMap::new();
        fix_results(file.code.into_iter().map(|decl| {
            match decl {
                PDeclaration::Comment(c) => {
                    // comment are concatenated and are considered metadata
                    if current_metadata.contains_key(&Token::new("comment", filename)) {
                        current_metadata
                            .entry(Token::new("comment", filename))
                            .and_modify(|e| {
                                *e = match e {
                                    PValue::String(tag, st) => {
                                        PValue::String(*tag, st.to_string() + c)
                                    }
                                }
                            });
                    } else {
                        current_metadata.insert("comment".into(), PValue::String(c, c.to_string()));
                    }
                }
                PDeclaration::Metadata(m) => {
                    // metadata must not be called "comment"
                    if m.key == Token::from("comment") {
                        fail!(m.key, "Metadata name '{}' is forbidden", m.key);
                    }
                    if current_metadata.contains_key(&m.key) {
                        fail!(
                            m.key,
                            "Metadata name '{}' is already defined at {}",
                            m.key,
                            current_metadata.entry(m.key).key()
                        );
                    }
                    current_metadata.insert(m.key, m.value);
                }
                PDeclaration::Resource(rd) => {
                    if self.pre_resources.contains_key(&rd.name) {
                        fail!(
                            rd.name,
                            "Resource {} has already been defined in {}",
                            rd.name,
                            self.pre_resources.entry(rd.name).key()
                        );
                    }
                    let metadata = fix_map_results(
                        current_metadata
                            .drain() // Move the content without moving the structure
                            .map(|(k, v)| Ok((k, Value::from_pvalue(v)?))),
                    )?;
                    let resource = PreResources {
                        metadata,
                        parameters: fix_vec_results(
                            rd.parameters.into_iter().map(Parameter::from_pparameter),
                        )?,
                        pre_states: Vec::new(),
                    };
                    self.pre_resources.insert(rd.name, resource);
                    // Reset metadata
                    current_metadata = HashMap::new();
                }
                PDeclaration::State(st) => {
                    if let Some(rd) = self.pre_resources.get_mut(&st.resource_name) {
                        let metadata = fix_map_results(
                            current_metadata
                                .drain() // Move the content without moving the structure
                                .map(|(k, v)| Ok((k, Value::from_pvalue(v)?))),
                        )?;
                        rd.pre_states.push((metadata, st));
                        // Reset metadata
                        current_metadata = HashMap::new();
                    } else {
                        fail!(
                            st.resource_name,
                            "Resource {} has not been defined for {}",
                            st.resource_name,
                            st.name
                        );
                    }
                }
                PDeclaration::Enum(e) => {
                    if e.global {
                        self.variables
                            .new_enum_variable(None, e.name, e.name, None)?;
                    }
                    self.enum_list.add_enum(e)?;
                    // Discard metadata
                    // TODO warn if there is some ignored metadata
                    current_metadata = HashMap::new();
                }
                PDeclaration::Mapping(em) => {
                    self.enum_mapping.push(em);
                    // Discard metadata
                    // TODO warn if there is some ignored metadata
                    current_metadata = HashMap::new();
                }
            };
            Ok(())
        }))
    }
}
