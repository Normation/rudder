// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023 Normation SAS

use std::str::FromStr;

pub struct RudderCmdOutput {
    pub command: String,
    pub output: std::process::Output,
}

impl std::fmt::Display for RudderCmdOutput {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let stdout = match String::from_utf8(self.output.stdout.clone()) {
            Ok(o) => o,
            Err(_) => String::from_str("").unwrap(),
        };
        let stderr = match String::from_utf8(self.output.stderr.clone()) {
            Ok(o) => o,
            Err(_) => String::from_str("").unwrap(),
        };
        write!(
            f,
            "Execute command:\n{}\nstatus:\n{}\nstdout:\n{}\nstderr:\n{}",
            self.command, self.output.status, stdout, stderr
        )
    }
}
