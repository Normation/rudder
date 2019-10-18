use relayd::{configuration::cli::CliConfiguration, init_logger, start};
use reqwest;
use std::thread;
mod common;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn it_correctly_replies_to_reload_api() {
        let cli_cfg = CliConfiguration::new("tests/test_simple/config/", false);
        thread::spawn(move || {
            start(cli_cfg, init_logger().unwrap()).unwrap();
        });
        assert!(common::start_api().is_ok());

        let client = reqwest::Client::new();
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
}
