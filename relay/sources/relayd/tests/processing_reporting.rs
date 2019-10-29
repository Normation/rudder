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

use diesel::{self, prelude::*, PgConnection};
use filetime::{set_file_times, FileTime};
use relayd::{
    configuration::cli::CliConfiguration, data::report::QueryableReport, init_logger,
    output::database::schema::ruddersysevents::dsl::*, start, stats::Stats,
};
use reqwest;
use serde_json;
use std::{
    fs::{copy, create_dir_all, remove_dir_all},
    path::Path,
    thread, time,
};

fn db_connection() -> PgConnection {
    PgConnection::establish("postgres://rudderreports:PASSWORD@127.0.0.1/rudder").unwrap()
}

// Checks number of start execution reports
// (so number of runlogs if everything goes well)
fn start_number(db: &PgConnection, expected: usize) -> Result<(), ()> {
    let mut retry = 10;
    while retry > 0 {
        thread::sleep(time::Duration::from_millis(200));
        retry -= 1;
        let results = ruddersysevents
            .filter(component.eq("start"))
            .limit(10)
            .load::<QueryableReport>(db)
            .unwrap();
        if results.len() == expected {
            return Ok(());
        }
    }
    Err(())
}

#[test]
fn it_reads_and_inserts_a_runlog() {
    let db = db_connection();
    diesel::delete(ruddersysevents).execute(&db).unwrap();

    assert!(start_number(&db, 0).is_ok());

    let _ = remove_dir_all("target/tmp/reporting");
    create_dir_all("target/tmp/reporting/incoming").unwrap();
    let cli_cfg = CliConfiguration::new("tests/files/config/", false);

    let file_old = "target/tmp/reporting/incoming/2017-08-24T15:55:01+00:00@e745a140-40bc-4b86-b6dc-084488fc906b.log";
    let file_new = "target/tmp/reporting/incoming/2018-08-24T15:55:01+00:00@e745a140-40bc-4b86-b6dc-084488fc906b.log";
    let file_broken = "target/tmp/reporting/incoming/2018-02-24T15:55:01+00:00@e745a140-40bc-4b86-b6dc-084488fc906b.log";
    let file_unknown = "target/tmp/reporting/incoming/2018-02-24T15:55:01+00:00@e745a140-40bc-4b86-b6dc-084488fc906d.log";
    let file_broken_failed = "target/tmp/reporting/failed/2018-02-24T15:55:01+00:00@e745a140-40bc-4b86-b6dc-084488fc906b.log";
    let file_unknown_failed = "target/tmp/reporting/failed/2018-02-24T15:55:01+00:00@e745a140-40bc-4b86-b6dc-084488fc906d.log";

    copy(
        "tests/files/runlogs/2017-08-24T15:55:01+00:00@e745a140-40bc-4b86-b6dc-084488fc906b.signed",
        file_old,
    )
    .unwrap();
    // We need to file to be old
    set_file_times(file_old, FileTime::zero(), FileTime::zero()).unwrap();

    thread::spawn(move || {
        start(cli_cfg, init_logger().unwrap()).unwrap();
    });

    assert!(start_number(&db, 1).is_ok());

    copy("tests/files/config/main.conf", file_broken).unwrap();
    copy("tests/files/config/main.conf", file_unknown).unwrap();

    thread::sleep(time::Duration::from_millis(500));
    assert!(start_number(&db, 1).is_ok());

    copy(
        "tests/files/runlogs/2018-08-24T15:55:01+00:00@e745a140-40bc-4b86-b6dc-084488fc906b.signed",
        file_new,
    )
    .unwrap();

    assert!(start_number(&db, 2).is_ok());

    // Test files have been removed
    assert!(!Path::new(file_old).exists());
    assert!(!Path::new(file_new).exists());
    // Test broken file has been moved
    assert!(!Path::new(file_broken).exists());
    assert!(Path::new(file_broken_failed).exists());
    // Test unknown file has been moved
    assert!(!Path::new(file_unknown).exists());
    assert!(Path::new(file_unknown_failed).exists());

    let body = reqwest::get("http://localhost:3030/rudder/relay-api/1/system/stats")
        .unwrap()
        .text()
        .unwrap();
    let answer = serde_json::from_str(&body).unwrap();
    let reference = Stats {
        report_received: 4,
        report_refused: 2,
        report_sent: 0,
        report_inserted: 2,
        inventory_received: 0,
        inventory_refused: 0,
        inventory_sent: 0,
    };
    assert_eq!(reference, answer);
}
