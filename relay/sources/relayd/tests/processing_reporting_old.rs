// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

mod processing_reporting;

use filetime::{set_file_times, FileTime};
use relayd::{configuration::main::CleanupConfig, input::watch::cleanup};
use std::{
    fs::{copy, create_dir_all, remove_dir_all},
    path::{Path, PathBuf},
    thread,
    time::{self, Duration},
};

#[test]
fn it_cleans_old_reports() {
    let _ = remove_dir_all("target/tmp/reporting_old");
    create_dir_all("target/tmp/reporting_old/incoming").unwrap();

    let file_new = "target/tmp/reporting_old/incoming/2019-08-24T15:55:01+00:00@e745a140-40bc-4b86-b6dc-084488fc906b.log";
    let file_very_old = "target/tmp/reporting_old/incoming/1970-08-24T15:55:01+00:00@e745a140-40bc-4b86-b6dc-084488fc906b.log";

    copy(
        "tests/files/runlogs/2017-08-24T15:55:01+00:00@e745a140-40bc-4b86-b6dc-084488fc906b.signed",
        file_new,
    )
    .unwrap();
    copy(
        "tests/files/runlogs/2017-08-24T15:55:01+00:00@e745a140-40bc-4b86-b6dc-084488fc906b.signed",
        file_very_old,
    )
    .unwrap();
    // We need to file to be old, unix epoch is ok
    set_file_times(file_very_old, FileTime::zero(), FileTime::zero()).unwrap();

    thread::spawn(move || {
        tokio::run(cleanup(
            PathBuf::from("target/tmp/reporting_old/incoming"),
            CleanupConfig {
                frequency: Duration::from_secs(1),
                retention: Duration::from_secs(60),
            },
        ));
    });

    thread::sleep(time::Duration::from_millis(500));

    // Old file has been cleaned up
    assert!(Path::new(file_new).exists());
    assert!(!Path::new(file_very_old).exists());
}
