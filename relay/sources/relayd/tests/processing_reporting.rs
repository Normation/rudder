// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use diesel::{self, prelude::*, PgConnection};
use filetime::{set_file_times, FileTime};
use rudder_relayd::{
    configuration::cli::CliConfiguration,
    data::report::QueryableReport,
    init_logger,
    output::database::schema::{reportsexecution::dsl::*, ruddersysevents::dsl::*},
    start,
};
use std::{
    collections::HashMap,
    fs::{copy, create_dir_all, remove_dir_all},
    path::Path,
    thread, time,
};

pub fn db_connection() -> PgConnection {
    PgConnection::establish("postgres://rudderreports:PASSWORD@127.0.0.1/rudder").unwrap()
}

/// List of key values to check in the metrics text
pub fn check_prometheus(metrics: &str, mut expected: HashMap<&str, &str>) -> bool {
    let mut is_ok = true;

    for line in metrics.split('\n') {
        if line.starts_with('#') || line.is_empty() {
            continue;
        }

        let split: Vec<&str> = line.split(' ').collect();
        let name = split[0];
        let value = split[1];

        if let Some(rvalue) = expected.get(name) {
            if *rvalue != value {
                println!("{} should equal {} but is {}", name, rvalue, value);
                is_ok = false;
            }
            expected.remove(name);
        }
    }

    for key in expected.keys() {
        is_ok = false;
        println!("{} should be present but is not there", key);
    }

    is_ok
}

// Checks number of start execution reports
// (so number of runlogs if everything goes well)
#[allow(clippy::result_unit_err)]
pub fn start_number(db: &PgConnection, expected: usize) -> Result<(), ()> {
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
    diesel::delete(reportsexecution).execute(&db).unwrap();

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
    // We need to file to be old (10 minutes ago)
    let old_time = chrono::Utc::now() - chrono::Duration::minutes(10);
    set_file_times(
        file_old,
        FileTime::from_unix_time(old_time.timestamp(), 0),
        FileTime::from_unix_time(old_time.timestamp(), 0),
    )
    .unwrap();

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

    // Test that watcher works
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

    //thread::sleep(time::Duration::from_secs(500));

    let body = reqwest::blocking::get("http://localhost:3030/metrics")
        .unwrap()
        .text()
        .unwrap();
    let mut expected = HashMap::new();
    expected.insert("rudder_relayd_reports_total{status=\"invalid\"}", "1");
    expected.insert("rudder_relayd_reports_total{status=\"ok\"}", "2");
    expected.insert("rudder_relayd_reports_total{status=\"error\"}", "1");
    expected.insert("rudder_relayd_reports_total{status=\"forward_ok\"}", "0");
    expected.insert("rudder_relayd_reports_total{status=\"forward_error\"}", "0");
    expected.insert(
        "rudder_relayd_inventories_total{status=\"forward_ok\"}",
        "0",
    );
    expected.insert(
        "rudder_relayd_inventories_total{status=\"forward_error\"}",
        "0",
    );
    expected.insert("rudder_relayd_managed_nodes_total", "3");
    expected.insert("rudder_relayd_sub_nodes_total", "6");
    assert!(check_prometheus(&body, expected));
}
