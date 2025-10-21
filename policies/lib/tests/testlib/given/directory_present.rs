// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2025 Normation SAS

use crate::testlib::given::setup_state::TestSetup;
use crate::testlib::test_setup::TestSetupResult;
use anyhow::Error;
use std::fs;
use tracing::debug;

#[derive(Clone, Debug)]
pub struct DirectoryPresentStruct {
    pub path: String,
}
impl TestSetup for DirectoryPresentStruct {
    fn resolve(&self) -> anyhow::Result<TestSetupResult, Error> {
        debug!("Creating directory {}", self.path);
        fs::create_dir(&self.path)?;
        Ok(TestSetupResult::default())
    }
}
