use super::context::VarContext;
use super::enums::EnumList;
use super::Parameter;
use crate::error::*;
use crate::parser::*;
use std::collections::HashMap;

#[derive(Debug)]
pub struct PreAST<'a> {
    pub enum_list: EnumList<'a>,
    pub enum_mapping: Vec<PEnumMapping<'a>>, // TODO Medatata
    pub pre_resources: HashMap<Token<'a>, PreResources<'a>>,
    pub variables: VarContext<'a>,
}

#[derive(Debug)]
pub struct PreResources<'a> {
    pub metadata: HashMap<Token<'a>, PValue<'a>>,
    pub parameters: Vec<Parameter<'a>>,
    pub pre_states: Vec<(HashMap<Token<'a>, PValue<'a>>, PStateDef<'a>)>,
}

impl<'a> PreAST<'a> {
    pub fn new() -> PreAST<'static> {
        PreAST {
            enum_list: EnumList::new(),
            enum_mapping: Vec::new(),
            pre_resources: HashMap::new(),
            variables: VarContext::new(),
        }
    }

    /// Add a file parsed with the top level parser.
    /// Call this once for each file before calling finalize.
    pub fn add_parsed_file(&mut self, filename: &'a str, file: PFile<'a>) -> Result<()> {
        if file.header.version != 0 {
            panic!("Multiple format not supported yet");
        }
        let mut current_metadata: HashMap<Token<'a>, PValue<'a>> = HashMap::new();
        fix_results(file.code.into_iter().map(|decl| {
            match decl {
                PDeclaration::Comment(c) => {
                    // comment are concatenated and are considered metadata
                    if current_metadata.contains_key(&Token::new("comment", filename)) {
                        current_metadata
                            .entry(Token::new("comment", filename))
                            .and_modify(|e| {
                                *e = match e {
                                    PValue::String(st) => PValue::String(st.to_string() + c),
                                    _ => panic!("Comment is not a string, this should not happen"),
                                }
                            });
                    } else {
                        current_metadata.insert("comment".into(), PValue::String(c.to_string()));
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
                    let resource = PreResources {
                        metadata: current_metadata.drain().collect(), // Move the content without moving the structure
                        parameters: rd.parameters.into_iter().map(Parameter::new).collect(),
                        pre_states: Vec::new(),
                    };
                    self.pre_resources.insert(rd.name, resource);
                    // Reset metadata
                    current_metadata = HashMap::new();
                }
                PDeclaration::State(st) => {
                    if let Some(rd) = self.pre_resources.get_mut(&st.resource_name) {
                        rd.pre_states.push((current_metadata.drain().collect(), st));
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
