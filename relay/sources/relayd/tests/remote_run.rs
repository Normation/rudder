// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

mod common;

use relayd::{configuration::cli::CliConfiguration, init_logger, start};
use std::{
    fs::{read_to_string, remove_file},
    path::Path,
    process::Command,
    thread, time,
};

fn fake_server_start(id: String) {
    thread::spawn(|| {
        Command::new("tests/server.py")
            .arg(id)
            .spawn()
            .expect("failed to execute process")
    });
    thread::sleep(time::Duration::from_millis(400));

    let client = reqwest::blocking::Client::builder()
        .danger_accept_invalid_certs(true)
        .build()
        .unwrap();
    let response = client.get("https://localhost:4443/uuid").send().unwrap();
    assert_eq!(response.status(), hyper::StatusCode::OK);
}

fn fake_server_stop() {
    let client = reqwest::blocking::Client::builder()
        .danger_accept_invalid_certs(true)
        .build()
        .unwrap();
    let response = client.get("https://localhost:4443/stop").send().unwrap();
    assert_eq!(response.status(), hyper::StatusCode::OK);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn it_runs_remote_run() {
        let cli_cfg = CliConfiguration::new("tests/files/config/", false);

        thread::spawn(move || {
            start(cli_cfg, init_logger().unwrap()).unwrap();
        });

        assert!(common::start_api().is_ok());
        let client = reqwest::blocking::Client::new();

        // Async & keep

        let _ = remove_file("target/tmp/api_test.txt");
        let params_async = [
            ("asynchronous", "true"),
            ("keep_output", "true"),
            ("classes", "class2,class3"),
            ("nodes", "root"),
        ];
        let response = client
            .post("http://localhost:3030/rudder/relay-api/1/remote-run/nodes")
            .form(&params_async)
            .send()
            .unwrap();
        assert_eq!(response.status(), hyper::StatusCode::OK);
        assert_eq!(response.text().unwrap(), "OK\nEND\n".to_string());
        assert_eq!(
            "remote run -D class2,class3 -- server.rudder.local".to_string(),
            read_to_string("target/tmp/api_test.txt").unwrap()
        );

        let _ = remove_file("target/tmp/api_test.txt");
        let params_async = [
            ("asynchronous", "true"),
            ("keep_output", "true"),
            ("classes", "class2,class45"),
            ("nodes", "e745a140-40bc-4b86-b6dc-084488fc906b"),
        ];
        let response = client
            .post("http://localhost:3030/rudder/relay-api/1/remote-run/nodes")
            .form(&params_async)
            .send()
            .unwrap();
        assert_eq!(response.status(), hyper::StatusCode::OK);
        assert_eq!(response.text().unwrap(), "OK\nEND\n".to_string());
        assert_eq!(
            "remote run -D class2,class45 -- node1.rudder.local".to_string(),
            read_to_string("target/tmp/api_test.txt").unwrap()
        );

        let _ = remove_file("target/tmp/api_test.txt");
        let params_async = [
            ("asynchronous", "true"),
            ("keep_output", "true"),
            ("classes", "class2,class46"),
        ];
        let response = client
            .post("http://localhost:3030/rudder/relay-api/1/remote-run/nodes/e745a140-40bc-4b86-b6dc-084488fc906b")
            .form(&params_async)
            .send()
            .unwrap();
        assert_eq!(response.status(), hyper::StatusCode::OK);
        assert_eq!(response.text().unwrap(), "OK\nEND\n".to_string());
        assert_eq!(
            "remote run -D class2,class46 -- node1.rudder.local".to_string(),
            read_to_string("target/tmp/api_test.txt").unwrap()
        );

        // Async & no keep

        let _ = remove_file("target/tmp/api_test.txt");
        let params_sync = [
            ("asynchronous", "true"),
            ("keep_output", "false"),
            ("classes", "class2,class4"),
            ("nodes", "root"),
        ];
        let response = client
            .post("http://localhost:3030/rudder/relay-api/1/remote-run/nodes")
            .form(&params_sync)
            .send()
            .unwrap();
        assert_eq!(response.status(), hyper::StatusCode::OK);
        assert_eq!(response.text().unwrap(), "".to_string());
        // async, let's wait a bit
        thread::sleep(time::Duration::from_millis(700));
        assert_eq!(
            "remote run -D class2,class4 -- server.rudder.local".to_string(),
            read_to_string("target/tmp/api_test.txt").unwrap()
        );

        // Sync & keep

        let _ = remove_file("target/tmp/api_test.txt");
        let params_sync = [
            ("asynchronous", "false"),
            ("keep_output", "true"),
            ("classes", "class2,class5"),
            ("nodes", "root"),
        ];
        let response = client
            .post("http://localhost:3030/rudder/relay-api/1/remote-run/nodes")
            .form(&params_sync)
            .send()
            .unwrap();
        assert_eq!(response.status(), hyper::StatusCode::OK);
        assert_eq!(response.text().unwrap(), "OK\nEND\n".to_string());
        assert_eq!(
            "remote run -D class2,class5 -- server.rudder.local".to_string(),
            read_to_string("target/tmp/api_test.txt").unwrap()
        );

        // Sync & keep with sub-relay

        let _ = remove_file("target/tmp/api_test.txt");
        let _ = remove_file("target/tmp/api_test_remote.txt");
        let params_sync = [
            ("asynchronous", "false"),
            ("keep_output", "true"),
            ("classes", "class2,class5"),
            ("nodes", "root,c745a140-40bc-4b86-b6dc-084488fc906b"),
        ];

        // Wrong certificate
        fake_server_start("e745a140-40bc-4b86-b6dc-084488fc906b".to_string());
        let response = client
            .post("http://localhost:3030/rudder/relay-api/1/remote-run/nodes")
            .form(&params_sync)
            .send()
            .unwrap();
        fake_server_stop();
        assert_eq!(response.status(), hyper::StatusCode::OK);

        // Good certificate
        fake_server_start("37817c4d-fbf7-4850-a985-50021f4e8f41".to_string());
        let response = client
            .post("http://localhost:3030/rudder/relay-api/1/remote-run/nodes")
            .form(&params_sync)
            .send()
            .unwrap();
        fake_server_stop();

        assert_eq!(response.status(), hyper::StatusCode::OK);
        assert_eq!(response.text().unwrap(), "OK\nEND\nREMOTE\n".to_string());
        assert_eq!(
            "remote run -D class2,class5 -- server.rudder.local".to_string(),
            read_to_string("target/tmp/api_test.txt").unwrap()
        );
        assert!(Path::new("target/tmp/api_test_remote.txt").exists());

        // Sync & no keep

        let _ = remove_file("target/tmp/api_test.txt");
        let params_sync = [
            ("asynchronous", "false"),
            ("keep_output", "false"),
            ("classes", "class2,class6"),
            ("nodes", "root"),
        ];
        let response = client
            .post("http://localhost:3030/rudder/relay-api/1/remote-run/nodes")
            .form(&params_sync)
            .send()
            .unwrap();
        assert_eq!(response.status(), hyper::StatusCode::OK);
        assert_eq!(response.text().unwrap(), "".to_string());
        assert_eq!(
            "remote run -D class2,class6 -- server.rudder.local".to_string(),
            read_to_string("target/tmp/api_test.txt").unwrap()
        );

        // Failure

        let params = [
            ("asynchronous", "false"),
            ("keep_output", "true"),
            ("classes", "class~1,class2,class3"),
            ("nodes", "node2.rudder.local,server.rudder.local"),
        ];
        let response = client
            .post("http://localhost:3030/rudder/relay-api/1/remote-run/nodes")
            .form(&params)
            .send()
            .unwrap();

        assert_eq!(response.status(), hyper::StatusCode::BAD_REQUEST);
        assert_eq!(
            response.text().unwrap(),
            "invalid condition: class~1, should match ^[a-zA-Z0-9][a-zA-Z0-9_]*$".to_string()
        );
    }
}
