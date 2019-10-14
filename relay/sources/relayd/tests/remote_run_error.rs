use relayd::{configuration::cli::CliConfiguration, init_logger, start};
use reqwest;
use std::thread;

mod common;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn it_handles_errors() {
        let cli_cfg = CliConfiguration::new("tests/test_simple/config/", false);

        thread::spawn(move || {
            start(cli_cfg, init_logger().unwrap()).unwrap();
        });

        assert!(common::start_api().is_ok());

        let client = reqwest::Client::new();

        let params = [
            ("asynchronous", "false"),
            ("keep_output", "true"),
            ("classes", "clas~1,class2,class3"),
            ("nodes", "node2.rudder.local,server.rudder.local"),
        ];

        let res = client
            .post("http://localhost:3030/rudder/relay-api/1/remote-run/nodes")
            .form(&params)
            .send();

        assert_eq!(res.unwrap().text().unwrap(), "Unhandled rejection: invalid condition: clas~1, should match ^[a-zA-Z0-9][a-zA-Z0-9_]*$".to_string());
    }
}
