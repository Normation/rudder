// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2022 Normation SAS

use crate::compiler::Methods;
use crate::frontends::methods::method::MethodInfo;
use crate::frontends::methods::reader::read_lib;
use crate::logs::ok_output;
use anyhow::Result;
use std::collections::HashMap;
use std::path::PathBuf;

pub mod method;
pub mod reader;

pub fn read_methods(libraries: &[PathBuf]) -> Result<&'static Methods> {
    let mut methods = HashMap::new();
    for library in libraries {
        let add = read_lib(library)?;
        let len = add.len();
        for m in add {
            methods.insert(m.bundle_name.clone(), m);
        }
        ok_output("Read", format!("{} methods ({})", len, library.display()))
    }
    let methods = Box::new(methods);
    // Get a static reference to allow easier usage. Methods don't change
    // during execution.
    let methods: &'static mut HashMap<String, MethodInfo> = Box::leak(methods);
    Ok(methods)
}
