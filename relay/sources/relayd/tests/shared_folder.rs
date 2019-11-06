// Copyright 2019 Normation SAS
//
// This file is part of Rudder.
//
// Rudder is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// In accordance with the terms of section 7 (7. Additional Terms.) of
// the GNU General Public License version 3, the copyright holders add
// the following Additional permissions:
// Notwithstanding to the terms of section 5 (5. Conveying Modified Source
// Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
// Public License version 3, when you create a Related Module, this
// Related Module is not considered as a part of the work and may be
// distributed under the license agreement of your choice.
// A "Related Module" means a set of sources files including their
// documentation that, without modification of the Source Code, enables
// supplementary functions or services in addition to those offered by
// the Software.
//
// Rudder is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

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
            .head("http://127.0.0.1:3030/rudder/relay-api/1/shared-folder/c745a140-40bc-4b86-b6dc-084488fc906b/37817c4d-fbf7-4850-a985-50021f4e8f41/file?hash_type=sha256&hash=cafebabe0xdeadbeef")
            .send()
            .unwrap();

        assert_eq!(200, hashes_are_not_equal.status());

        let no_hash_sent = client
            .head("http://127.0.0.1:3030/rudder/relay-api/1/shared-folder/c745a140-40bc-4b86-b6dc-084488fc906b/37817c4d-fbf7-4850-a985-50021f4e8f41/file?hash_type=sha256&hash=")
            .send()
            .unwrap();

        assert_eq!(200, no_hash_sent.status());

        let wrong_path = client
            .head("http://127.0.0.1:3030/rudder/relay-api/1/shared-folder/wrong-key-hash/37817c4d-fbf7-4850-a985-50021f4e8f41/file?hash_type=sha256&hash=181210f8f9c779c26da1d9b2075bde0127302ee0e3fca38c9a83f5b1dd8e5d3b")
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
