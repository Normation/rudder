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
mod utils;
use utils::*;

use test_case::test_case;

// =============== Tests every file listed below, from the */compile* folder =============== //

#[test_case("f_enum")]
#[test_case("f_fuzzy")]
#[test_case("s_basic")]
#[test_case("s_state_case")]
// #[test_case("s_declare")]
// #[test_case("s_does_not_exist")] // supposed to fail as the file does not exist
fn real_files(filename: &str) {
    // manually define here the output folder (direct parent folder is /tests/)
    test_real_file(filename);
}

// ======== Tests every raw string listed below ======== //

// Note: any file not deleted in the `tests/tmp/` folder is a test that failed (kind of trace)

// ======= GENERAL SYNTAX =======

#[test_case("s_purest", "@format=0\n" ; "s_purest")]
#[test_case("f_format_val;", "@format= 24\n" ; "f_format_val")]
#[test_case("f_format", "@format=24\n" ; "f_format")]
#[test_case("s_whitespaces", r#"@format=0
     enum      example        {
        ok,             
    err             
     }         "#; "s_whitespaces")]
// ======= GENERAL PURPOSE PARSING FUNCTIONS =======

// numbers in parenthesis are the line the element is declared in parser.rs

// Space terminated
#[test_case("s_resourcedef_terminating_space", r#"@format=0
resource Rname ()
"#; "s_resourcedef_terminating_space")]
#[test_case("f_enum_no_terminating_space", r#"@format=0
global enumsuccess { ok }
"#; "f_enum__no_terminating_space")]
#[test_case("f_resourcedef_no_terminating_space", r#"@format=0
resourceRname ()
"#; "f_resourcedef_no_terminating_space")]
// Parameter list
#[test_case("s_paramlist_empty", r#"@format=0
resource Rname ( )
"#; "s_paramlist_empty")]
#[test_case("s_paramlist_2", r#"@format=0
resource Rname (a,b)
"#; "s_paramlist_2")]
#[test_case("s_paramlist_superfuous_ending_comma", r#"@format=0
resource Rname (a,)
"#; "s_paramlist_superfuous_ending_comma")]
#[test_case("f_paramlist_superfuous_commas", r#"@format=0
resource Rname (a,,b)
"#; "f_paramlist_superfuous_commas")]
#[test_case("s_paramlist_spaces", r#"@format=0
resource Rname (   
        a   ,
        b   ,
    )
"#; "s_paramlist_spaces")]
// Comments
#[test_case("s_simple_comment", r#"@format=0
# Hi, i'm a simple comment
"#; "s_simple_comment")]
// ======= ENUMS ======= (280)
#[test_case("s_inline_enum", r#"@format=0
enum success { ok }
"#; "s_inline_enum")]
#[test_case("f_enm", r#"@format=0
enm error {
    ok,
    err
}"#; "f_enm")]
#[test_case("f_enum_comma", r#"   @format   = 0     
enum error {
    ok
    err
}"#; "f_enum_comma")]
#[test_case("f_unterminated_enum", r#"@format=0
enum success {
    ok,
    err
"#; "f_unterminated_enum")]
#[test_case("s_enum", r#"@format=0
enum success {
    ok,
    err
}"#; "s_enum")]
#[test_case("s_enum_meta", r#"@format=0
@forbiddenmeta = "Should not be here"
enum success {
    ok,
}"#; "s_enum_meta")]
#[test_case("s_enum_comment", r#"@format=0
#simple comment authorized
enum success {
    ok,
}"#; "s_enum_comment")]
#[test_case("s_enum_meta_comment", r#"@format=0
##allowedcomment
enum success {
    ok,
}"#; "s_enum_meta_comment")]
#[test_case("f_enum_reserved_keyword", r#"@format=0
enum enum {
    ascendant,
}"#; "f_enum_reserved_keyword")]
// ======= RESOURCE DEFINITIONS ======= (713)
#[test_case("s_resdef", r#"@format=0
resource Rname ()
"#; "s_resdef")]
#[test_case("s_resourcedef_meta", r#"@format=0
##metacomment is ok here
@metadata=1
resource Rname() : condition
"#; "s_resourcedef_meta")]
#[test_case("f_resourcedef_superfluous_bracket", r#"@format=0
resource Rname (a,b,c) }
"#; "f_resourcedef_superfluous_bracket")] // expected_enum issue
#[test_case("s_resdef_parent", r#"@format=0
resource Rname() : condition
"#; "s_resdef_parent")] // beware, condition has 1 param
#[test_case("f_resdef_recursive", r#"@format=0
resource Rname() : Rname
"#; "f_resdef_recursive")]
#[test_case("f_resdef_parent", r#"@format=0
resource Rname() : NonexistantParent
"#; "f_resdef_parent")]
// ======= STATE DEFINITIONS ======= (903)
#[test_case("s_statedef_empty", r#"@format=0
## authorized
@metadata=1
file state name(a,b) {}
"#; "s_statedef_empty")]
#[test_case("f_statedef_undefined_resource", r#"@format=0
@metadata=1
NonExistantResource state name(a,b) {
}
"#; "f_statedef_undefined_resource")]
// #[test_case("f_state_declaration", r#"@format=0
// @metadata=1
// newres state hi() {}
// newres state hi() {}
// "#; "f_state_full")] // UNHANDLED ERROR when having a double declaration
#[test_case("s_state_audit", r#"@format=0
@metadata=1
resource Configure_NTP()
Configure_NTP state technique() {
    # state_decl Audit
    !package("ntp").present("","","") as package_present_ntp
}
"#; "s_state_audit")]
#[test_case("s_state_var", r#"@format=0
@metadata=1
resource Configure_NTP()
Configure_NTP state technique() {
    @var_def = 123
    filename = "s_test.rl"
}
"#; "s_state_var")]
// CASEs and IFs
#[test_case("f_state_var_nonexistant", r#"@format=0
@metadata=1
resource Configure_NTP()
Configure_NTP state technique() {
    if outvar =~ kept => noop
}
"#; "f_state_var_nonexistant")]
#[test_case("s_state_if", r#"@format=0
@metadata=1
resource Configure_NTP()
Configure_NTP state technique() {
    ## pstate_declaration
    file("/tmp").absent() as abs_file
    if abs_file =~ kept => noop
}"#; "s_state_if")]
#[test_case("s_state_case", r#"@format=0
@metadata=1
resource Configure_NTP()
Configure_NTP state technique() {
  case {
    # this case makes no sense, testing purpose
    system=~ubuntu => file("/tmp").absent(),
    system=~debian => file("/tmp").present(),
    default => log "info: ok"
  }
}"#; "s_state_case")]
// #[test_case("f_state_case", r#"@format=0
// @metadata=1
// resource Configure_NTP()
// Configure_NTP state technique() {
//   case {
//     ubuntu => file("/tmp").doesnotexist(),
//     debian => file("/tmp").doesnoteither()
//   }
// }"#; "f_state_case")] // PANIC
#[test_case("f_state_case_nonexhaustive", r#"@format=0

