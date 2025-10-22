// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2025 Normation SAS

use crate::testlib::given::setup_state::TestSetup;
use crate::testlib::test_setup::TestSetupResult;
use anyhow::Error;
use log::debug;
use std::fs::File;
use std::io::Write;

#[derive(Clone, Debug)]
pub struct FilePresentStruct {
    pub path: String,
    pub content: String,
}
impl TestSetup for FilePresentStruct {
    fn resolve(&self) -> anyhow::Result<TestSetupResult, Error> {
        debug!("Creating file {}", self.path);
        let mut f = File::create(&self.path)?;
        f.write_all(self.content.as_bytes())?;
        Ok(TestSetupResult::default())
    }
}
