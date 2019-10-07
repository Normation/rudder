use relayd::{configuration::cli::CliConfiguration, init_logger, start};
use reqwest;
use std::thread;

mod common;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn it_shares_files() {
        let cli_cfg = CliConfiguration::new("tests/test_simple/config/", false);

        thread::spawn(move || {
            start(cli_cfg, init_logger().unwrap()).unwrap();
        });

        assert!(common::start_api().is_ok());

        let client = reqwest::Client::new();

        // curl --head http://127.0.0.1:3030/rudder/relay-api/shared-files/c745a140-40bc-4b86-b6dc-084488fc906b/37817c4d-fbf7-4850-a985-50021f4e8f41/file?hash=db3974a97f2407b7cae1ae637c0030687a11913274d578492558e39c16c017de84eacdc8c62fe34ee4e12b4b1428817f09b6a2760c3f8a664ceae94d2434a593

        let hashes_are_equal = client
            .head("http://127.0.0.1:3030/rudder/relay-api/1/shared-files/c745a140-40bc-4b86-b6dc-084488fc906b/37817c4d-fbf7-4850-a985-50021f4e8f41/file?hash=db3974a97f2407b7cae1ae637c0030687a11913274d578492558e39c16c017de84eacdc8c62fe34ee4e12b4b1428817f09b6a2760c3f8a664ceae94d2434a593")
            .send()
            .unwrap();

        assert_eq!(200, hashes_are_equal.status());

        let hashes_are_not_equal = client
            .head("http://127.0.0.1:3030/rudder/relay-api/1/shared-files/c745a140-40bc-4b86-b6dc-084488fc906b/37817c4d-fbf7-4850-a985-50021f4e8f41/file?hash=0xcafecafecafe")
            .send()
            .unwrap();

        assert_eq!(404, hashes_are_not_equal.status());

        let no_hash_sent = client
            .head("http://127.0.0.1:3030/rudder/relay-api/1/shared-files/c745a140-40bc-4b86-b6dc-084488fc906b/37817c4d-fbf7-4850-a985-50021f4e8f41/file?hash=")
            .send()
            .unwrap();

        assert_eq!(404, no_hash_sent.status());
    }
}
