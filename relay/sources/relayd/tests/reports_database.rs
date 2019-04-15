use diesel::{self, prelude::*, PgConnection};
use filetime::{set_file_times, FileTime};
use relayd::{
    configuration::CliConfiguration, data::report::QueryableReport, init,
    output::database::schema::ruddersysevents::dsl::*,
};
use std::{
    fs::{copy, create_dir_all, remove_dir_all, File},
    thread, time,
};

pub fn db_connection() -> PgConnection {
    PgConnection::establish("postgres://rudderreports:PASSWORD@127.0.0.1/rudder").unwrap()
}

#[test]
fn it_reads_and_inserts_a_runlog() {
    let db = db_connection();
    diesel::delete(ruddersysevents).execute(&db).unwrap();
    let results = ruddersysevents
        .limit(1)
        .load::<QueryableReport>(&db)
        .unwrap();
    assert_eq!(results.len(), 0);

    let _ = remove_dir_all("target/tmp/test_simple");
    create_dir_all("target/tmp/test_simple/incoming").unwrap();
    let cli_cfg = CliConfiguration::new("tests/test_simple/relayd.conf", false);

    let file_old = "target/tmp/test_simple/incoming/2017-01-24T15:55:01+00:00@root.log";
    let file_new = "target/tmp/test_simple/incoming/2018-01-24T15:55:01+00:00@root.log";
    let file_broken = "target/tmp/test_simple/incoming/2018-02-24T15:55:01+00:00@root.log";
    let file_failed = "target/tmp/test_simple/failed/2018-02-24T15:55:01+00:00@root.log";

    copy("tests/runlogs/normal_old.log", file_old).unwrap();
    // We need to file to be old
    set_file_times(file_old, FileTime::zero(), FileTime::zero()).unwrap();

    thread::spawn(move || {
        init(&cli_cfg).unwrap();
    });
    thread::sleep(time::Duration::from_millis(200));

    let results = ruddersysevents
        .filter(component.eq("start"))
        .limit(3)
        .load::<QueryableReport>(&db)
        .unwrap();
    assert_eq!(results.len(), 1);

    copy("tests/runlogs/normal.log", file_new).unwrap();
    copy("tests/files/relayd.toml", file_broken).unwrap();
    thread::sleep(time::Duration::from_millis(200));

    let results = ruddersysevents
        .filter(component.eq("start"))
        .limit(3)
        .load::<QueryableReport>(&db)
        .unwrap();
    assert_eq!(results.len(), 2);

    // Test files have been removed
    assert!(File::open(file_old).is_err());
    assert!(File::open(file_new).is_err());
    // Test broken file has been moved
    assert!(File::open(file_broken).is_err());
    assert!(File::open(file_failed).is_ok());

    // TODO check stats api
}
