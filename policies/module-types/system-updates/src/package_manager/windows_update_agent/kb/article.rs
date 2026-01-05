// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS
use anyhow::bail;
use serde::{Deserialize, Serialize};
#[derive(Debug, Clone, Deserialize, Serialize, PartialEq)]
pub struct Article(String);
impl Article {
    pub fn new(id: String) -> Result<Self, anyhow::Error> {
        let id = id.trim();
        let number_part = if id.to_uppercase().starts_with("KB") {
            &id[2..]
        } else {
            id
        };

        if number_part.is_empty() {
            bail!("Update Article ID cannot be empty");
        }

        if !number_part.chars().all(|c| c.is_ascii_digit()) {
            bail!(format!(
                "Update Article ID must be numeric, got: '{}'",
                number_part
            ))
        }

        Ok(Self(format!("KB{}", number_part)))
    }
}
