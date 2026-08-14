// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

//! The `LOCAL_USERS` section: the users of the machine, which `sysinfo` reads from the password
//! database.

use serde::Serialize;
use sysinfo::Users;
use tracing::debug;

#[derive(Debug, PartialEq, Serialize)]
#[serde(rename_all = "UPPERCASE")]
pub struct User {
    login: String,
}

/// Only the login is reported: the server reads nothing from the home directory, the shell or
/// the identifier of a user.
pub fn inventory(users: &Users) -> Vec<User> {
    let users: Vec<User> = users
        .iter()
        .map(|u| User {
            login: u.name().to_string(),
        })
        .collect();
    debug!("Found {} users", users.len());
    users
}
