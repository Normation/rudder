// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use crate::dsl::comparator::Comparison;
use crate::dsl::AugPath;

/*
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
                         ~ for regex match, !~ for regex not match
    STRING is a string
    INT is a number
    AN_ARRAY is in the form ['a string', 'another']
*/

// FIXME NumComparators ?

pub enum Check<'a> {
    // Comparison contains both the typed value and the comparator
    Get(AugPath<'a>, Comparison),
    ValuesInclude(AugPath<'a>, &'a str),
    ValuesNotInclude(AugPath<'a>, &'a str),
    ValuesEqual(AugPath<'a>, Vec<&'a str>),
    ValuesNotEqual(AugPath<'a>, Vec<&'a str>),
    MatchSize(AugPath<'a>, String, usize),
    MatchInclude(AugPath<'a>, String),
    MatchNotInclude(AugPath<'a>, &'a str),
    MatchEqual(AugPath<'a>, Vec<&'a str>),
    MatchNotEqual(AugPath<'a>, Vec<&'a str>),
}
