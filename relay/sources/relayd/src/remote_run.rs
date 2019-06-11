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

use crate::error::Error;
use regex::Regex;
use std::collections::HashMap;
use std::process::{Command};
use std::str::FromStr;

#[derive(Debug)]
pub struct Agent {
    asynchronous: bool,
    keep_output: bool,
    condition: Vec<Condition>,
    nodes: Vec<String>,
}

#[derive(Debug)]
pub struct Condition {
    data: String,
}

impl FromStr for Condition {
    type Err = Error;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        let re = Regex::new(r"^[a-zA-Z0-9][a-zA-Z0-9_]*$").unwrap();

        if !re.is_match(s) {
            let mystring = format!("Wrong condition: {} Your condition should match this regex : ^[a-zA-Z0-9][a-zA-Z0-9_]*$", s);
            Err(Error::InvalidCondition(mystring.to_string()))
        } else {
            Ok(Condition {
                data: s.to_string(),
            })
        }
    }
}

impl Agent {
    pub fn execute_agent(&self) -> Result<String, Error> {
        let output = Command::new("sh")
            .args(self.command_line().iter())
            .output()?;
        Ok(String::from_utf8(output.stdout)?)
    }

    pub fn command_line(&self) -> Vec<String> {
        let mut remote_run_command = vec![
            "-c".to_string(),
            "sudo".to_string(),
            "/opt/rudder/bin/rudder".to_string(),
            "remote".to_string(),
            "run".to_string(),
        ];
        // let LOCAL_RUN_COMMAND =
        //     "sudo /opt/rudder/bin/rudder agent run > /dev/null 2>&1".to_string(); // les mettre dans un vec<str>

        if !&self.condition.is_empty() {
            remote_run_command.push("-D".to_string());
            let conditions: Vec<String> = self.condition.iter().map(|s| s.data.clone()).collect();
            let myconditions = conditions.join(",");
            remote_run_command.push(myconditions.to_string());
            remote_run_command
        } else {
            remote_run_command
        }
    }
}

pub fn nodes_handle(simple_map: &HashMap<String, String>) -> Result<Agent, Error> {
    let nodes_vector: Vec<String> = simple_map
        .get("nodes")
        .map(|s| s.split(',').map(|s| s.to_string()).collect())
        .unwrap_or_else(|| vec![]);

    let conditions_vector: Result<Vec<Condition>, Error> = simple_map
        .get("conditions")
        .unwrap_or(&"".to_string())
        .split(',')
        .map(|s| Condition::from_str(s))
        .collect();

    let conditions_vector = conditions_vector?;

    let asynchronous_bool = simple_map
        .get("asynchronous")
        .unwrap_or(&"false".to_string())
        .parse::<bool>()?;

    let keep_output_bool = simple_map
        .get("keep_output")
        .unwrap_or(&"false".to_string())
        .parse::<bool>()?;

    let my_agent = Agent {
        asynchronous: asynchronous_bool,
        keep_output: keep_output_bool,
        condition: conditions_vector,
        nodes: nodes_vector,
    };

    Ok(my_agent)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn it_handles_command_injection() {
        let condition1 = Condition::from_str("class1").unwrap();

        let condition2 = Condition::from_str("class2").unwrap();

        let condition3 = Condition::from_str("class3").unwrap();

        let condition4 = Condition::from_str("class4").unwrap();

        assert!(Condition::from_str("cl&$$y").is_err());

        let condition_vector: Vec<Condition> = vec![condition1, condition2, condition3];

        let my_agent = Agent {
            asynchronous: true,
            keep_output: false,
            condition: condition_vector,
            nodes: vec![
                "node7_uuid".to_string(),
                "node97".to_string(),
                "node5".to_string(),
            ],
        };
    }
}
