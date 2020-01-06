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
