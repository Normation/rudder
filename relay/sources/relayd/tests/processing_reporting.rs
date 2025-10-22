// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

mod common;

use crate::common::random_ports;

use diesel::{self, prelude::*, PgConnection};
use rudder_relayd::{
    configuration::cli::CliConfiguration,
    data::report::QueryableReport,
    init_logger,
    output::database::schema::{reportsexecution::dsl::*, ruddersysevents::dsl::*},
    start,
};
use std::path::PathBuf;
use std::time::Duration;
use std::{
    collections::HashMap,
    fs::{copy, create_dir_all, remove_dir_all},
    path::Path,
    thread, time,
};

pub fn db_connection() -> PgConnection {
    PgConnection::establish("postgres://rudderreports:PASSWORD@postgres/rudder").unwrap()
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
                println!("{name} should equal {rvalue} but is {value}");
                is_ok = false;
            }
            expected.remove(name);
        }
    }

    for key in expected.keys() {
        is_ok = false;
        println!("{key} should be present but is not there");
    }

    is_ok
}

// Checks number of start execution reports
// (so number of runlogs if everything goes well)
#[allow(clippy::result_unit_err)]
pub fn start_number(db: &mut PgConnection, expected: usize) -> Result<(), ()> {
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
    let (api_port, https_port) = random_ports();

    let mut db = db_connection();
    diesel::delete(ruddersysevents).execute(&mut db).unwrap();
    diesel::delete(reportsexecution).execute(&mut db).unwrap();

    assert!(start_number(&mut db, 0).is_ok());

    let _ = remove_dir_all("target/tmp/reporting");
    create_dir_all("target/tmp/reporting/incoming").unwrap();
    let cli_cfg = CliConfiguration::new("tests/files/config/", false);

    let file_old = "target/tmp/reporting/incoming/2017-08-24T15:55:01+00:00@e745a140-40bc-4b86-b6dc-084488fc906b.log";
    let file_new = "target/tmp/reporting/incoming/2018-08-24T15:55:01+00:00@e745a140-40bc-4b86-b6dc-084488fc906b.log";
    let file_broken = "target/tmp/reporting/incoming/2018-02-24T15:55:01+00:00@e745a140-40bc-4b86-b6dc-084488fc906b.log";
    let file_broken_name = "target/tmp/reporting/incoming/bob.log";
    let file_unknown = "target/tmp/reporting/incoming/2018-02-24T15:55:01+00:00@e745a140-40bc-4b86-b6dc-084488fc906d.log";
    let file_broken_failed = "target/tmp/reporting/failed/2018-02-24T15:55:01+00:00@e745a140-40bc-4b86-b6dc-084488fc906b.log";
    let file_unknown_failed = "target/tmp/reporting/failed/2018-02-24T15:55:01+00:00@e745a140-40bc-4b86-b6dc-084488fc906d.log";

    copy(
        "tests/files/runlogs/2017-08-24T15_55_01+00_00@e745a140-40bc-4b86-b6dc-084488fc906b.signed",
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
        start(
            cli_cfg,
            init_logger().unwrap(),
            Some((api_port, https_port)),
        )
        .unwrap();
    });

    assert!(start_number(&mut db, 1).is_ok());

    copy("tests/files/config/main.conf", file_broken).unwrap();
    // Ensure it does not bother processing
    copy("tests/files/config/main.conf", file_broken_name).unwrap();
    copy("tests/files/config/main.conf", file_unknown).unwrap();

    thread::sleep(time::Duration::from_millis(500));
    assert!(start_number(&mut db, 1).is_ok());

    copy(
        "tests/files/runlogs/2018-08-24T15_55_01+00_00@e745a140-40bc-4b86-b6dc-084488fc906b.signed",
        file_new,
    )
    .unwrap();

    // Test that watcher works
    assert!(start_number(&mut db, 2).is_ok());

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

    let body = reqwest::blocking::get(format!("http://localhost:{api_port}/metrics"))
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

use filetime::{set_file_times, FileTime};
use rudder_relayd::{configuration::main::CleanupConfig, input::watch::cleanup};

#[test]
fn it_cleans_old_reports() {
    let _ = remove_dir_all("target/tmp/reporting_old");
    create_dir_all("target/tmp/reporting_old/incoming").unwrap();

    let file_new = "target/tmp/reporting_old/incoming/2019-08-24T15:55:01+00:00@e745a140-40bc-4b86-b6dc-084488fc906b.log";
    let file_very_old = "target/tmp/reporting_old/incoming/1970-08-24T15:55:01+00:00@e745a140-40bc-4b86-b6dc-084488fc906b.log";

    copy(
        "tests/files/runlogs/2017-08-24T15_55_01+00_00@e745a140-40bc-4b86-b6dc-084488fc906b.signed",
        file_new,
    )
    .unwrap();
    copy(
        "tests/files/runlogs/2017-08-24T15_55_01+00_00@e745a140-40bc-4b86-b6dc-084488fc906b.signed",
        file_very_old,
    )
    .unwrap();
    // We need to file to be old, unix epoch is ok
    set_file_times(file_very_old, FileTime::zero(), FileTime::zero()).unwrap();

    thread::spawn(move || {
        let rt = tokio::runtime::Runtime::new().unwrap();

        rt.block_on(cleanup(
            PathBuf::from("target/tmp/reporting_old/incoming"),
            CleanupConfig {
                frequency: Duration::from_secs(1),
                retention: Duration::from_secs(60),
            },
        ))
        .unwrap();
    });

    thread::sleep(Duration::from_millis(500));

    // Old file has been cleaned up
    assert!(Path::new(file_new).exists());
    assert!(!Path::new(file_very_old).exists());
}
