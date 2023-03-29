// SPDX-License-Identifier: GPL-3.0-or-later WITH GPL-3.0-linking-source-exception
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

mod common;

use std::{fs, thread};

use common::{fake_server_start, fake_server_stop};
use rudder_relayd::{configuration::cli::CliConfiguration, init_logger, start};

fn upstream_call_ok(client: &reqwest::blocking::Client, should_be_ok: bool) {
    let params_sync = [
        ("asynchronous", "false"),
        ("keep_output", "true"),
        ("classes", "class2,class5"),
        ("nodes", "root,c745a140-40bc-4b86-b6dc-084488fc906b"),
    ];

    let response = client
        .post("http://localhost:3030/rudder/relay-api/1/remote-run/nodes")
        .form(&params_sync)
        .send()
        .unwrap();
    assert_eq!(response.status(), hyper::StatusCode::OK);
    if should_be_ok {
        // No remote answer = upstream request failed
        assert_eq!(response.text().unwrap(), "OK\nEND\nREMOTE\n".to_string());
    } else {
        assert_eq!(response.text().unwrap(), "OK\nEND\n".to_string());
    }
}

fn reload_config(client: &reqwest::blocking::Client) {
    let response: serde_json::Value = serde_json::from_str(
        &client
            .post("http://localhost:3030/rudder/relay-api/1/system/reload")
            .send()
            .unwrap()
            .text()
            .unwrap(),
    )
    .unwrap();

    let reference: serde_json::Value =
        serde_json::from_str("{\"result\":\"success\",\"action\":\"reloadConfiguration\"}")
            .unwrap();
    assert_eq!(reference, response);
}

#[test]
fn it_reloads_http_clients() {
    let cli_cfg = CliConfiguration::new("tests/files/config/", false);

    thread::spawn(move || {
        start(cli_cfg, init_logger().unwrap()).unwrap();
    });

    assert!(common::start_api().is_ok());
    let client = reqwest::blocking::Client::new();

    fake_server_start("37817c4d-fbf7-4850-a985-50021f4e8f41".to_string());

    // First successful request
    upstream_call_ok(&client, true);

    fs::copy(
        "tests/files/keys/nodescerts.pem",
        "target/tmp/nodescerts.pem.back",
    )
    .unwrap();
    // Replace by wrong certificate
    fs::copy(
        "tests/files/keys/e745a140-40bc-4b86-b6dc-084488fc906b.cert",
        "tests/files/keys/nodescerts.pem",
    )
    .unwrap();

    // Reload configuration
    reload_config(&client);

    // Fail as certificate is wrong
    upstream_call_ok(&client, false);

    // Put correct cert back in place
    fs::copy(
        "target/tmp/nodescerts.pem.back",
        "tests/files/keys/nodescerts.pem",
    )
    .unwrap();

    // Reload configuration
    reload_config(&client);

    // Should be back
    upstream_call_ok(&client, true);

    fake_server_stop();
}
