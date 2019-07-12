use diesel::{self, prelude::*, PgConnection};
use filetime::{set_file_times, FileTime};
use relayd::{
    configuration::CliConfiguration, data::report::QueryableReport, init_logger,
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

    let _ = remove_dir_all("target/tmp/test_simple");
    create_dir_all("target/tmp/test_simple/incoming").unwrap();
    let cli_cfg = CliConfiguration::new("tests/test_simple/config/", false);

    let file_old = "target/tmp/test_simple/incoming/2017-08-24T15:55:01+00:00@e745a140-40bc-4b86-b6dc-084488fc906b.log";
    let file_new = "target/tmp/test_simple/incoming/2018-08-24T15:55:01+00:00@e745a140-40bc-4b86-b6dc-084488fc906b.log";
    let file_broken = "target/tmp/test_simple/incoming/2018-02-24T15:55:01+00:00@e745a140-40bc-4b86-b6dc-084488fc906b.log";
    let file_failed = "target/tmp/test_simple/failed/2018-02-24T15:55:01+00:00@e745a140-40bc-4b86-b6dc-084488fc906b.log";

    copy(
        "tests/runlogs/2017-08-24T15:55:01+00:00@e745a140-40bc-4b86-b6dc-084488fc906b.signed",
        file_old,
    )
    .unwrap();
    // We need to file to be old
    set_file_times(file_old, FileTime::zero(), FileTime::zero()).unwrap();

    thread::spawn(move || {
        start(cli_cfg, init_logger().unwrap()).unwrap();
    });

    assert!(start_number(&db, 1).is_ok());

    copy(
        "tests/runlogs/2018-08-24T15:55:01+00:00@e745a140-40bc-4b86-b6dc-084488fc906b.signed",
        file_new,
    )
    .unwrap();
    copy("tests/files/config/main.conf", file_broken).unwrap();

    assert!(start_number(&db, 2).is_ok());

    // Test files have been removed
    assert!(!Path::new(file_old).exists());
    assert!(!Path::new(file_new).exists());
    // Test broken file has been moved
    assert!(!Path::new(file_broken).exists());
    assert!(Path::new(file_failed).exists());

    let body = reqwest::get("http://localhost:3030/stats")
        .unwrap()
        .text()
        .unwrap();
    let answer = serde_json::from_str(&body).unwrap();
    let reference = Stats {
        report_received: 3,
        report_refused: 1,
        report_sent: 0,
        report_inserted: 2,
        inventory_received: 0,
        inventory_refused: 0,
        inventory_sent: 0,
    };
    assert_eq!(reference, answer);
}
