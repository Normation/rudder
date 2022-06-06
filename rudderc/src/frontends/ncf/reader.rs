// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2022 Normation SAS

//! Reads ncf metadata from generic method `.cf` files
//!
//! Does not parse .cf policies but only metadata in ncf format.

use std::fs::read_to_string;
use std::path::Path;

use anyhow::{anyhow, bail, Context, Result};
use log::{debug, warn};
use walkdir::{DirEntry, WalkDir};

use super::method::Method;

/// Detect valid .cf source files, skip ignored ones (starting with _)
fn is_cf_file(entry: &DirEntry) -> bool {
    entry
        .file_name()
        .to_str()
        .map(|s| s.ends_with(".cf") && !s.starts_with('_'))
        .unwrap_or(false)
}

/*
   filelist1 = get_all_generic_methods_filenames_in_dir(this "/tree/30_generic_methods")
   filelist2 = get_all_generic_methods_filenames_in_dir("/var/rudder/configuration-repository/ncf/30_generic_methods")
*/

pub fn read_lib(path: &Path) -> Result<Vec<Method>> {
    let mut methods = vec![];

    if !path.exists() {
        bail!("Could not open library in {}", path.display());
    }

    let walker = WalkDir::new(path)
        .into_iter()
        .filter(|r| r.as_ref().map(is_cf_file).unwrap_or(false));
    for source_file in walker {
        let source = source_file?;
        debug!("Parsing {}", source.path().display());
        let data = read_to_string(source.path())?;

        let method_result: Result<Method> = data
            .parse()
            .context(anyhow!("Error parsing {}", source.path().display()));
        match method_result {
            Ok(mut m) => {
                // Add source path
                m.source = Some(source.path().to_path_buf());
                methods.push(m);
            }
            Err(e) => {
                // Display error
                warn!("{:?}", e);
                // Skip broken file
                continue;
            }
        }
    }

    Ok(methods)
}
