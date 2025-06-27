// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2022 Normation SAS

//! Reads method metadata from generic method `.cf` files
//!
//! Does not parse .cf policies but only metadata in method format.

use std::{fs::read_to_string, path::Path};

use anyhow::{Context, Result, anyhow, bail};
use log::{debug, warn};
use walkdir::{DirEntry, WalkDir};

use super::method::MethodInfo;

/// Directories to skip when loading methods
const NON_METHOD_DIR: [&str; 2] = ["10_ncf_internals", "20_cfe_basics"];

/// Detect valid .cf source files, skip ignored ones (starting with _, non 30_generic_methods).
fn is_cf_method(entry: &DirEntry) -> bool {
    for dir in NON_METHOD_DIR {
        if entry.path().to_string_lossy().contains(dir) {
            return false;
        }
    }
    entry
        .file_name()
        .to_str()
        .map(|path| path.ends_with(".cf") && !path.starts_with('_'))
        .unwrap_or(false)
}

/// We handle both root lib dir, methods lib dir or direct method path
pub fn read_lib(path: &Path) -> Result<Vec<MethodInfo>> {
    let mut methods = vec![];

    if !path.exists() {
        bail!("Could not open library in {}", path.display());
    }

    let walker = WalkDir::new(path)
        .into_iter()
        .filter(|r| r.as_ref().map(is_cf_method).unwrap_or(false));
    for source_file in walker {
        let source = source_file?;

        debug!("Parsing {}", source.path().display());
        let data = read_to_string(source.path())
            .context(anyhow!("Reading method file {}", source.path().display()))?;

        let method_result: Result<MethodInfo> = data
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
                warn!("{e:?}");
                // Skip broken file
                continue;
            }
        }
    }

    Ok(methods)
}
