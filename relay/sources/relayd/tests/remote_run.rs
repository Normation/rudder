use relayd::{configuration::cli::CliConfiguration, init_logger, start};
use reqwest;
use std::{fs::read_to_string, thread, time};
mod common;

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn it_processes_the_parameters() {
        let cli_cfg = CliConfiguration::new("tests/test_simple/config/", false);

        thread::spawn(move || {
            start(cli_cfg, init_logger().unwrap()).unwrap();
        });

        assert!(common::start_api().is_ok());

        let client = reqwest::Client::new();

        let params = [
            ("asynchronous", "false"),
            ("keep_output", "true"),
            ("classes", "class2,class3"),
            ("nodes", "root"),
        ];

        client
            .post("http://localhost:3030/rudder/relay-api/remote-run/nodes")
            .form(&params)
            .send()
            .unwrap();

        thread::sleep(time::Duration::from_millis(500));
        let data = read_to_string("target/tmp/api_test.txt").expect("Unable to read file");

        assert_eq!(
            "remote run -D class2,class3 server.rudder.local".to_string(),
            data
        );
    }
}
