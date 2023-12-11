// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023 Normation SAS

use std::{process::Command, str::FromStr};

use anyhow::Result;
use tracing::debug;

pub struct CmdOutput {
    pub command: String,
    pub output: std::process::Output,
}

impl CmdOutput {
    pub fn new(cmd: &mut Command) -> Result<Self> {
        let output = cmd.output()?;
        let cmd_output = CmdOutput {
            command: format!("{:?}", cmd),
            output,
        };
        debug!("{}", cmd_output);
        Ok(cmd_output)
    }
}

impl std::fmt::Display for CmdOutput {
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
