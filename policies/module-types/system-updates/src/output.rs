// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

// TODO macros for log+output

use crate::package_manager::PackageDiff;
use anyhow::Result;
use serde::{Deserialize, Serialize};

// FIXME: wrapper for Command

/*
       outputs.append('# Running ' + " ".join(command))
       errors.append('# Running ' + " ".join(command))
       (code, output, error) = run(command)
       outputs.append(output)
       errors.append(error)
*/

/// Outcome of each function
///
/// We need to collect outputs for reporting
pub struct ResultOutput<T> {
    res: Result<T>,
    stdout: Vec<String>,
    stderr: Vec<String>,
}

impl<T> ResultOutput<T> {
    pub fn builder() -> ResultOutputBuilder {
        ResultOutputBuilder::new()
    }
}

pub struct ResultOutputBuilder {
    stdout: Vec<String>,
    stderr: Vec<String>,
}

impl ResultOutputBuilder {
    pub fn new() -> Self {
        Self {
            stdout: vec![],
            stderr: vec![],
        }
    }

    pub fn stdout(&mut self, s: String) {
        self.stdout.push(s)
    }

    pub fn stderr(&mut self, s: String) {
        self.stderr.push(s)
    }

    pub fn build<T>(self, result: T) -> ResultOutput<T> {
        ResultOutput {
            res: result,
            stdout: self.stdout,
            stderr: self.stderr,
        }
    }
}

// Same as the Python implementation in 8.1.
#[derive(Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
struct Report {
    pub software_updated: Vec<PackageDiff>,
    pub status: String,
    pub output: String,
}
