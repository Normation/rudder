// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use std::thread;

use crate::common::random_ports;
use rudder_relayd::{configuration::cli::CliConfiguration, init_logger, start};

mod common;

#[test]
fn it_shares_folder() {
    let cli_cfg = CliConfiguration::new("tests/files/config/", false);
    let (api_port, https_port) = random_ports();

    thread::spawn(move || {
        start(
            cli_cfg,
            init_logger().unwrap(),
            Some((api_port, https_port)),
        )
        .unwrap();
    });
    assert!(common::start_api(api_port).is_ok());

    let client = reqwest::blocking::Client::new();

    // curl --head http://127.0.0.1:3030/rudder/relay-api/shared-folder/c745a140-40bc-4b86-b6dc-084488fc906b/37817c4d-fbf7-4850-a985-50021f4e8f41/file?hash_type=sha256?hash=181210f8f9c779c26da1d9b2075bde0127302ee0e3fca38c9a83f5b1dd8e5d3b

    let hashes_are_equal = client
            .head(format!(
                "http://localhost:{api_port}/rudder/relay-api/1/shared-folder/c745a140-40bc-4b86-b6dc-084488fc906b/37817c4d-fbf7-4850-a985-50021f4e8f41/file?hash_type=sha256&hash=181210f8f9c779c26da1d9b2075bde0127302ee0e3fca38c9a83f5b1dd8e5d3b"))
            .send()
            .unwrap();

    assert_eq!(304, hashes_are_equal.status());

    // curl --head http://127.0.0.1:3030/rudder/relay-api/shared-folder/c745a140-40bc-4b86-b6dc-084488fc906b/37817c4d-fbf7-4850-a985-50021f4e8f41/file%20with%20space?hash_type=sha256?hash=181210f8f9c779c26da1d9b2075bde0127302ee0e3fca38c9a83f5b1dd8e5d3b

    let hashes_are_equal = client
        .head(format!(
            "http://localhost:{api_port}/rudder/relay-api/1/shared-folder/c745a140-40bc-4b86-b6dc-084488fc906b/37817c4d-fbf7-4850-a985-50021f4e8f41/file%20with%20space?hash_type=sha256&hash=181210f8f9c779c26da1d9b2075bde0127302ee0e3fca38c9a83f5b1dd8e5d3b"))
        .send()
        .unwrap();

    assert_eq!(304, hashes_are_equal.status());

    let hashes_are_not_equal = client
            .head(format!(
                "http://localhost:{api_port}/rudder/relay-api/1/shared-folder/c745a140-40bc-4b86-b6dc-084488fc906b/37817c4d-fbf7-4850-a985-50021f4e8f41/file?hash_type=sha256&hash=181210f8f9c779c26da1d9b2075bde0127302ee0e3fca38c9a83f5b1dd8e5d78"))
            .send()
            .unwrap();

    assert_eq!(200, hashes_are_not_equal.status());

    let hashes_invalid = client
            .head(format!(
                "http://localhost:{api_port}/rudder/relay-api/1/shared-folder/c745a140-40bc-4b86-b6dc-084488fc906b/37817c4d-fbf7-4850-a985-50021f4e8f41/file?hash_type=sha256&hash=test"))
            .send()
            .unwrap();

    assert_eq!(400, hashes_invalid.status());

    let no_hash_sent = client
            .head(format!(
                "http://localhost:{api_port}/rudder/relay-api/1/shared-folder/c745a140-40bc-4b86-b6dc-084488fc906b/37817c4d-fbf7-4850-a985-50021f4e8f41/file?hash_type=sha256&hash="))
            .send()
            .unwrap();

    assert_eq!(200, no_hash_sent.status());

    let wrong_path = client
            .head(format!(
                "http://localhost:{api_port}/rudder/relay-api/1/shared-folder/wrong-node/37817c4d-fbf7-4850-a985-50021f4e8f41/file?hash_type=sha256&hash=181210f8f9c779c26da1d9b2075bde0127302ee0e3fca38c9a83f5b1dd8e5d3b"))
            .send()
            .unwrap();

    assert_eq!(404, wrong_path.status());

    let request_error = client
            .head(format!(
                "http://localhost:{api_port}/rudder/relay-api/1/shared-folder/c745a140-40bc-4b86-b6dc-084488fc906b/37817c4d-fbf7-4850-a985-50021f4e8f41/file?hash_type=wrong-hash-type&hash=181210f8f9c779c26da1d9b2075bde0127302ee0e3fca38c9a83f5b1dd8e5d3b"))
            .send()
            .unwrap();

    assert_eq!(400, request_error.status());

    let get_succeeds = client
            .get(format!(
                "http://localhost:{api_port}/rudder/relay-api/1/shared-folder/c745a140-40bc-4b86-b6dc-084488fc906b/37817c4d-fbf7-4850-a985-50021f4e8f41/file"))
            .send()
            .unwrap();

    assert_eq!(200, get_succeeds.status());
    assert_eq!(get_succeeds.text().unwrap(), "123\n");

    let get_fails = client
            .get(format!(
                "http://localhost:{api_port}/rudder/relay-api/1/shared-folder/c745a140-40bc-4b86-b6dc-084488fc906b/doesnotexist"))
            .send()
            .unwrap();

    assert_eq!(404, get_fails.status());
    assert_eq!(get_fails.text().unwrap(), "NOT FOUND");
}