@metadata=1
resource Configure_NTP()
Configure_NTP state technique() {
  case {
    system=~debian => file("/tmp").absent()
  }
}"#; "f_state_case_nonexhaustive")]
#[test_case("s_state_casedefault", r#"@format=0
@metadata=1
resource Configure_NTP()
Configure_NTP state technique() {
  case {
    # this case makes no sense, testing purpose
    system=~ubuntu => file("/tmp").absent(),
    default => log "info: ok"
  }
}"#; "s_state_casedefault")]
#[test_case("f_state_if_recursive", r#"@format=0
@metadata=1
resource Configure_NTP()
Configure_NTP state technique() {
    ## pstate_declaration
    file("/tmp").absent() as abs_file
    if abs_file =~ kept => if abs_file =~ kept => noop
}"#; "f_state_if_recursive")] // designed to be handled someday

// enum expr without spaces
#[test_case("s_state_exprs_nospace", r#"@format=0
@metadata=1
resource Configure_NTP()
Configure_NTP state technique() {
    file("/tmp").absent() as abs_file
    file("/tmp").present() as pres_file
    if abs_file=~kept|pres_file=~repaired&pres_file!~error => return kept
}"#; "s_state_exprs_nospace")]
// enum expressions
#[test_case("s_state_exprs", r#"@format=0
@metadata=1
resource Configure_NTP()
Configure_NTP state technique() {
    file("/tmp").absent() as abs_file
    file("/tmp").present() as pres_file
    if abs_file =~ kept | pres_file =~ repaired & pres_file !~ error => return kept
}"#; "s_state_exprs")]
#[test_case("s_state_expr_nested", r#"@format=0
@metadata=1
resource Configure_NTP()
Configure_NTP state technique() {
    file("/tmp").absent() as abs_file
    file("/tmp").present() as pres_file
    if (((abs_file =~ kept | pres_file !~ kept) & pres_file =~ error) & pres_file =~ error) => noop
}"#; "s_state_expr_nested")]
// ======= VARIABLE DEFINITIONS ======= (754)
#[test_case("s_variabledef_string", r#"@format=0
varname = "value"
"#; "s_variabledef_string")]
#[test_case("s_variabledef_int", r#"@format=0
varname = 1
"#; "s_variabledef_int")]
#[test_case("s_variabledef_list", r#"@format=0
varname = [ [1, 2, 3], [1], [] ]
"#; "s_variabledef_list")]
#[test_case("s_variabledef_struct", r#"@format=0
varname = {
    "key1": "value1",
    "key2": "value2",
}
"#; "s_variabledef_struct")]
// ======= ALIAS DEFINITIONS ======= (949)
#[test_case("s_aliasdef", r#"@format=0
@alias=1
alias resource_alias().state_alias() = resource().state()
"#; "s_aliasdef")]
#[test_case("f_aliasdef_missing_tag", r#"@format=0
@alias=1
alias resource_alias()state_alias() resource()state()
"#; "f_aliasdef_missing_tag")]

// #[test_case("v_purest", "@format=0\n"; "v_purest")] // supposed to fail as the prefix `v_` is not expected

fn generated_files(filename: &str, content: &str) {
    test_generated_file(filename, content);
}
