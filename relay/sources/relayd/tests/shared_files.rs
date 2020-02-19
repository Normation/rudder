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

use relayd::{
    configuration::cli::CliConfiguration, data::shared_file::Metadata, init_logger, start,
};
use reqwest;
use std::{
    fs::{read_to_string, remove_file},
    str::FromStr,
    thread,
};

mod common;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn it_shares_files() {
        let cli_cfg = CliConfiguration::new("tests/files/config/", false);

        thread::spawn(move || {
            start(cli_cfg, init_logger().unwrap()).unwrap();
        });

        assert!(common::start_api().is_ok());

        let client = reqwest::Client::new();

        // .sign created with:
        // tools/rudder-sign tests/api_shared_files/37817c4d-fbf7-4850-a985-50021f4e8f41/e745a140-40bc-4b86-b6dc-084488fc906b/file tests/files/keys/e745a140-40bc-4b86-b6dc-084488fc906b.priv tests/files/keys/e745a140-40bc-4b86-b6dc-084488fc906b.pub "node1.rudder.local"

        // curl --head http://127.0.0.1:3030/rudder/relay-api/1/shared-files/37817c4d-fbf7-4850-a985-50021f4e8f41/e745a140-40bc-4b86-b6dc-084488fc906b/file?hash=dda78e9b97a69aca3cff21de266246bde0d91bc4b61df72bfb0387564ac0c7bd64dd4caca39ce1ef400f32aa711ec4909789705beec93314eb65fabd5183bbfe

        let hashes_are_equal = client
            .head("http://127.0.0.1:3030/rudder/relay-api/1/shared-files/37817c4d-fbf7-4850-a985-50021f4e8f41/e745a140-40bc-4b86-b6dc-084488fc906b/file?hash=dda78e9b97a69aca3cff21de266246bde0d91bc4b61df72bfb0387564ac0c7bd64dd4caca39ce1ef400f32aa711ec4909789705beec93314eb65fabd5183bbfe")
            .send()
            .unwrap();

        assert_eq!(200, hashes_are_equal.status());

        let hashes_are_not_equal = client
            .head("http://127.0.0.1:3030/rudder/relay-api/1/shared-files/37817c4d-fbf7-4850-a985-50021f4e8f41/e745a140-40bc-4b86-b6dc-084488fc906b/file?hash=dda78e9b97a69aca3cff21de266246bde0d91bc4b61df72bfb0387564ac0c7bd64dd4caca39ce1ef400f32aa711ec4909789705beec93314eb65fabd5183bbf3")
            .send()
            .unwrap();

        assert_eq!(404, hashes_are_not_equal.status());

        let hashes_are_invalid = client
            .head("http://127.0.0.1:3030/rudder/relay-api/1/shared-files/37817c4d-fbf7-4850-a985-50021f4e8f41/e745a140-40bc-4b86-b6dc-084488fc906b/file?hash=0xcafecafecafe")
            .send()
            .unwrap();

        assert_eq!(404, hashes_are_invalid.status());

        let no_hash_sent = client
            .head("http://127.0.0.1:3030/rudder/relay-api/1/shared-files/37817c4d-fbf7-4850-a985-50021f4e8f41/e745a140-40bc-4b86-b6dc-084488fc906b/file?hash=")
            .send()
            .unwrap();

        assert_eq!(404, no_hash_sent.status());

        // prepare body content
        // .sign created with:
        // tools/rudder-sign tests/api_shared_files/37817c4d-fbf7-4850-a985-50021f4e8f41/e745a140-40bc-4b86-b6dc-084488fc906b/file2 tests/files/keys/e745a140-40bc-4b86-b6dc-084488fc906b.priv tests/files/keys/e745a140-40bc-4b86-b6dc-084488fc906b.pub "node1.rudder.local"

        let file = "tests/api_shared_files/37817c4d-fbf7-4850-a985-50021f4e8f41/files/e745a140-40bc-4b86-b6dc-084488fc906b/file2";
        let signature = read_to_string(&format!("{}.sign", file)).unwrap();
        let content = read_to_string(&format!("{}.source", file)).unwrap();

        // Wrong digest

        let wrong_signature = read_to_string(&format!("{}.wrongsign", file)).unwrap();
        let upload = client.put("http://127.0.0.1:3030/rudder/relay-api/1/shared-files/37817c4d-fbf7-4850-a985-50021f4e8f41/e745a140-40bc-4b86-b6dc-084488fc906b/file2?ttl=1d").body(format!("{}\n{}", wrong_signature, content))
        .send().unwrap();
        assert_eq!(500, upload.status());

        let upload = client.put("http://127.0.0.1:3030/rudder/relay-api/1/shared-files/37817c4d-fbf7-4850-a985-50021f4e8f41/e745a140-40bc-4b86-b6dc-084488fc906b/file2?ttl=1d").body(format!("{}\n{}", signature, "test".to_string()))
        .send().unwrap();
        assert_eq!(500, upload.status());

        // Correct upload

        let upload = client.put("http://127.0.0.1:3030/rudder/relay-api/1/shared-files/37817c4d-fbf7-4850-a985-50021f4e8f41/e745a140-40bc-4b86-b6dc-084488fc906b/file2?ttl=1d").body(format!("{}\n{}", signature, content))
        .send().unwrap();
        assert_eq!(200, upload.status());

        let mut written_metadata =
            Metadata::from_str(&read_to_string(&format!("{}.metadata", file)).unwrap()).unwrap();
        let expiration = (chrono::Utc::now() + chrono::Duration::days(1)).timestamp();
        // Check expiration is correct
        assert!((expiration - written_metadata.expires.unwrap()).abs() < 500);
        written_metadata.expires = None;

        let source_metadata =
            Metadata::from_str(&read_to_string(&format!("{}.sign", file)).unwrap()).unwrap();

        // Check uploaded file
        assert_eq!(
            read_to_string(file).unwrap(),
            read_to_string(&format!("{}.source", file)).unwrap()
        );
        // Check metadata file
        assert_eq!(source_metadata, written_metadata);

        // Remove leftover
        remove_file(file).unwrap();
        remove_file(&format!("{}.metadata", file)).unwrap();
    }
}
