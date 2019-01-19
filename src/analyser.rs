mod enums;
mod context;
//mod strings;

use crate::error::*;
use crate::parser::*;

pub fn analyse(ast: PFile) -> Result<()> {
    let mut enumlist = enums::EnumList::new();
    // check header version
    if ast.header.version != 0 { panic!("Multiple format not supported yet"); }
    for decl in ast.code {
        match decl {
            PDeclaration::Comment(_) => {},
            PDeclaration::Metadata(_) => {},
            PDeclaration::Resource(_) => {},
            PDeclaration::State(_) => {},
            PDeclaration::Enum(e) => enumlist.add_enum(e)?,
            PDeclaration::Mapping(e) => enumlist.add_mapping(e)?,
        }
    }
    Ok(())
}

