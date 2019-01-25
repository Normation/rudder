mod enums;
mod context;
//mod strings;

use crate::error::*;
use crate::parser::*;
use self::enums::EnumList;
use self::context::VarContext;
use std::collections::HashMap;



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

impl<'a> GlobalContext<'a> {
    pub fn new() -> GlobalContext<'static> {
        GlobalContext {
            enumlist:  EnumList::new(),
            resources: HashMap::new(),
            variables: VarContext::new_global(),
        }
    }

    pub fn add_pfile(&mut self, file: PCode<'a>) -> Result<()> {
        if file.header.version != 0 { panic!("Multiple format not supported yet"); }
        let mut current_metadata = HashMap::new();
        for decl in file.code {
            match decl {
                PDeclaration::Comment(c) => {
                    // comment are concatenated and are considered metadata
                },
                PDeclaration::Metadata(m) => {
                    // metadata must not be called "comment"
                },
                PDeclaration::Resource(rd) => {
                    if self.resources.contains_key(&rd.name) {
                        let key = self.resources.entry(rd.name).key();
                        fail!(rd.name, "Resource {} has already been defined in {}", rd.name, key);
                    }
                    //self.resources.insert(rd.name,(current_metadata,rd));
                    current_metadata = HashMap::new();
                },
                PDeclaration::State(st) => {
                },
                PDeclaration::Enum(e) => self.enumlist.add_enum(e)?,
                PDeclaration::Mapping(em) => self.enumlist.add_mapping(em)?,
            }
        }
        Ok(())
    }
}

