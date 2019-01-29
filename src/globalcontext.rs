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

// TODO out of order enum mapping definition

#[derive(Debug)]
pub struct GlobalContext<'a> {
    enumlist: EnumList<'a>,
    resources: HashMap<Token<'a>,Resources<'a>>,
    variables: VarContext<'a>,
}

#[derive(Debug)]
struct Resources<'a> {
    metadata: HashMap<Token<'a>,PValue<'a>>, 
    parameters: Vec<Parameter<'a>>, // TODO ?
    states: HashMap<Token<'a>,StateDef<'a>>,
}

#[derive(Debug)]
struct StateDef<'a> {
    metadata: HashMap<Token<'a>,PValue<'a>>, 
    parameters: Vec<Parameter<'a>>, //TODO ?
    statements: Vec<PStatement<'a>>, //TODO ?
}

#[derive(Debug)]
struct Parameter<'a> {
    pub name: Token<'a>,
    pub ptype: PType,
    pub default: Option<PValue<'a>>,
}
impl<'a> Parameter<'a> {
    fn new(p: PParameter<'a>) -> Parameter<'a> {
        let ptype = match p.ptype {
            Some(t) => t,
            None => {
                if let Some(val) = &p.default {
                    // guess from default
                    match val {
                        PValue::String(_) => PType::TString,
                        _ => panic!("Unknown value type"), // TODO remove
                    }
                } else {
                    // Nothing -> String
                    PType::TString
                }
            },
        };
        Parameter { name: p.name, ptype, default: p.default }
    }

    fn value_match(&self, param_ref: &PValue) -> Result<()> {
        match (&self.ptype, param_ref) {
            (PType::TString, PValue::String(_)) => Ok(()),
            (t,_v) => fail!(Token::new("x","y"), "Parameter is not of the type {:?}", t), // TODO we need a Token to position PValues and a display trait
        }
    }
}

// TODO type inference
// TODO check that parameter type match parameter default
// TODO put default parameter in calls

impl<'a> GlobalContext<'a> {
    pub fn new() -> GlobalContext<'static> { GlobalContext {
            enumlist:  EnumList::new(),
            resources: HashMap::new(),
            variables: VarContext::new_global(),
        }
    }

    pub fn add_pfile(&mut self, filename: &'a str, file: PFile<'a>) -> Result<()> {
        if file.header.version != 0 { panic!("Multiple format not supported yet"); }
        let mut current_metadata: HashMap<Token<'a>,PValue<'a>>= HashMap::new();
        for decl in file.code {
            match decl {
                PDeclaration::Comment(c) => {
                    // comment are concatenated and are considered metadata
                    if current_metadata.contains_key(&Token::new("comment",filename)) {
                        current_metadata.entry(Token::new("comment",filename)).and_modify(|e| { *e = match e {
                            PValue::String(st) => PValue::String(st.to_string()+c),
                            _ => panic!("Comment is not a string, this should not happen"),
                        }});
                    } else {
                        current_metadata.insert("comment".into(), PValue::String(c.to_string()));
                    }
                },
                PDeclaration::Metadata(m) => {
                    // metadata must not be called "comment"
                    if m.key == Token::from("comment") {
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
                        parameters: rd.parameters.into_iter().map(Parameter::new).collect(),
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
                            parameters: st.parameters.into_iter().map(Parameter::new).collect(),
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
                    // TODO warn if there is some ignored metadata
                    current_metadata = HashMap::new();
                },
                PDeclaration::Mapping(em) => {
                    self.enumlist.add_mapping(em)?;
                    // Discard metadata
                    // TODO warn if there is some ignored metadata
                    current_metadata = HashMap::new();
                },
            }
        }
        Ok(())
    }

    fn state_call_check(&self, statement: &PStatement) -> Result<()> {
        match statement {
            PStatement::StateCall(_out, _mode, res, name, params) => {
                match self.resources.get(&res.name) {
                    None => fail!(res.name, "Resource type {} does not exist", res.name),
                    Some(resource) => {
                        // Assume default parameter replacement and type inference if any has already be done
                        match_parameters(&resource.parameters, &res.parameters, res.name)?;
                        match resource.states.get(&name) {
                            None => fail!(name, "State {} does not exist for resource {}", name, res.name),
                            Some(state) => {
                                // Assume default parameter replacement and type inference if any has already be done
                                match_parameters(&state.parameters, &params, *name)?;
                            }
                        }
                    },
                }
            },
            PStatement::Case(cases) => {
                for (_cond, st) in cases.iter() {
                    self.state_call_check(st)?;
                }
            },
            _ => {},
        }
        Ok(())
    }

    fn enum_expression_check(&self, statement: &PStatement) -> Result<()> {
        match statement {
            PStatement::Case(cases) => {
                let exp_list = cases.iter()
                                    .map(|(cond,_)| self.enumlist.canonify_expression(&self.variables, cond))
                                    .collect::<Result<Vec<_>>>()?;
                self.enumlist.evaluate(&self.variables, &exp_list, Token::new("","")); // TODO no local context ?
                                                                                     // TODO local token
                                                                                     // TODO report error
            },
            _ => {},
        }
        Ok(())
    }

    pub fn analyze(&self) -> Result<()> {
        for (rn, resource) in self.resources.iter() {
            for (sn, state) in resource.states.iter() {
                for st in state.statements.iter() {
                    // check for resources and state existence
                    // check for matching parameter and type
                    self.state_call_check(st)?;
                    // check for enum expression validity and completeness
                    self.enum_expression_check(st)?;
                }
            }
        }
        // TODO check for string syntax and interpolation validity
        Ok(())
    }

    // TODO generate only one file
    pub fn generate_cfengine(&self) -> Result<()> { // TODO separate via trait ?
        let mut files: HashMap<&str,String> = HashMap::new();
        for (rn, res) in self.resources.iter() {
            for (sn, state) in res.states.iter() {
                let mut content = match files.get(sn.file()) {
                    Some(s) => s.to_string(),
                    None => String::new(),
                };
                let params = res.parameters.iter()
                                           .chain(state.parameters.iter())
                                           .map(|p| p.name.fragment())
                                           .collect::<Vec<&str>>()
                                           .join(",");
                content.push_str(&format!("bundle agent {}_{} ({})\n",rn.fragment(),sn.fragment(),params));
                content.push_str("{\n  methods:\n");
                for st in state.statements.iter() {
                    match st {
                        PStatement::StateCall(_out,_mode,res,call,params) => {
                            let param_str = res.parameters.iter()
                                               .chain(params.iter())
                                               .map(parameter_to_cfengine)
                                               .collect::<Vec<String>>()
                                               .join(",");
                            content.push_str(&format!("    \"method_call\" usebundle => {}_{}({});\n",res.name.fragment(),call.fragment(),param_str));
                        },
                        // TODO case
                        _ => {},
                    }
                }
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

fn match_parameters(pdef: &Vec<Parameter>, pref: &Vec<PValue>, identifier: Token) -> Result<()> {
    if pdef.len() != pref.len() {
        fail!(identifier, "Error in call to {}, parameter count do not match, expecting {}, you gave {}", identifier, pdef.len(), pref.len());
    }
    pdef.iter()
        .zip(pref.iter())
        .map(|(p,v)| p.value_match(v))
        .collect()
}

fn parameter_to_cfengine(param: &PValue) -> String {
    match param {
        PValue::String(s) => format!("\"{}\"",s),
        _ => "XXX".to_string(), // TODO remove _
    }
}
