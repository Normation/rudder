// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use crate::output::ResultOutput;
use anyhow::{bail, Result};
use std::path::Path;
use std::process::Command;
// We have systemd everywhere.

pub struct System {}

impl System {
    pub fn new() -> Self {
        Self {}
    }

    // FIXME: smarter way of rebooting...
    pub fn reboot(&self) -> ResultOutput<()> {
        let mut res = ResultOutput::new(Ok(()));
        let mut c = Command::new("systemctl");
        c.arg("reboot");
        let _ = res.command(c);
        res
    }

    pub fn restart_services(&self, services: &[String]) -> ResultOutput<()> {
        let mut res = ResultOutput::new(Ok(()));

        // Make sure the units are up to date
        let mut c = Command::new("systemctl");
        c.arg("daemon-reload");
        let _ = res.command(c);

        let mut c = Command::new("systemctl");
        c.arg("restart").args(services);
        let _ = res.command(c);

        res
    }
}
