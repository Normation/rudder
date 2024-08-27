// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use std::process::Command;

use crate::output::ResultOutput;
// We have systemd everywhere.

pub struct System {}

impl System {
    pub fn new() -> Self {
        Self {}
    }

    // Maybe add a delay before rebooting?
    pub fn reboot(&self) -> ResultOutput<()> {
        let mut c = Command::new("systemctl");
        c.arg("reboot");
        ResultOutput::command(c).clear_ok()
    }

    pub fn restart_services(&self, services: &[String]) -> ResultOutput<()> {
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
