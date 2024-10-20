// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use std::process::Command;

use crate::output::ResultOutput;

pub trait System {
    fn reboot(&self) -> ResultOutput<()>;
    fn restart_services(&self, services: &[String]) -> ResultOutput<()>;
}

/// We have systemd everywhere.
pub struct Systemd {}

impl Default for Systemd {
    fn default() -> Self {
        Self::new()
    }
}

impl Systemd {
    pub fn new() -> Self {
        Self {}
    }
}

impl System for Systemd {
    fn reboot(&self) -> ResultOutput<()> {
        let mut c = Command::new("systemctl");
        c.arg("reboot");
        ResultOutput::command(c).clear_ok()
    }

    fn restart_services(&self, services: &[String]) -> ResultOutput<()> {
        // Make sure the units are up-to-date
        let mut c = Command::new("systemctl");
        c.arg("daemon-reload");
        let res = ResultOutput::command(c);

        let mut c = Command::new("systemctl");
        c.arg("restart").args(services);
        let res = res.step(ResultOutput::command(c));
        res.clear_ok()
    }
}
