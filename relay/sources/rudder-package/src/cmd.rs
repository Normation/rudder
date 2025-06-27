// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023 Normation SAS

use std::{process::Command, str::FromStr};

use anyhow::Result;
use serde::{Serialize, ser::SerializeMap};
use tracing::debug;

pub struct CmdOutput {
    pub command: String,
    pub output: std::process::Output,
}

impl CmdOutput {
    pub fn new(cmd: &mut Command) -> Result<Self> {
        let output = cmd.output()?;
        let cmd_output = CmdOutput {
            command: format!("{cmd:?}"),
            output,
        };
        debug!("{}", cmd_output);
        Ok(cmd_output)
    }

    pub fn get_stdout(&self) -> String {
        match String::from_utf8(self.output.stdout.clone()) {
            Ok(o) => o,
            Err(_) => String::from_str("").unwrap(),
        }
    }

    pub fn get_stderr(&self) -> String {
        match String::from_utf8(self.output.stderr.clone()) {
            Ok(o) => o,
            Err(_) => String::from_str("").unwrap(),
        }
    }
}

impl std::fmt::Display for CmdOutput {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(
            f,
            "Execute command:\n{}\nstatus:\n{}\nstdout:\n{}\nstderr:\n{}",
            self.command,
            self.output.status,
            self.get_stdout(),
            self.get_stderr()
        )
    }
}

impl Serialize for CmdOutput {
    fn serialize<S>(&self, serializer: S) -> std::prelude::v1::Result<S::Ok, S::Error>
    where
        S: serde::Serializer,
    {
        let code = self.output.status.code().unwrap_or(999);
        let mut inner = serializer.serialize_map(None)?;
        inner.serialize_entry("Exitcode", &code)?;
        inner.serialize_entry("Stdout", &self.get_stdout())?;
        inner.serialize_entry("Stderr", &self.get_stderr())?;
        inner.end()
    }
}
