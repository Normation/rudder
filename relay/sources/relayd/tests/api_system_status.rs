// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use std::{fs::rename, thread};

use rudder_relayd::{configuration::cli::CliConfiguration, init_logger, start};

mod common;

#[test]
fn it_correctly_replies_to_status_api() {
    let cli_cfg = CliConfiguration::new("tests/files/config/", false);
    thread::spawn(move || {
        start(cli_cfg, init_logger().unwrap()).unwrap();
    });
    assert!(common::start_api().is_ok());

    let response: serde_json::Value = serde_json::from_str(
        &reqwest::blocking::get("http://localhost:3030/rudder/relay-api/1/system/status")
            .unwrap()
            .text()
            .unwrap(),
    )
    .unwrap();

    let reference: serde_json::Value = serde_json::from_str("{\"data\":{\"database\":{\"status\":\"success\"},\"configuration\":{\"status\":\"success\"}},\"result\":\"success\",\"action\":\"getStatus\"}").unwrap();

    assert_eq!(reference, response);

    // Broken config file

    rename(
        "tests/files/config/main.conf",
        "tests/files/config/main.conf.new",
    )
    .unwrap();

    let response: serde_json::Value = serde_json::from_str(
        &reqwest::blocking::get("http://localhost:3030/rudder/relay-api/1/system/status")
            .unwrap()
            .text()
            .unwrap(),
    )
    .unwrap();

    rename(
        "tests/files/config/main.conf.new",
        "tests/files/config/main.conf",
    )
    .unwrap();

    let reference: serde_json::Value = serde_json::from_str("{\"data\":{\"database\":{\"status\":\"success\"},\"configuration\":{\"status\":\"error\", \"details\": \"Could not read main configuration file from tests/files/config/main.conf\"}},\"result\":\"success\",\"action\":\"getStatus\"}").unwrap();

    assert_eq!(reference, response);
}
