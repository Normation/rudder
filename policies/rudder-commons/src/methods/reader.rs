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

fn is_in_non_method_dir(path: &Path) -> bool {
    NON_METHOD_DIR
        .iter()
        .any(|dir| path.to_string_lossy().contains(dir))
}

/// Detect valid .cf source files, skip ignored ones (starting with _, non 30_generic_methods).
fn is_cf_method(entry: &DirEntry) -> bool {
    if is_in_non_method_dir(entry.path()) {
        return false;
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

    // Keep walk errors (e.g. an unreadable directory) so they are reported instead of
    // silently producing an incomplete library, except in directories we would skip anyway.
    let walker = WalkDir::new(path).into_iter().filter(|r| match r {
        Ok(entry) => is_cf_method(entry),
        Err(e) => !e.path().is_some_and(is_in_non_method_dir),
    });
    for source_file in walker {
        let source = match source_file {
            Ok(s) => s,
            Err(e) => {
                // Display error
                warn!("Listing method files in {}: {e}", path.display());
                // Skip unreadable entry
                continue;
            }
        };

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

#[cfg(all(test, unix))]
mod tests {
    use std::{
        fs::{self, Permissions},
        os::unix::fs::PermissionsExt,
    };

    use super::*;

    fn make_unreadable(dir: &Path) {
        fs::set_permissions(dir, Permissions::from_mode(0o000)).unwrap();
    }

    #[test]
    fn it_skips_unreadable_method_dir() {
        let lib = tempfile::tempdir().unwrap();
        let methods_dir = lib.path().join("30_generic_methods");
        fs::create_dir(&methods_dir).unwrap();
        make_unreadable(&methods_dir);
        let res = read_lib(lib.path());
        fs::set_permissions(&methods_dir, Permissions::from_mode(0o755)).unwrap();
        assert!(res.unwrap().is_empty());
    }

    #[test]
    fn it_ignores_unreadable_non_method_dir() {
        let lib = tempfile::tempdir().unwrap();
        let internals_dir = lib.path().join("10_ncf_internals");
        fs::create_dir(&internals_dir).unwrap();
        make_unreadable(&internals_dir);
        let res = read_lib(lib.path());
        fs::set_permissions(&internals_dir, Permissions::from_mode(0o755)).unwrap();
        assert!(res.unwrap().is_empty());
    }
}
