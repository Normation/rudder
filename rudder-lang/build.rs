use ron::de::from_reader;
use serde::Deserialize;

#[derive(Default, Debug, Deserialize)]
struct OsTreeBuilder {
    types: Vec<String>,
    families: Vec<String>,
    major: Vec<String>,
    minor: Vec<String>,
    minor_map: Vec<String>,
}

#[derive(Debug, Deserialize)]
enum OsTree {
    Oses(Vec<Os>),
    Family((String, Vec<Box<OsTree>>)),
    Type((String, Vec<Box<OsTree>>)),
}

// Mapping purpose only, allows to print an os in the right container which is either a type or a family
#[derive(Debug, Clone)]
enum ParentBranch {
    Family(String),
    Type(String),
    None,
}

/// A Version is composed of a `major` and an array of `minor`s
/// Not all versions are numbers therefore fields have to be strings
/// `major` / `minor` are holded into an `Option` because:
/// - `None` for a `major` means Rudder will work on any version
/// - `None` for a `minor` means Rudder would work on any subversion of the `major`
type Version = (Option<String>, Option<Vec<String>>);
#[derive(Debug, Deserialize)]
struct Os(String, Vec<Version>);
/// Generates an Os and all its possible variants as a vec of stringified Os'es
impl Os {
    fn get_name(&self) -> &str { &self.0 }
    fn get_versions(&self) -> Vec<Version> {
        // hack to make sure enven with an empty array a default major is created
        if self.1.len() > 0 {
            self.1.clone()
        } else {
            vec![(None, None)]
        }
    }

    fn as_vec(&self, tree_builder: &mut OsTreeBuilder, parent: &ParentBranch) {
        let osname = self.get_name();

        match parent {
            ParentBranch::Type(name) => tree_builder.types.push(format!("{} -> {}", osname, name)),
            ParentBranch::Family(name) => tree_builder.families.push(format!("{} -> {}", osname, name)),
            _ => ()
        };

        for version in &self.get_versions() {
            let major = match &version.0 {
                Some(m) => m.to_owned(),
                None => "0".to_owned(),
            };
            let minors = match &version.1 {
                Some(m) => m.to_vec(),
                None => vec!["0".to_owned()],
            };
            for minor in minors {
                tree_builder.minor.push(format!("{}_{}_{}", osname, major, minor));
                tree_builder.minor_map.push(format!("{}_{}_{} -> {}_{}", osname, major, minor, osname, major));
            }
            tree_builder.major.push(format!("{}_{} -> {}", osname, major, osname));
        }
    }
}

/// Browses an Os branch to convert it to a full list of supported oses
fn rec_oslib_builder(os_tree: &OsTree, tree_builder: &mut OsTreeBuilder, parent: ParentBranch) {
    match os_tree {
        OsTree::Oses(oses) => {
            for os in oses {
                os.as_vec(tree_builder, &parent);
            }
        },
        OsTree::Family((name, osboxes)) => {
            if let ParentBranch::Type(parent_name) = parent {
                tree_builder.types.push(format!("{} -> {}", name, parent_name));
            }
            for osbox in osboxes {
                rec_oslib_builder(&*osbox, tree_builder, ParentBranch::Family(name.to_string()));
            }
        },
        OsTree::Type((name, osboxes)) => {
            for osbox in osboxes {
                rec_oslib_builder(&*osbox, tree_builder, ParentBranch::Type(name.to_string()));
            }
        },
    };
}

/// Reads the os builder .RON file and converts its content
/// into a necessary rudder-lang `global enum os` that holds every single supported Os(kind)
fn generate_oslib(fname: &str, oslib_filename: &str) -> std::io::Result<()> {
    let file_tree = std::fs::File::open(fname)?;
    let recvd_tree: Vec<OsTree> = from_reader(file_tree).unwrap();

    let mut tree_builder = OsTreeBuilder::default();
    recvd_tree.iter().for_each(|branch| rec_oslib_builder(branch, &mut tree_builder, ParentBranch::None));
    
    // write global enum os in a file
    let content = format!(
r#"@format=0

enum os ~> family {{
  {},
  *->*
}}

enum family ~> type {{
  {},
  *->*
}}

enum major ~> os {{
  {},
  *->*
}}

enum minor ~> major {{
  {},
  *->*
}}

global enum minor {{
  {}
}}
"#,
        tree_builder.families.join(",\n  "),
        tree_builder.types.join(",\n  "),
        tree_builder.major.join(",\n  "),
        tree_builder.minor_map.join(",\n  "),
        tree_builder.minor.join(",\n  "),
    );
    std::fs::write(oslib_filename, content.as_bytes())?;
    Ok(())
}

fn main() {
    println!("Generating OS list");
    generate_oslib("libs/osbuilder.ron", "libs/oslib.rl").expect("Could not generate the os list");
}
