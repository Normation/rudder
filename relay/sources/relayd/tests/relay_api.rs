use relayd::{configuration::cli::CliConfiguration, init_logger, start};
use reqwest;
use std::{fs::read_to_string, thread, time};

fn start_api() -> Result<(), ()> {
    let mut retry = 10;
    while retry > 0 {
        thread::sleep(time::Duration::from_millis(200));
        retry -= 1;

        let resp = reqwest::get("http://localhost:3030/status");

        if resp.is_ok() {
            return Ok(());
        }
    }
    Err(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn it_handles_errors() {
        let cli_cfg = CliConfiguration::new("tests/test_simple/config/", false);

        thread::spawn(move || {
            start(cli_cfg, init_logger().unwrap()).unwrap();
        });

        assert!(start_api().is_ok());

        let client = reqwest::Client::new();

        let params = [
            ("asynchronous", "false"),
            ("keep_output", "true"),
            ("classes", "clas~1,class2,class3"),
            ("nodes", "node2.rudder.local,server.rudder.local"),
        ];

        let res = client
            .post("http://localhost:3030/rudder/relay-api/remote-run/nodes")
            .form(&params)
            .send();

        assert_eq!(res.unwrap().text().unwrap(), "Unhandled rejection: Invalid agent Condition : Invalid agent Condition : Wrong condition: \'clas~1\', it should match ^[a-zA-Z0-9][a-zA-Z0-9_]*$".to_string());
    }

    #[test]
    fn it_processes_the_parameters() {
        let cli_cfg = CliConfiguration::new("tests/test_simple/config/", false);

        thread::spawn(move || {
            start(cli_cfg, init_logger().unwrap()).unwrap();
        });

        assert!(start_api().is_ok());

        let client = reqwest::Client::new();

        let params = [
            ("asynchronous", "false"),
            ("keep_output", "true"),
            ("classes", "class2,class3"),
            ("nodes", "server.rudder.local"),
        ];

        client
            .post("http://localhost:3030/rudder/relay-api/remote-run/nodes")
            .form(&params)
            .send()
            .unwrap();

        thread::sleep(time::Duration::from_millis(500));
        let data = read_to_string("target/tmp/api_test.txt").expect("Unable to read file");

        assert_eq!(
            "remote run -D class2,class3 -H server.rudder.local".to_string(),
            data
        );
    }

    #[test]
    fn it_shares_folder() {
        let cli_cfg = CliConfiguration::new("tests/test_simple/config/", false);

        thread::spawn(move || {
            start(cli_cfg, init_logger().unwrap()).unwrap();
        });

        assert!(start_api().is_ok());

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

    #[test]
    fn it_shares_files() {
        let cli_cfg = CliConfiguration::new("tests/test_simple/config/", false);

        thread::spawn(move || {
            start(cli_cfg, init_logger().unwrap()).unwrap();
        });

        assert!(start_api().is_ok());

        let client = reqwest::Client::new();

        // curl --head http://127.0.0.1:3030/rudder/relay-api/shared-files/c745a140-40bc-4b86-b6dc-084488fc906b/37817c4d-fbf7-4850-a985-50021f4e8f41/file?hash=db3974a97f2407b7cae1ae637c0030687a11913274d578492558e39c16c017de84eacdc8c62fe34ee4e12b4b1428817f09b6a2760c3f8a664ceae94d2434a593

        let hashes_are_equal = client
            .head("http://127.0.0.1:3030/rudder/relay-api/shared-files/c745a140-40bc-4b86-b6dc-084488fc906b/37817c4d-fbf7-4850-a985-50021f4e8f41/file?hash=db3974a97f2407b7cae1ae637c0030687a11913274d578492558e39c16c017de84eacdc8c62fe34ee4e12b4b1428817f09b6a2760c3f8a664ceae94d2434a593")
            .send()
            .unwrap();

        assert_eq!(200, hashes_are_equal.status());

        let hashes_are_not_equal = client
            .head("http://127.0.0.1:3030/rudder/relay-api/shared-files/c745a140-40bc-4b86-b6dc-084488fc906b/37817c4d-fbf7-4850-a985-50021f4e8f41/file?hash=0xcafecafecafe")
            .send()
            .unwrap();

        assert_eq!(404, hashes_are_not_equal.status());

        let no_hash_sent = client
            .head("http://127.0.0.1:3030/rudder/relay-api/shared-files/c745a140-40bc-4b86-b6dc-084488fc906b/37817c4d-fbf7-4850-a985-50021f4e8f41/file?hash=")
            .send()
            .unwrap();

        assert_eq!(404, no_hash_sent.status());
    }
}
