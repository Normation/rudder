use relayd::{configuration::cli::CliConfiguration, init_logger, start};
use reqwest;
use std::thread;
mod common;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn it_correctly_replies_to_info_api() {
        let cli_cfg = CliConfiguration::new("tests/test_simple/config/", false);
        thread::spawn(move || {
            start(cli_cfg, init_logger().unwrap()).unwrap();
        });
        assert!(common::start_api().is_ok());

        let response: serde_json::Value = serde_json::from_str(
            &reqwest::get("http://localhost:3030/rudder/relay-api/1/system/info")
                .unwrap()
                .text()
                .unwrap(),
        )
        .unwrap();

        let reference: serde_json::Value = serde_json::from_str("{\"data\":{\"major-version\":\"0.0\",\"full-version\":\"0.0.0-dev\"},\"result\":\"success\",\"action\":\"getSystemInfo\"}").unwrap();

        assert_eq!(reference, response);
    }
}
