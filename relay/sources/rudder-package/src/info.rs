// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023 Normation SAS

use chrono::SecondsFormat;

use crate::{repo_index::RepoIndex, repository::Repository};

pub fn display_info(repo: &Repository, index: Option<&RepoIndex>) {
    println!(
        "Account: {}",
        repo.get_username()
            .unwrap_or("none (anonymous)".to_string())
    );
    println!("Repository: {}", repo.server);
    if let Some(i) = index {
        println!("Latest index update: {}", i.latest_update.to_rfc3339_opts(SecondsFormat::Secs, true));
    } else {
        println!("Index was never downloaded from the server");
    }
    // Compute next license expiration
    
    
    // Next expiration
}
