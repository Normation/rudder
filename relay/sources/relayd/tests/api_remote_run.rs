// Copyright 2019 Normation SAS
//
// This file is part of Rudder.
//
// Rudder is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// In accordance with the terms of section 7 (7. Additional Terms.) of
// the GNU General Public License version 3, the copyright holders add
// the following Additional permissions:
// Notwithstanding to the terms of section 5 (5. Conveying Modified Source
// Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
// Public License version 3, when you create a Related Module, this
// Related Module is not considered as a part of the work and may be
// distributed under the license agreement of your choice.
// A "Related Module" means a set of sources files including their
// documentation that, without modification of the Source Code, enables
// supplementary functions or services in addition to those offered by
// the Software.
//
// Rudder is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

mod common;

use relayd::{configuration::cli::CliConfiguration, init_logger, start};
use reqwest;
use std::{
    fs::{read_to_string, remove_file},
    thread, time,
};

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn it_runs_local_remote_run() {
        let cli_cfg = CliConfiguration::new("tests/files/config/", false);

        thread::spawn(move || {
            start(cli_cfg, init_logger().unwrap()).unwrap();
        });

        assert!(common::start_api().is_ok());
        let client = reqwest::Client::new();

        // Async & keep

        let _ = remove_file("target/tmp/api_test.txt");
        let params_async = [
            ("asynchronous", "true"),
            ("keep_output", "true"),
            ("classes", "class2,class3"),
            ("nodes", "root"),
        ];
        let mut response = client
            .post("http://localhost:3030/rudder/relay-api/1/remote-run/nodes")
            .form(&params_async)
            .send()
            .unwrap();
        assert_eq!(response.status(), hyper::StatusCode::OK);
        assert_eq!(response.text().unwrap(), "OK\nEND\n".to_string());
        assert_eq!(
            "remote run -D class2,class3 server.rudder.local".to_string(),
            read_to_string("target/tmp/api_test.txt").unwrap()
        );

        let _ = remove_file("target/tmp/api_test.txt");
        let params_async = [
            ("asynchronous", "true"),
            ("keep_output", "true"),
            ("classes", "class2,class45"),
            ("nodes", "e745a140-40bc-4b86-b6dc-084488fc906b"),
        ];
        let mut response = client
            .post("http://localhost:3030/rudder/relay-api/1/remote-run/nodes")
            .form(&params_async)
            .send()
            .unwrap();
        assert_eq!(response.status(), hyper::StatusCode::OK);
        assert_eq!(response.text().unwrap(), "OK\nEND\n".to_string());
        assert_eq!(
            "remote run -D class2,class45 node1.rudder.local".to_string(),
            read_to_string("target/tmp/api_test.txt").unwrap()
        );

        let _ = remove_file("target/tmp/api_test.txt");
        let params_async = [
            ("asynchronous", "true"),
            ("keep_output", "true"),
            ("classes", "class2,class46"),
        ];
        let mut response = client
            .post("http://localhost:3030/rudder/relay-api/1/remote-run/nodes/e745a140-40bc-4b86-b6dc-084488fc906b")
            .form(&params_async)
            .send()
            .unwrap();
        assert_eq!(response.status(), hyper::StatusCode::OK);
        assert_eq!(response.text().unwrap(), "OK\nEND\n".to_string());
        assert_eq!(
            "remote run -D class2,class46 node1.rudder.local".to_string(),
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
        let mut response = client
            .post("http://localhost:3030/rudder/relay-api/1/remote-run/nodes")
            .form(&params_sync)
            .send()
            .unwrap();
        assert_eq!(response.status(), hyper::StatusCode::OK);
        assert_eq!(response.text().unwrap(), "".to_string());
        // async, let's wait a bit
        thread::sleep(time::Duration::from_millis(700));
        assert_eq!(
            "remote run -D class2,class4 server.rudder.local".to_string(),
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
        let mut response = client
            .post("http://localhost:3030/rudder/relay-api/1/remote-run/nodes")
            .form(&params_sync)
            .send()
            .unwrap();
        assert_eq!(response.status(), hyper::StatusCode::OK);
        assert_eq!(response.text().unwrap(), "OK\nEND\n".to_string());
        assert_eq!(
            "remote run -D class2,class5 server.rudder.local".to_string(),
            read_to_string("target/tmp/api_test.txt").unwrap()
        );

        // Sync & no keep

        let _ = remove_file("target/tmp/api_test.txt");
        let params_sync = [
            ("asynchronous", "false"),
            ("keep_output", "false"),
            ("classes", "class2,class6"),
            ("nodes", "root"),
        ];
        let mut response = client
            .post("http://localhost:3030/rudder/relay-api/1/remote-run/nodes")
            .form(&params_sync)
            .send()
            .unwrap();
        assert_eq!(response.status(), hyper::StatusCode::OK);
        assert_eq!(response.text().unwrap(), "".to_string());
        assert_eq!(
            "remote run -D class2,class6 server.rudder.local".to_string(),
            read_to_string("target/tmp/api_test.txt").unwrap()
        );

        // Failure

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
