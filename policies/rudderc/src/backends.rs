// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

//! Target != Backend, we could have different target compiled by the same backend

use crate::backends::metadata::Metadata;
use anyhow::{Error, Result};
use rudder_commons::Target;
use std::path::Path;
use walkdir::WalkDir;

pub use self::{unix::Unix, windows::Windows};
use crate::ir::Technique;

// Special "backend" for reporting data for the webapp
pub mod metadata;

// Technique generation backends
pub mod unix;
pub mod windows;

/// A backend is something that can generate final code for a given language from an IR
pub trait Backend {
    // For now, we only generate one file content
    fn generate(&self, policy: Technique, resources: &Path) -> Result<String>;

    /// List resources in directory
    ///
    /// Note: We only support UTF-8 file names.
    fn list_resources(path: &Path) -> Result<Vec<String>>
    where
        Self: Sized,
    {
        if path.is_dir() {
            WalkDir::new(path)
                // We need a stable order
                .sort_by_file_name()
                .into_iter()
                // Only select files
                .filter(|r| r.as_ref().map(|e| e.file_type().is_file()).unwrap_or(true))
                .map(|e| {
                    e.map(|e| {
                        e.path()
                            // relative path
                            .strip_prefix(path)
                            .unwrap()
                            .to_string_lossy()
                            .to_string()
                    })
                    .map_err(|e| e.into())
                })
                .collect::<Result<Vec<String>, Error>>()
        } else {
            Ok(vec![])
        }
    }
}

/// Select the right backend
pub fn backend(target: Target) -> Box<dyn Backend> {
    match target {
        Target::Unix => Box::new(Unix::new()),
        Target::Windows => Box::new(Windows::new()),
        Target::Metadata => Box::new(Metadata),
        Target::Docs => unreachable!(),
    }
}

pub fn metadata_backend() -> impl Backend {
    Metadata
}
