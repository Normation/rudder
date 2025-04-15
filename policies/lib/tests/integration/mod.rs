// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2025 Normation SAS

#[cfg(test)]
pub mod command_execution_test;
#[cfg(test)]
pub mod condition_from_expression_test;
#[cfg(test)]
pub mod condition_from_variable_existence_test;
#[cfg(test)]
pub mod condition_from_variable_match_test;
#[cfg(test)]
pub mod file_absent_test;
#[cfg(test)]
pub mod file_check_exists_test;
#[cfg(test)]
mod file_content_test;

use log::debug;
use rudder_commons::methods::Methods;
use std::mem::ManuallyDrop;
use std::path::PathBuf;
use std::sync::OnceLock;
use tempfile::{tempdir, TempDir};

const LIBRARY_PATH: &str = "./tree";
pub fn get_lib() -> &'static Methods {
    static LIB: OnceLock<Methods> = OnceLock::new();
    LIB.get_or_init(|| {
        rudderc::frontends::read_methods(&[get_lib_path()])
            .unwrap()
            .clone()
    })
}
fn get_lib_path() -> PathBuf {
    PathBuf::from(LIBRARY_PATH)
}

fn init_test() -> ManuallyDrop<TempDir> {
    let _ = env_logger::try_init();
    let workdir = tempdir().unwrap();
    debug!("WORKDIR = {:?}", workdir.path());
    ManuallyDrop::new(workdir)
}

fn end_test(workdir: ManuallyDrop<TempDir>) {
    ManuallyDrop::into_inner(workdir);
}
