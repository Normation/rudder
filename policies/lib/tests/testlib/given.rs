// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2025 Normation SAS

pub mod directory_present;
pub mod file_absent;
pub mod file_present;
pub mod setup_state;

use crate::testlib::given::file_absent::FileAbsentStruct;
use crate::testlib::given::file_present::FilePresentStruct;
use crate::testlib::method_to_test::MethodToTest;
use directory_present::DirectoryPresentStruct;
use setup_state::SetupState;
use setup_state::SetupState::{DirectoryPresent, FileAbsent, FilePresent};

#[derive(Clone)]
pub enum Given {
    Setup(SetupState),
    MethodCall(MethodToTest),
}

impl Given {
    pub fn method_call(m: MethodToTest) -> Given {
        Given::MethodCall(m)
    }
    pub fn file_present(file_path: String) -> Given {
        Given::Setup(FilePresent(FilePresentStruct { path: file_path }))
    }
    pub fn file_absent(file_path: String) -> Given {
        Given::Setup(FileAbsent(FileAbsentStruct { path: file_path }))
    }
    pub fn directory_present(directory_path: String) -> Given {
        Given::Setup(DirectoryPresent(DirectoryPresentStruct {
            path: directory_path,
        }))
    }
}
