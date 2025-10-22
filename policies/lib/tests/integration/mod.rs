// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2025 Normation SAS

#[cfg(test)]
#[cfg(feature = "unix")]
mod audit_from_command_test;
#[cfg(test)]
mod audit_from_osquery_test;
#[cfg(test)]
#[cfg(feature = "unix")]
mod command_execution_options_test;
#[cfg(test)]
mod command_execution_test;
#[cfg(test)]
#[cfg(feature = "unix")]
mod condition_from_expression_test;
#[cfg(test)]
mod condition_from_variable_existence_test;
#[cfg(test)]
mod condition_from_variable_match_test;
#[cfg(test)]
#[cfg(feature = "unix")]
mod directory_check_exists_test;
#[cfg(test)]
mod directory_present_test;
#[cfg(test)]
mod file_absent_test;
#[cfg(test)]
#[cfg(feature = "unix")]
mod file_block_present_in_section_test;
#[cfg(test)]
#[cfg(feature = "unix")]
mod file_block_present_test;
#[cfg(test)]
mod file_check_exists_test;
#[cfg(test)]
mod file_content_test;
#[cfg(test)]
#[cfg(feature = "unix")]
mod file_copy_from_local_source_recursion_test;
#[cfg(test)]
#[cfg(feature = "unix")]
mod file_ensure_key_value_option_test;
#[cfg(test)]
mod file_from_http_server_test;
#[cfg(test)]
#[cfg(feature = "unix")]
mod file_from_local_source_recursion_test;
#[cfg(test)]
#[cfg(feature = "unix")]
mod file_from_string_mustache_test;
#[cfg(test)]
mod file_from_template_options_test;
#[cfg(test)]
mod file_from_template_type_test;
#[cfg(test)]
mod file_key_value_parameter_present_in_list_test;
#[cfg(test)]
mod file_lines_absent_test;
#[cfg(test)]
#[cfg(feature = "unix")]
mod file_replace_lines_test;
#[cfg(all(test, unix))]
#[cfg(feature = "unix")]
mod permissions_acl_entry_test;
#[cfg(all(test, unix))]
#[cfg(feature = "unix")]
mod permissions_group_acl_absent_test;
#[cfg(all(test, unix))]
#[cfg(feature = "unix")]
mod permissions_group_acl_present_test;
#[cfg(all(test, unix))]
#[cfg(feature = "unix")]
mod permissions_other_acl_present_test;
#[cfg(test)]
#[cfg(feature = "unix")]
mod user_methods_test;
#[cfg(test)]
#[cfg(feature = "unix")]
mod variable_string_default_test;
#[cfg(test)]
mod variable_string_from_command_test;
#[cfg(test)]
mod variable_string_test;

use tracing::debug;
use rudder_commons::methods::Methods;
use std::mem::ManuallyDrop;
use std::path::PathBuf;
use std::sync::OnceLock;
use tempfile::{TempDir, tempdir};

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
    std::path::absolute(PathBuf::from(LIBRARY_PATH)).expect("Could not get the absolute path of the library")
}

fn init_test() -> ManuallyDrop<TempDir> {
    let _ = tracing_subscriber::fmt::init();
    let workdir = tempdir().unwrap();
    debug!("WORKDIR = {:?}", workdir.path());
    ManuallyDrop::new(workdir)
}

fn end_test(workdir: ManuallyDrop<TempDir>) {
    ManuallyDrop::into_inner(workdir);
}
