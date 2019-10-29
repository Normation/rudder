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
use std::{fs::rename, thread};

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn it_correctly_replies_to_status_api() {
        let cli_cfg = CliConfiguration::new("tests/files/config/", false);
        thread::spawn(move || {
            start(cli_cfg, init_logger().unwrap()).unwrap();
        });
        assert!(common::start_api().is_ok());

        let response: serde_json::Value = serde_json::from_str(
            &reqwest::get("http://localhost:3030/rudder/relay-api/1/system/status")
                .unwrap()
                .text()
                .unwrap(),
        )
        .unwrap();

        let reference: serde_json::Value = serde_json::from_str("{\"data\":{\"database\":{\"status\":\"success\"},\"configuration\":{\"status\":\"success\"}},\"result\":\"success\",\"action\":\"getStatus\"}").unwrap();

        assert_eq!(reference, response);

        // Broken config file

        rename(
            "tests/files/config/main.conf",
            "tests/files/config/main.conf.new",
        )
        .unwrap();

        let response: serde_json::Value = serde_json::from_str(
            &reqwest::get("http://localhost:3030/rudder/relay-api/1/system/status")
                .unwrap()
                .text()
                .unwrap(),
        )
        .unwrap();

        rename(
            "tests/files/config/main.conf.new",
            "tests/files/config/main.conf",
        )
        .unwrap();

        let reference: serde_json::Value = serde_json::from_str("{\"data\":{\"database\":{\"status\":\"success\"},\"configuration\":{\"status\":\"error\", \"details\": \"I/O error: No such file or directory (os error 2)\"}},\"result\":\"success\",\"action\":\"getStatus\"}").unwrap();

        assert_eq!(reference, response);
    }
}
