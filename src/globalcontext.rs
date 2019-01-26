mod enums;
mod context;
//mod strings;

use crate::error::*;
use crate::parser::*;
use self::enums::EnumList;
use self::context::VarContext;
use std::collections::HashMap;
use std::fs::File;
use std::io::Write;

pub struct GlobalContext<'a> {
    enumlist: EnumList<'a>,
    resources: HashMap<PToken<'a>,Resources<'a>>,
    variables: VarContext<'a>,
}

struct Resources<'a> {
    metadata: HashMap<PToken<'a>,PValue<'a>>, 
    parameters: Vec<PParameter<'a>>, // TODO ?
    states: HashMap<PToken<'a>,StateDef<'a>>,
}

struct StateDef<'a> {
    metadata: HashMap<PToken<'a>,PValue<'a>>, 
    parameters: Vec<PParameter<'a>>, //TODO ?
    statements: Vec<PStatement<'a>>, //TODO ?
}

struct Parameter<'a> {
    pub name: PToken<'a>,
    pub ptype: PType,
    pub default: Option<PValue<'a>>,
}

impl<'a> GlobalContext<'a> {
    pub fn new() -> GlobalContext<'static> { GlobalContext {
            enumlist:  EnumList::new(),
            resources: HashMap::new(),
            variables: VarContext::new_global(),
        }
    }

    pub fn add_pfile(&mut self, filename: &'a str, file: PFile<'a>) -> Result<()> {
        if file.header.version != 0 { panic!("Multiple format not supported yet"); }
        let mut current_metadata: HashMap<PToken<'a>,PValue<'a>>= HashMap::new();
        for decl in file.code {
            match decl {
                PDeclaration::Comment(c) => {
                    // comment are concatenated and are considered metadata
                    if current_metadata.contains_key(&PToken::new("comment",filename)) {
                        current_metadata.entry(PToken::new("comment",filename)).and_modify(|e| { *e = match e {
                            PValue::String(s) => PValue::String(c.iter().fold(s.to_string(), { |mut i,st| { i.push_str(st); i } })),
                            _ => panic!("Comment is not a string, this should not happen"),
                        }});
                    } else {
                        current_metadata.insert("comment".into(), PValue::String(c.iter().fold(String::from(""), { |mut i,s| { i.push_str(s); i } })));
                    }
                },
                PDeclaration::Metadata(m) => {
                    // metadata must not be called "comment"
                    if m.key == PToken::from("comment") {
                        fail!(m.key, "Metadata name '{}' is forbidden", m.key);
                    }
                    if current_metadata.contains_key(&m.key) {
                        fail!(m.key, "Metadata name '{}' is already defined at {}", m.key, current_metadata.entry(m.key).key());
                    }
                    current_metadata.insert(m.key, m.value);
                },
                PDeclaration::Resource(rd) => {
                    if self.resources.contains_key(&rd.name) {
                        fail!(rd.name, "Resource {} has already been defined in {}", rd.name, self.resources.entry(rd.name).key());
                    }
                    let resource = Resources {
                        metadata: current_metadata,
                        parameters: rd.parameters,
                        states: HashMap::new(),
                    };
                    self.resources.insert(rd.name, resource);
                    // Reset metadata
                    current_metadata = HashMap::new();
                },
                PDeclaration::State(st) => {
                    if let Some(rd) = self.resources.get_mut(&st.resource_name) {
                        if rd.states.contains_key(&st.name) {
                            fail!(st.name, "State {} for resource {} has already been defined in {}", st.name, st.resource_name, rd.states.entry(st.name).key());
                        }
                        let state = StateDef {
                            metadata: current_metadata,
                            parameters: st.parameters,
                            statements: st.statements,
                        };
                        rd.states.insert(st.name, state);
                        // Reset metadata
                        current_metadata = HashMap::new();
                    } else {
                        fail!(st.resource_name, "Resource {} has not been defined for {}", st.resource_name, st.name);
                    }
                },
                PDeclaration::Enum(e) => {
                    self.enumlist.add_enum(e)?;
                    // Discard metadata
                    // TODO warn if there is nome ignored metadata
                    current_metadata = HashMap::new();
                },
                PDeclaration::Mapping(em) => {
                    self.enumlist.add_mapping(em)?;
                    // Discard metadata
                    // TODO warn if there is nome ignored metadata
                    current_metadata = HashMap::new();
                },
            }
        }
        Ok(())
    }

    pub fn generate_cfengine(&self) -> Result<()> { // TODO separate via trait ?
        let mut files: HashMap<&str,String> = HashMap::new();
        for (rn, res) in self.resources.iter() {
            for (sn, state) in res.states.iter() {
                let mut content = match files.get(sn.file()) {
                    Some(s) => s.to_string(),
                    None => String::new(),
                };
                content.push_str(&format!("bundle agent {}_{}\n",rn,sn)); // TODO parameters
                content.push_str("{\n  methods:\n");

                content.push_str("}\n");
                files.insert(sn.file(), content.to_string()); // TODO there is something smelly with this to_string
            }
        }
        for (name, content) in files.iter() {
            let mut file = File::create(format!("{}.cf", name )).unwrap();
            file.write_all(content.as_bytes()).unwrap();
        }
        Ok(())
    }
}

