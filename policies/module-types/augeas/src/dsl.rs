// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

//! Implements two DSLs to define a safe subset of augeas commands
//! and handle checks.

/*

from https://github.com/puppetlabs/puppetlabs-augeas_core.git
under Apache-2.0

changes:
    set <PATH> <VALUE> --- Sets the value VALUE at location PATH
    setm <PATH> <SUB> <VALUE> --- Sets multiple nodes (matching SUB relative to PATH) to VALUE
    rm <PATH> --- Removes the node at location PATH
    remove <PATH> --- Synonym for rm
    clear <PATH> --- Sets the node at PATH to NULL, creating it if needed
    clearm <PATH> <SUB> --- Sets multiple nodes (matching SUB relative to PATH) to NULL
    touch <PATH> --- Creates PATH with the value NULL if it does not exist
    ins <LABEL> (before|after) <PATH> --- Inserts an empty node LABEL either before or after PATH.
    insert <LABEL> <WHERE> <PATH> --- Synonym for ins
    mv <PATH> <OTHER PATH> --- Moves a node at PATH to the new location OTHER PATH
    move <PATH> <OTHER PATH> --- Synonym for mv
    rename <PATH> <LABEL> --- Rename a node at PATH to a new LABEL
    defvar <NAME> <PATH> --- Sets Augeas variable $NAME to PATH
    defnode <NAME> <PATH> <VALUE> --- Sets Augeas variable $NAME to PATH, creating it with VALUE if needed

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
    STRING is a string
    INT is a number
    AN_ARRAY is in the form ['a string', 'another']
*/
