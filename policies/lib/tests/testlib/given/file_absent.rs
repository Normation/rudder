// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2025 Normation SAS

use crate::testlib::given::setup_state::TestSetup;
use crate::testlib::test_setup::TestSetupResult;
use anyhow::{Error, bail};
use log::debug;
use std::fs;
use std::io::ErrorKind;

#[derive(Clone)]
pub struct FileAbsentStruct {
    pub path: String,
}
impl TestSetup for FileAbsentStruct {
    fn resolve(&self) -> anyhow::Result<TestSetupResult, Error> {
        let r = TestSetupResult::default();
        debug!("Removing file {}", self.path);
        match fs::remove_file(&self.path) {
            Ok(()) => Ok(r),
            Err(e) if e.kind() == ErrorKind::NotFound => Ok(r),
            Err(e) => bail!(e),
        }
    }
}
