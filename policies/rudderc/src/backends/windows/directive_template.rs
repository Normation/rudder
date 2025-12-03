// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2025 Normation SAS

use crate::backends::windows::filters;
use askama::Template;

#[derive(Template)]
#[template(path = "test-directive.ps1.askama", escape = "none")]
pub struct DirectiveTemplate<'a> {
    pub bundle_name: &'a str,
    pub technique_name: &'a str,
    pub policy_mode: &'a str,
    pub params: &'a str,
    pub directive_id: &'a str,
    //pub rule_id: &'a str,
    pub conditions: Vec<&'a String>,
}
