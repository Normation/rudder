// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2025 Normation SAS

pub mod directory_present;
pub mod file_absent;
pub mod file_present;
pub mod posix_acl_present;
pub mod setup_state;

use crate::testlib::given::file_absent::FileAbsentStruct;
use crate::testlib::given::file_present::FilePresentStruct;
use crate::testlib::given::posix_acl_present::PosixAclPresentStruct;
use crate::testlib::method_to_test::MethodToTest;
use directory_present::DirectoryPresentStruct;
#[cfg(unix)]
use posix_acl::Qualifier;
use setup_state::SetupState;
use setup_state::SetupState::{DirectoryPresent, FileAbsent, FilePresent, PosixAclPresent};

#[derive(Clone)]
pub enum Given {
    Setup(SetupState),
    MethodCall(MethodToTest),
}

impl Given {
    pub fn method_call(m: &MethodToTest) -> Given {
        Given::MethodCall(m.clone())
    }
    pub fn file_present(file_path: &str, content: &str) -> Given {
        Given::Setup(FilePresent(FilePresentStruct {
            path: file_path.to_string(),
            content: content.to_string(),
        }))
    }
    pub fn file_absent(file_path: &str) -> Given {
        Given::Setup(FileAbsent(FileAbsentStruct {
            path: file_path.to_string(),
        }))
    }
    pub fn directory_present(directory_path: &str) -> Given {
        Given::Setup(DirectoryPresent(DirectoryPresentStruct {
            path: directory_path.to_string(),
        }))
    }

    #[cfg(unix)]
    pub fn posix_acl_present(
        path: &str,
        qualifier: Qualifier,
        perm: posix_acl_present::Perms,
    ) -> Given {
        Given::Setup(PosixAclPresent(PosixAclPresentStruct {
            path: path.to_string(),
            qualifier,
            perm,
        }))
    }

    #[cfg(windows)]
    pub fn posix_acl_present(
        path: &str,
        qualifier: Qualifier,
        perm: posix_acl_present::Perms,
    ) -> Given {
        bail!("Not supported on Windows");
    }
}
