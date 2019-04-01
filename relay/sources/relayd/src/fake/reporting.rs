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

use crate::data::{
    nodes::NodeId,
    reporting::{Report, RunInfo, RunLog},
};
use chrono::DateTime;
use rand::{self, Rng};
use uuid::Uuid;

pub fn runlog(node_id: Option<NodeId>) -> RunLog {
    let mut rng = rand::thread_rng();

    // TODO root
    let node_id = node_id.unwrap_or_else(|| Uuid::new_v4().to_string());
    let timestamp =
        DateTime::parse_from_str("2018-08-24 15:55:01+00:00", "%Y-%m-%d %H:%M:%S%z").unwrap();

    let mut reports = vec![];

    let begin = Report {
        start_datetime: timestamp,
        rule_id: "rudder".to_string(),
        directive_id: "run".to_string(),
        component: "start".to_string(),
        key_value: "20180824-130007-3ad37587".to_string(),
        event_type: "control".to_string(),
        msg: "Start execution".to_string(),
        policy: "Common".to_string(),
        node_id: node_id.clone(),
        execution_datetime: timestamp,
        serial: 0,
    };
    let end = Report {
        start_datetime: timestamp,
        rule_id: "rudder".to_string(),
        directive_id: "run".to_string(),
        component: "end".to_string(),
        key_value: "20180824-130007-3ad37587".to_string(),
        event_type: "control".to_string(),
        msg: "End execution".to_string(),
        policy: "Common".to_string(),
        node_id: node_id.clone(),
        execution_datetime: timestamp,
        serial: 0,
    };

    reports.push(begin);

    for _i in 0..rng.gen_range(5, 2000) {
        reports.push(Report {
            start_datetime: timestamp,
            rule_id: Uuid::new_v4().to_string(),
            directive_id: Uuid::new_v4().to_string(),
            component: "test".to_string(),
            key_value: "test".to_string(),
            event_type: "result_repaired".to_string(),
            msg: "test".to_string(),
            policy: "rule".to_string(),
            node_id: node_id.clone(),
            execution_datetime: timestamp,
            serial: 0,
        });
    }

    reports.push(end);

    RunLog {
        info: RunInfo { node_id, timestamp },
        reports,
    }
}
