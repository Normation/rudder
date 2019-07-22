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
}
