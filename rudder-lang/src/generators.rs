// Copyright 2019 Normation SAS
//
// This file is part of Rudder.
//
// Rudder is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// In accordance with the terms of section 7 (7. Additional Terms.) of
// the GNU General Public License version 3, the copyright holders add
// the following Additional permissions:
// Notwithstanding to the terms of section 5 (5. Conveying Modified Source
// Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
// Public License version 3, when you create a Related Module, this
// Related Module is not considered as a part of the work and may be
// distributed under the license agreement of your choice.
// A "Related Module" means a set of sources files including their
// documentation that, without modification of the Source Code, enables
// supplementary functions or services in addition to those offered by
// the Software.
//
// Rudder is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

mod cfengine;

pub use self::cfengine::CFEngine;
use crate::ast::AST;
use crate::error::*;
use std::path::Path;

/// A generator is something that can generate final code for a given language from an AST
/// We want at least cfengine, dsc, mgmt
pub trait Generator {
    /// If file is None: Generate code for everything that has been parsed
    /// If file is some: Only generate code for a single source file (for incremental generation or for integration with existing files)
    fn generate(&mut self, gc: &AST, file: Option<&Path>, technique_metadata: bool) -> Result<()>;
}
