// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

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
// Note: 2 ways a test fails:
// - either rudderc::compile() and expected result don't match
// - or rudderc::compile() was not called at all (file does not exist or filename format is wrong)

mod compile_utils;
use compile_utils::*;

use test_case::test_case;

// ======= Tests every file listed below, from the */compile* folder ======= //

#[test_case("f_fuzzy")]
#[test_case("s_basic")]
// #[test_case("s_does_not_exist")] // supposed to fail as the file does not exist
fn real_files(filename: &str) {
    test_real_file(filename);
}

// ======= Tests every raw string listed below ======= //

// Note: any file not deleted in the `tests/tmp/` folder is a test that failed (kind of trace)

#[test_case("s_purest", "@format=0\n" ; "s_purest")]

#[test_case("f_enm", r#"@format=0
enm error {
    ok,
    err
}"#; "f_enm")]

#[test_case("s_enum", r#"@format=0
enum success {
    ok,
    err
}"#; "s_enum")]

// #[test_case("v_purest", "@format=0\n"; "v_purest")] // supposed to fail as the prefix `v_` is not expected

fn generated_files(filename: &str, content: &str) {
    test_generated_file(filename, content);
}
