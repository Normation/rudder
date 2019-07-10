use crate::error::*;
use crate::parser::*;
use std::collections::HashMap;

/// ResourceDeclaration is a temporary Resource structure for CodeIndex.
/// If will be transformed into a ResourceDef in AST.
#[derive(Debug)]
pub struct ResourceDeclaration<'src> {
    pub metadata: HashMap<Token<'src>, PValue<'src>>,
    pub parameters: Vec<PParameter<'src>>,
    //                   name,          metadata                           state
    pub states: HashMap<Token<'src>, (HashMap<Token<'src>, PValue<'src>>, PStateDef<'src>)>,
}

/// CodeIndex is a global structure that stores all parsed data.
/// We need a global view and some indexes before creating the AST.
#[derive(Debug)]
pub struct CodeIndex<'src> {
    pub enums: Vec<PEnum<'src>>,
    pub enum_mappings: Vec<PEnumMapping<'src>>,
    pub resources: HashMap<Token<'src>, ResourceDeclaration<'src>>,
    pub variable_declarations: Vec<(Token<'src>, PValue<'src>)>,
    pub parameter_defaults: HashMap<(Token<'src>, Option<Token<'src>>), Vec<Option<PValue<'src>>>>,
                      // child    parent
    pub parents: Vec<(Token<'src>, Token<'src>)>,
    pub aliases: Vec<PAliasDef<'src>>,
}

impl<'src> CodeIndex<'src> {
    pub fn new() -> CodeIndex<'static> {
        CodeIndex {
            enums: Vec::new(),
            enum_mappings: Vec::new(),
            resources: HashMap::new(),
            variable_declarations: Vec::new(),
            parameter_defaults: HashMap::new(),
            parents: Vec::new(),
            aliases: Vec::new(),
        }
    }

    /// Add a file parsed with the top level parser.
    /// Call this once for each file before creating the AST.
    /// Uniqueness checks are done here.
    pub fn add_parsed_file(&mut self, filename: &'src str, file: PFile<'src>) -> Result<()> {
        if file.header.version != 0 {
            panic!("Multiple format not supported yet");
        }
        let mut current_metadata: HashMap<Token<'src>, PValue<'src>> = HashMap::new();
        // iterate over all parsed declarations
        fix_results(file.code.into_iter().map(|declaration| {
            match declaration {
                // comments are concatenated and are considered metadata.
                PDeclaration::Comment(c) => {
                    if current_metadata.contains_key(&Token::new("comment", filename)) {
                        current_metadata
                            .entry(Token::new("comment", filename))
                            .and_modify(|e| {
                                *e = match e {
                                    PValue::String(tag, st) => {
                                        PValue::String(*tag, format!("{}\n{}", st.to_string(), c.to_string()))
                                    }
                                    _ => panic!("Non string comment has been created"),
                                }
                            });
                    } else {
                        current_metadata.insert("comment".into(), PValue::String(c.position(), c.to_string()));
                    }
                }

                // Metadata are stored into a HashMap for later use.
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

                // Resource declaration are stored in a temporary format (ResourceDeclaration) with their metadata.
                // Uniqueness is checked.
                // Parameter defaults are stored separately because they are needed globally during AST creation.
                PDeclaration::Resource(rd) => {
                    let PResourceDef {
                        name,
                        parameters,
                        parameter_defaults,
                        parent,
                    } = rd;
                    if self.resources.contains_key(&name) {
                        fail!(
                            name,
                            "Resource {} has already been defined in {}",
                            name,
                            self.resources.entry(name).key()
                        );
                    }
                    let metadata = current_metadata
                        .drain() // Move the content without moving the structure
                        .collect();
                    let resource = ResourceDeclaration {
                        metadata,
                        parameters,
                        states: HashMap::new(),
                    };
                    // store resource declaration
                    self.resources.insert(name, resource);
                    // parents are stored in a separate structure
                    if let Some(parent_name) = parent {
                        self.parents.push((name, parent_name));
                    }
                    // default values are stored in a separate structure
                    self.parameter_defaults
                        .insert((name, None), parameter_defaults);
                    // Reset metadata
                    current_metadata = HashMap::new();
                }

                // State declaration are stored within resource declarations.
                // State uniqueness is checked here.
                // Parameter defaults are stored separately because they are needed globally during AST creation.
                PDeclaration::State(st) => {
                    let PStateDef {
                        name,
                        resource_name,
                        parameters,
                        parameter_defaults,
                        statements,
                    } = st;
                    if !self.resources.contains_key(&resource_name) {
                        fail!(
                            name,
                            "Resource {} has not been defined for {}",
                            resource_name,
                            name
                        );
                    }
                    let rd = self.resources.get_mut(&resource_name).unwrap();
                    if rd.states.contains_key(&name) {
                        fail!(
                            name,
                            "Resource {} state {} has already been defined in {}",
                            resource_name,
                            name,
                            rd.states.entry(name).key()
                        );
                    }
                    let metadata = current_metadata
                        .drain() // Move the content without moving the structure
                        .collect();
                    let state = PStateDef {
                        name,
                        resource_name,
                        parameters,
                        parameter_defaults: Vec::new(),
                        statements,
                    };
                    // store state declaration
                    rd.states.insert(name, (metadata, state));
                    // default values are stored in a separate structure
                    self.parameter_defaults
                        .insert((resource_name, Some(name)), parameter_defaults);
                    // reset metadata
                    current_metadata = HashMap::new();
                }

                // Enums are stored in a vec
                PDeclaration::Enum(e) => {
                    // Metadata not supported on enums
                    if !current_metadata.is_empty() {
                        fail!(
                            e.name,
                            "Metadata and documentation comment not supported on enums yet (in {})",
                            e.name
                        )
                    }
                    self.enums.push(e);
                }

                // Enum mappings are stored in a vec
                PDeclaration::Mapping(em) => {
                    // Metadata not supported on enums
                    if !current_metadata.is_empty() {
                        fail!(
                            &em.to,
                            "Metadata and documentation comment not supported on enums yet (in {})",
                            &em.to
                        )
                    }
                    self.enum_mappings.push(em);
                }

                // Global variables are put in a global context for typing
                // and stored as a global declaration for code generation.
                PDeclaration::GlobalVar(variable, value) => {
                    self.variable_declarations.push((variable, value));
                }

                // Aliases are sored for later processing
                PDeclaration::Alias(alias) => {
                    self.aliases.push(alias);
                }
            };
            Ok(())
        }))
    }
}

