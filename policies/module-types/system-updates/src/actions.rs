// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use anyhow::{bail, Result};
use std::path::Path;
use std::process::Command;

const SYSTEMCTL_PATH: &str = "/usr/bin/systemctl";
const SHUTDOWN_PATH: &str = "/usr/sbin/shutdown";
const SERVICE_PATH: &str = "/usr/sbin/service";

pub struct System {
    has_systemd: bool,
}

impl System {
    pub fn new() -> Self {
        Self {
            has_systemd: Path::new(SYSTEMCTL_PATH).exists(),
        }
    }

    pub fn reboot(&self) -> Result<()> {
        let c = if self.has_systemd {
            Command::new(SYSTEMCTL_PATH).arg("reboot").output()
        } else {
            if Path::new(SHUTDOWN_PATH).exists() {
                Command::new(SHUTDOWN_PATH)
                    .arg("--reboot")
                    .arg("now")
                    .output()
            } else {
                bail!("Could not find a way to reboot the system");
            }
        };
        let todo = c?;

        Ok(())
    }

    pub fn restart_services(&self, services: &[String]) -> Result<()> {
        if self.has_systemd {
            Command::new(SYSTEMCTL_PATH).arg("daemon-reload").status()?;
            Command::new(SYSTEMCTL_PATH)
                .arg("restart")
                .args(services)
                .output()?;
        } else {
            for s in services {
                Command::new(SERVICE_PATH).arg(s).arg("restart").output()?;
            }
        }

        Ok(())
    }
}
