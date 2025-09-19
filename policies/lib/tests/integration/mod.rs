// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2025 Normation SAS

#[cfg(test)]
mod audit_from_command_test;
#[cfg(test)]
mod audit_from_osquery_test;
mod command_execution_options_test;
#[cfg(test)]
mod command_execution_test;
#[cfg(test)]
mod condition_from_expression_test;
#[cfg(test)]
mod condition_from_variable_existence_test;
#[cfg(test)]
mod condition_from_variable_match_test;
#[cfg(test)]
mod directory_check_exists_test;
#[cfg(test)]
mod directory_present_test;
#[cfg(test)]
mod file_absent_test;
#[cfg(test)]
mod file_block_present_in_section_test;
#[cfg(test)]
mod file_block_present_test;
#[cfg(test)]
mod file_check_exists_test;
#[cfg(test)]
mod file_content_test;
#[cfg(test)]
mod file_copy_from_local_source_recursion_test;
#[cfg(test)]
mod file_ensure_key_value_option_test;
#[cfg(test)]
mod file_from_http_server_test;
#[cfg(test)]
mod file_from_local_source_recursion_test;
#[cfg(test)]
mod file_from_string_mustache_test;
#[cfg(test)]
mod file_from_template_type_test;
#[cfg(test)]
mod file_key_value_parameter_present_in_list_test;
#[cfg(test)]
mod file_lines_absent_test;
#[cfg(test)]
mod file_replace_lines_test;
#[cfg(test)]
mod permissions_acl_entry_test;
#[cfg(test)]
mod permissions_group_acl_absent_test;
#[cfg(test)]
mod permissions_group_acl_present_test;
#[cfg(test)]
mod permissions_other_acl_present_test;
#[cfg(test)]
mod user_methods_test;
#[cfg(test)]
mod variable_string_default_test;
#[cfg(test)]
mod variable_string_from_command_test;
#[cfg(test)]
mod variable_string_test;

use log::debug;
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
