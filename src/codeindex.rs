use crate::error::*;
use crate::parser::*;
use std::collections::HashMap;

/// CodeIndex is a global structure that stores all parsed data.
/// We need a global reference with direct acces to structures
/// and some indexes/hashmaps before creating the AST.
/// 
/// It is used to insert parsed files one by one.
#[derive(Debug)]
pub struct CodeIndex<'src> {
    pub enums: Vec<PEnum<'src>>,
    pub enum_mappings: Vec<PEnumMapping<'src>>,
    pub resources: HashMap<Token<'src>, TmpResourceDef<'src>>,
    pub variable_declarations: Vec<(Token<'src>, PValue<'src>)>,
    pub parameter_defaults: HashMap<(Token<'src>, Option<Token<'src>>), Vec<Option<PValue<'src>>>>,
                      // child    parent
    pub parents: Vec<(Token<'src>, Token<'src>)>,
    pub aliases: Vec<PAliasDef<'src>>,
}

/// TmpResourceDef is a temporary Resource structure for CodeIndex.
/// It will be transformed into a ResourceDef in final AST.
#[derive(Debug)]
pub struct TmpResourceDef<'src> {
    pub metadata: Vec<PMetadata<'src>>,
    pub parameters: Vec<PParameter<'src>>,
    pub states: HashMap<Token<'src>, PStateDef<'src>>,
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
    /// Most uniqueness checks are done here.
    pub fn add_parsed_file(&mut self, file: PFile<'src>) -> Result<()> {
        if file.header.version != 0 {
            panic!("Multiple format not supported yet");
        }
        // iterate over all parsed declarations
        map_results(file.code.into_iter(), |declaration| {
            match declaration {

                // Resource declaration are stored in a temporary format (TmpResourceDef) with their metadata.
                // Uniqueness is checked.
                // Parameter defaults are stored separately because they are needed globally during AST creation.
                PDeclaration::Resource(rd) => {
                    let PResourceDef {
                        metadata,
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
                    let resource = TmpResourceDef {
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
                }

                // State declaration are stored within resource declarations.
                // State uniqueness is checked here.
                // Parameter defaults are stored separately because they are needed globally during AST creation.
                PDeclaration::State(st) => {
                    let PStateDef {
                        metadata,
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
                    let state = PStateDef {
                        metadata,
                        name,
                        resource_name,
                        parameters,
                        parameter_defaults: Vec::new(),
                        statements,
                    };
                    // store state declaration
                    rd.states.insert(name, state);
                    // default values are stored in a separate structure
                    self.parameter_defaults
                        .insert((resource_name, Some(name)), parameter_defaults);
                }

                // Enums are stored in a vec
                PDeclaration::Enum(e) => {
                    self.enums.push(e);
                }

                // Enum mappings are stored in a vec
                PDeclaration::Mapping(em) => {
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
        })
    }
}

// TESTS
//

#[cfg(test)]
mod tests {
    use super::*;

    fn parse_code(input: &str) -> Result<()> {
        let mut code_index = CodeIndex::new();
        let content = parse_file("test_string", input).unwrap();
        code_index.add_parsed_file(content)
    }

    #[test]
    fn test_metadata() {
        assert!(parse_code(
            "@format=0
@test=\"ok\"
resource x()
        "
        )
        .is_ok());
    }

    #[test]
    fn test_enum() {
        assert!(parse_code(
            "@format=0
enum abc { a, b, c }
        "
        )
        .is_ok());
    }

    #[test]
    fn test_resource() {
        assert!(parse_code(
            "@format=0
## resource comment
resource File(name)
        "
        )
        .is_ok());
        assert!(parse_code(
            "@format=0
## resource comment
resource File(name)
resource File(name2)
        "
        )
        .is_err());
        assert!(parse_code(
            "@format=0
resource File(name)
File state content() { }
        "
        )
        .is_ok());
        assert!(parse_code(
            "@format=0
resource File(name)
File2 state content() { }
        "
        )
        .is_err());
        assert!(parse_code(
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
        assert!(parse_code(
            "@format=0
resource File(name=\"default\")
        "
        )
        .is_ok());
        assert!(parse_code(
            "@format=0
resource File(name=\"default\",path)
        "
        )
        .is_ok()); // no default order check here
        assert!(parse_code(
            "@format=0
resource File(name=\"defaul${\")
        "
        )
        .is_ok()); // default parsing not done here
    }
}
