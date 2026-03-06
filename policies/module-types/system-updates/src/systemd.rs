// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use crate::output::{CommandBehavior, CommandCapture, ResultOutput};
use std::process::Command;

pub fn systemd_reboot() -> ResultOutput<()> {
    let mut c = Command::new("systemctl");
    c.arg("reboot");
    ResultOutput::command(
        c,
        CommandBehavior::FailOnErrorCode,
        CommandCapture::StdoutStderr,
    )
    .clear_ok()
}

pub fn systemd_restart_services(services: &[String]) -> ResultOutput<()> {
    // Make sure the units are up-to-date
    let mut c = Command::new("systemctl");
    c.arg("daemon-reload");
    let res = ResultOutput::command(
        c,
        CommandBehavior::FailOnErrorCode,
        CommandCapture::StdoutStderr,
    );

    let mut c = Command::new("systemctl");
    c.arg("restart").args(services);
    let res = res.step(ResultOutput::command(
        c,
        CommandBehavior::FailOnErrorCode,
        CommandCapture::StdoutStderr,
    ));
    res.clear_ok()
}
