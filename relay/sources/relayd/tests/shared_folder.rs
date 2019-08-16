use relayd::{configuration::cli::CliConfiguration, init_logger, start};
use reqwest;
use std::thread;

mod common;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn it_shares_folder() {
        let cli_cfg = CliConfiguration::new("tests/test_simple/config/", false);

        thread::spawn(move || {
            start(cli_cfg, init_logger().unwrap()).unwrap();
        });

        assert!(common::start_api().is_ok());

        let client = reqwest::Client::new();

        // curl --head http://127.0.0.1:3030/rudder/relay-api/shared-folder/c745a140-40bc-4b86-b6dc-084488fc906b/37817c4d-fbf7-4850-a985-50021f4e8f41/file?hash_type=sha256?hash=181210f8f9c779c26da1d9b2075bde0127302ee0e3fca38c9a83f5b1dd8e5d3b

        let hashes_are_equal = client
            .head("http://127.0.0.1:3030/rudder/relay-api/shared-folder/c745a140-40bc-4b86-b6dc-084488fc906b/37817c4d-fbf7-4850-a985-50021f4e8f41/file?hash_type=sha256?hash=181210f8f9c779c26da1d9b2075bde0127302ee0e3fca38c9a83f5b1dd8e5d3b")
            .send()
            .unwrap();

        assert_eq!(304, hashes_are_equal.status());

        let hashes_are_not_equal = client
            .head("http://127.0.0.1:3030/rudder/relay-api/shared-folder/c745a140-40bc-4b86-b6dc-084488fc906b/37817c4d-fbf7-4850-a985-50021f4e8f41/file?hash_type=sha256?hash=cafebabe0xdeadbeef")
            .send()
            .unwrap();

        assert_eq!(200, hashes_are_not_equal.status());

        let no_hash_sent = client
            .head("http://127.0.0.1:3030/rudder/relay-api/shared-folder/c745a140-40bc-4b86-b6dc-084488fc906b/37817c4d-fbf7-4850-a985-50021f4e8f41/file?hash_type=sha256?hash=")
            .send()
            .unwrap();

        assert_eq!(200, no_hash_sent.status());

        let wrong_path = client
            .head("http://127.0.0.1:3030/rudder/relay-api/shared-folder/wrong-key-hash/37817c4d-fbf7-4850-a985-50021f4e8f41/file?hash_type=sha256?hash=181210f8f9c779c26da1d9b2075bde0127302ee0e3fca38c9a83f5b1dd8e5d3b")
            .send()
            .unwrap();

        assert_eq!(404, wrong_path.status());

        let internal_error = client
            .head("http://127.0.0.1:3030/rudder/relay-api/shared-folder/c745a140-40bc-4b86-b6dc-084488fc906b/37817c4d-fbf7-4850-a985-50021f4e8f41/file?hash_type=wrong-hash-type?hash=181210f8f9c779c26da1d9b2075bde0127302ee0e3fca38c9a83f5b1dd8e5d3b")
            .send()
            .unwrap();

        assert_eq!(500, internal_error.status());
    }
}
