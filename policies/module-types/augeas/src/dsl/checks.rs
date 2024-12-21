// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use regex::Regex;

pub type MatchPath = String;

/*


changes:

ifonly:
    get <AUGEAS_PATH> <COMPARATOR> <STRING>
    values <MATCH_PATH> include <STRING>
    values <MATCH_PATH> not_include <STRING>
    values <MATCH_PATH> == <AN_ARRAY>
    values <MATCH_PATH> != <AN_ARRAY>
    match <MATCH_PATH> size <COMPARATOR> <INT>
    match <MATCH_PATH> include <STRING>
    match <MATCH_PATH> not_include <STRING>
    match <MATCH_PATH> == <AN_ARRAY>
    match <MATCH_PATH> != <AN_ARRAY>

where:
    AUGEAS_PATH is a valid path scoped by the context
    MATCH_PATH is a valid match syntax scoped by the context
    COMPARATOR is one of >, >=, !=, ==, <=, or <
      ~ for regex match
    STRING is a string
    INT is a number
    AN_ARRAY is in the form ['a string', 'another']
*/

pub enum Comparator {
    GreaterThan,
    GreaterThanOrEqual,
    NotEqual,
    Equal,
    LessThanOrEqual,
    LessThan,
    MatchRegex(Regex),
    NotMatchRegex(Regex),
}

pub enum Check {
    Get(String, Comparator, String),
    ValuesInclude(String, String),
    ValuesNotInclude(String, String),
    ValuesEqual(String, Vec<String>),
    ValuesNotEqual(String, Vec<String>),
    MatchSize(String, String, usize),
    MatchInclude(String, String),
    MatchNotInclude(String, String),
    MatchEqual(String, Vec<String>),
    MatchNotEqual(String, Vec<String>),
}
