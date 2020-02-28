// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

mod common;

use relayd::{configuration::cli::CliConfiguration, init_logger, start};
use reqwest;
use std::thread;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn it_shares_folder() {
        let cli_cfg = CliConfiguration::new("tests/files/config/", false);

        thread::spawn(move || {
            start(cli_cfg, init_logger().unwrap()).unwrap();
        });

        assert!(common::start_api().is_ok());

        let client = reqwest::Client::new();

        // curl --head http://127.0.0.1:3030/rudder/relay-api/shared-folder/c745a140-40bc-4b86-b6dc-084488fc906b/37817c4d-fbf7-4850-a985-50021f4e8f41/file?hash_type=sha256?hash=181210f8f9c779c26da1d9b2075bde0127302ee0e3fca38c9a83f5b1dd8e5d3b

        let hashes_are_equal = client
            .head("http://127.0.0.1:3030/rudder/relay-api/1/shared-folder/c745a140-40bc-4b86-b6dc-084488fc906b/37817c4d-fbf7-4850-a985-50021f4e8f41/file?hash_type=sha256&hash=181210f8f9c779c26da1d9b2075bde0127302ee0e3fca38c9a83f5b1dd8e5d3b")
            .send()
            .unwrap();

        assert_eq!(304, hashes_are_equal.status());

        let hashes_are_not_equal = client
            .head("http://127.0.0.1:3030/rudder/relay-api/1/shared-folder/c745a140-40bc-4b86-b6dc-084488fc906b/37817c4d-fbf7-4850-a985-50021f4e8f41/file?hash_type=sha256&hash=181210f8f9c779c26da1d9b2075bde0127302ee0e3fca38c9a83f5b1dd8e5d78")
            .send()
            .unwrap();

        assert_eq!(200, hashes_are_not_equal.status());

        let hashes_invalid = client
            .head("http://127.0.0.1:3030/rudder/relay-api/1/shared-folder/c745a140-40bc-4b86-b6dc-084488fc906b/37817c4d-fbf7-4850-a985-50021f4e8f41/file?hash_type=sha256&hash=test")
            .send()
            .unwrap();

        assert_eq!(500, hashes_invalid.status());

        let no_hash_sent = client
            .head("http://127.0.0.1:3030/rudder/relay-api/1/shared-folder/c745a140-40bc-4b86-b6dc-084488fc906b/37817c4d-fbf7-4850-a985-50021f4e8f41/file?hash_type=sha256&hash=")
            .send()
            .unwrap();

        assert_eq!(200, no_hash_sent.status());

        let wrong_path = client
            .head("http://127.0.0.1:3030/rudder/relay-api/1/shared-folder/wrong-node/37817c4d-fbf7-4850-a985-50021f4e8f41/file?hash_type=sha256&hash=181210f8f9c779c26da1d9b2075bde0127302ee0e3fca38c9a83f5b1dd8e5d3b")
            .send()
            .unwrap();

        assert_eq!(404, wrong_path.status());

        let internal_error = client
            .head("http://127.0.0.1:3030/rudder/relay-api/1/shared-folder/c745a140-40bc-4b86-b6dc-084488fc906b/37817c4d-fbf7-4850-a985-50021f4e8f41/file?hash_type=wrong-hash-type&hash=181210f8f9c779c26da1d9b2075bde0127302ee0e3fca38c9a83f5b1dd8e5d3b")
            .send()
            .unwrap();

        assert_eq!(500, internal_error.status());

        let mut get_succeeds = client
            .get("http://127.0.0.1:3030/rudder/relay-api/1/shared-folder/c745a140-40bc-4b86-b6dc-084488fc906b/37817c4d-fbf7-4850-a985-50021f4e8f41/file")
            .send()
            .unwrap();

        assert_eq!(200, get_succeeds.status());
        assert_eq!(get_succeeds.text().unwrap(), "123\n");

        let mut get_fails = client
            .get("http://127.0.0.1:3030/rudder/relay-api/1/shared-folder/c745a140-40bc-4b86-b6dc-084488fc906b/doesnotexist")
            .send()
            .unwrap();

        assert_eq!(404, get_fails.status());
        assert_eq!(get_fails.text().unwrap(), "");
    }
}
