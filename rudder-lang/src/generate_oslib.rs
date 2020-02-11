use crate::{
    error::*,
    parser::Token
};
use colored::Colorize;
use ron::de::from_reader;
use serde::Deserialize;
use std::fs;


#[derive(Debug, Deserialize)]
struct OsTreeBuilder(Vec<OsTree>);

#[derive(Debug, Deserialize)]
enum OsTree {
    Oses(Vec<Os>),
    Family((String, Vec<Box<OsTree>>)),
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
    fn get_versions(&self) -> &Vec<Version> { &self.1 }

    fn as_vec(&self) -> Vec<String> {
        let osname = self.get_name();
        let mut osfull: Vec<String> = Vec::new();
        // add a comment line to visually separate oses
        // osfull.push(format!("# {}", osname));
        osfull.push(osname.to_owned());
        for version in self.get_versions() {
            if let Some(major) = &version.0 {
                if let Some(minors) = &version.1 {
                    for minor in minors {
                        osfull.push(format!("{}_{}_{}", osname, major, minor));
                    }
                } else {
                    osfull.push(format!("{}_{}", osname, major));
                }
            }
        }
        osfull
    }
}

/// Browses an Os branch to convert it to a full list of supported oses
fn rec_oslib_builder(os_tree: &OsTree, mut oslist: &mut Vec<String>) {
    match os_tree {
        OsTree::Oses(oses) => {
            for os in oses {
                oslist.extend(os.as_vec());
            }
        },
        OsTree::Family((name, osboxes)) => {
            oslist.push(format!("{}, # family", name));
            for osbox in osboxes {
                rec_oslib_builder(&*osbox, &mut oslist);
            }
        },
    }
}

/// Reads the os builder .RON file and converts its content
/// into a necessary rudder-lang `global enum os` that holds every single supported Os(kind)
pub fn generate_oslib(fname: &str, oslib_filename: &str) -> Result<()> {
    info!("{} from {}", "Generating OS list".bright_green(), fname.bright_yellow());
    let ftree = fs::File::open(fname).map_err(|e| err!(Token::new(&fname.to_owned(), ""), "{}", e))?;
    let os_tree_builder: OsTreeBuilder = from_reader(ftree).map_err(|e| err!(Token::new(fname, ""), "{}", e))?;

    let mut oslist: Vec<String> = Vec::new();
    os_tree_builder.0.iter().for_each(|os_tree| rec_oslib_builder(os_tree, &mut oslist));
    
    // // write global enum os in a file
    let content = format!("@format=0\n\nglobal enum os {{\n  {}\n}}", oslist.join(",\n  "));
    {    
        fs::write(oslib_filename, content.as_bytes()).expect("Could not write content to os lib file");
    }
    Ok(())
}
