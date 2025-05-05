// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2025 Normation SAS

use crate::testlib::given::directory_present::DirectoryPresentStruct;
use crate::testlib::given::file_absent::FileAbsentStruct;
use crate::testlib::given::file_present::FilePresentStruct;
use crate::testlib::given::posix_acl_present::PosixAclPresentStruct;
use crate::testlib::test_setup::TestSetupResult;
use anyhow::Error;

pub trait TestSetup {
    fn resolve(&self) -> anyhow::Result<TestSetupResult, Error>;
}

#[derive(Clone)]
pub enum SetupState {
    FilePresent(FilePresentStruct),
    FileAbsent(FileAbsentStruct),
    DirectoryPresent(DirectoryPresentStruct),
    PosixAclPresent(PosixAclPresentStruct),
}

impl TestSetup for SetupState {
    fn resolve(&self) -> anyhow::Result<TestSetupResult, Error> {
        match self {
            SetupState::FilePresent(fp) => fp.resolve(),
            SetupState::FileAbsent(fa) => fa.resolve(),
            SetupState::DirectoryPresent(dp) => dp.resolve(),
            SetupState::PosixAclPresent(pp) => pp.resolve(),
        }
    }
}
