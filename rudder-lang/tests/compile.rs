// Copyright 2019 Normation SAS
//
// This file is part of Rudder.
//
// Rudder is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// In accordance with the terms of section 7 (7. Additional Terms.) of
// the GNU General Public License version 3, the copyright holders add
// the following Additional permissions:
// Notwithstanding to the terms of section 5 (5. Conveying Modified Source
// Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
// Public License version 3, when you create a Related Module, this
// Related Module is not considered as a part of the work and may be
// distributed under the license agreement of your choice.
// A "Related Module" means a set of sources files including their
// documentation that, without modification of the Source Code, enables
// supplementary functions or services in addition to those offered by
// the Software.
//
// Rudder is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

/// To test only the technique compiler, run this: `cargo test --test compile`

/// this file will be the integration tests base for techniques compilation to cfengine file
/// takes an rl file and a cf file, parses the first, compiles,
/// and compares expected result (inferrred by its filename) with compilation result
/// Therefore, there are naming rules: 
/// with success: s_ & failure: f_
/// input is rl -> state_checkdef.rl
/// output is .rl.cf -> state_checkdef.rl.cf
/// example of files that should succeed: s_errors.rl s_errors.rl.cf
// TODO: compare both result and generated output (.rl.cf) in separated tests

#[macro_use]
extern crate lazy_static;
mod compile_utils;
use compile_utils::*;
use std::collections::HashMap;
use test_case::test_case;

// ======= Tests every file listed below, from the */compile* folder =======
#[test_case("f_enum")]
#[test_case("f_fuzzy")] // should succeed
#[test_case("s_basic")]
// #[test_case("s_does_not_exist")] // this test should fail
fn real_files(filename: &str) {
    test_real_file(filename);
}

// ======= Tests every raw string listed below =======
// List of temporary test files: an array of tuples `(filename, content)`
// Format is not totally correct since there are several superfluous whitespaces but these are trimmmed by the parser
lazy_static! {
    static ref MAPPED_VIRTUAL_FILES: HashMap<&'static str, &'static str> = [
        (
            "s_purest",
            r#"@format=0
            "#
        ),
        (
            "f_enm",
            r#"@format=0
            enm error {
                ok,
                err
            }"#
        ),
        (
            "s_enum",
            r#"@format=0
            enum success {
                ok,
                err
            }"#
        ),
        (
            "v_enum",
            r#"@format=0
            enum success {
                ok,
                err
            }"#
        ),
    ].iter().cloned().collect();
}

// add any new entry to the tests by adding a test_case line on the top of the following
// comment any line to skip the corresponding test
#[test_case("s_purest")]
#[test_case("f_enm")]
#[test_case("s_enum")]
// #[test_case("v_enum")] // this test should fail.
// #[test_case("f_does_not_exist")] // this test should fail. 
fn generated_files(filename: &str) {
    test_generated_file(filename, MAPPED_VIRTUAL_FILES.get(filename));
}