use relayd::{configuration::cli::CliConfiguration, init_logger, start};
use reqwest;
use std::{
    fs::{read_to_string, remove_file},
    thread, time,
};
mod common;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn it_runs_local_remote_run() {
        let cli_cfg = CliConfiguration::new("tests/test_simple/config/", false);

        thread::spawn(move || {
            start(cli_cfg, init_logger().unwrap()).unwrap();
        });

        assert!(common::start_api().is_ok());
        let client = reqwest::Client::new();

        let _ = remove_file("target/tmp/api_test.txt");
        let params_async = [
            ("asynchronous", "true"),
            ("keep_output", "false"),
            ("classes", "class2,class3"),
            ("nodes", "root"),
        ];
        let mut response = client
            .post("http://localhost:3030/rudder/relay-api/1/remote-run/nodes")
            .form(&params_async)
            .send()
            .unwrap();
        assert_eq!(response.status(), hyper::StatusCode::OK);
        assert_eq!(response.text().unwrap(), "".to_string());
        // async, let's wait a bit
        thread::sleep(time::Duration::from_millis(700));
        assert_eq!(
            "remote run -D class2,class3 server.rudder.local".to_string(),
            read_to_string("target/tmp/api_test.txt").unwrap()
        );

        let _ = remove_file("target/tmp/api_test.txt");
        let params_sync = [
            ("asynchronous", "true"),
            ("keep_output", "true"),
            ("classes", "class2,class4"),
            ("nodes", "root"),
        ];
        let mut response = client
            .post("http://localhost:3030/rudder/relay-api/1/remote-run/nodes")
            .form(&params_sync)
            .send()
            .unwrap();
        assert_eq!(response.status(), hyper::StatusCode::OK);
        assert_eq!(response.text().unwrap(), "OK".to_string());
        assert_eq!(
            "remote run -D class2,class4 server.rudder.local".to_string(),
            read_to_string("target/tmp/api_test.txt").unwrap()
        );
    }
}
