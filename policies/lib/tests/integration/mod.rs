// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2025 Normation SAS

mod common;
#[cfg(all(test, feature = "test-unix"))]
mod unix;
#[cfg(all(test, feature = "test-windows"))]
mod windows;

use rudder_commons::methods::Methods;
use std::mem::ManuallyDrop;
use std::path::PathBuf;
use std::sync::OnceLock;
use tempfile::{TempDir, tempdir};
use tracing::debug;

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
    std::path::absolute(PathBuf::from(LIBRARY_PATH))
        .expect("Could not get the absolute path of the library")
}

fn init_test() -> ManuallyDrop<TempDir> {
    tracing_subscriber::fmt::init();
    let workdir = tempdir().unwrap();
    debug!("WORKDIR = {:?}", workdir.path());
    ManuallyDrop::new(workdir)
}

fn end_test(workdir: ManuallyDrop<TempDir>) {
    ManuallyDrop::into_inner(workdir);
}