// TESTS
//

#[cfg(test)]
mod tests {
    use super::*;

    fn parse_string(input: &str) -> Result<()> {
        let mut code_index = CodeIndex::new();
        let content = parse_file("test_string", input).unwrap();
        code_index.add_parsed_file("test_string", content)
    }

    #[test]
    fn test_metadata() {
        assert!(parse_string(
            "@format=0
@test=\"ok\"
        "
        )
        .is_ok());
        assert!(parse_string(
            "@format=0
@test=\"ok\"
@test=\"ko\"
        "
        )
        .is_err());
        assert!(parse_string(
            "@format=0
@comment=\"ok\"
## ko
        "
        )
        .is_err());
    }

    #[test]
    fn test_enum() {
        assert!(parse_string(
            "@format=0
enum abc { a, b, c }
        "
        )
        .is_ok());
        assert!(parse_string(
            "@format=0
## enum comment
enum abc { a, b, c }
        "
        )
        .is_err());
    }

    #[test]
    fn test_resource() {
        assert!(parse_string(
            "@format=0
## resource comment
resource File(name)
        "
        )
        .is_ok());
        assert!(parse_string(
            "@format=0
## resource comment
resource File(name)
resource File(name2)
        "
        )
        .is_err());
        assert!(parse_string(
            "@format=0
resource File(name)
File state content() { }
        "
        )
        .is_ok());
        assert!(parse_string(
            "@format=0
resource File(name)
File2 state content() { }
        "
        )
        .is_err());
        assert!(parse_string(
            "@format=0
resource File(name)
File state content() { }
File state content() { }
        "
        )
        .is_err());
    }

    #[test]
    fn test_defaults() {
        assert!(parse_string(
            "@format=0
resource File(name=\"default\")
        "
        )
        .is_ok());
        assert!(parse_string(
            "@format=0
resource File(name=\"default\",path)
        "
        )
        .is_ok()); // no default order check here
        assert!(parse_string(
            "@format=0
resource File(name=\"defaul${\")
        "
        )
        .is_ok()); // default parsing not done here
    }
}
