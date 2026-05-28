// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024-2026 Normation SAS

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
    if services.is_empty() {
        return ResultOutput::new_output(
            Ok(()),
            vec!["No services to restart".to_string()],
            Vec::new(),
        );
    }

    let services = systemd_get_restartable_services(services);

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

fn systemd_get_restartable_services(services: &[String]) -> Vec<&str> {
    let mut res = Vec::new();

    for service in services {
        let output = Command::new("systemctl")
            .arg("show")
            .arg(service)
            .arg("-p")
            .arg("RefuseManualStop")
            .arg("--value")
            .output();

        let output = match output {
            Ok(o) if o.status.success() => o,
            _ => continue,
        };

        let value = String::from_utf8_lossy(&output.stdout).trim().to_string();

        if value.contains("no") {
            res.push(service.as_str());
        }
    }
    res
}
