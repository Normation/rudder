// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS
use crate::package_manager::windows_update_agent::update::category_collection::WellKnownCategories;
use anyhow;
use anyhow::Context;
use serde::{Deserialize, Serialize};
use std::fmt;
use windows::Win32::System::UpdateAgent::ICategory;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct Category {
    pub category_type: String,
    pub name: String,
    pub id: String,
}

impl Category {
    pub fn is_security(&self) -> bool {
        self.id.as_str().to_uppercase() == format!("{}", WellKnownCategories::SecurityUpdates)
    }
}
impl TryFrom<&ICategory> for Category {
    type Error = anyhow::Error;
    fn try_from(c: &ICategory) -> Result<Self, Self::Error> {
        unsafe {
            Ok(Self {
                category_type: c
                    .Type()
                    .context("Failed to get category type from ICategory")?
                    .to_string(),
                name: c
                    .Name()
                    .context("Failed to get name from ICategory")?
                    .to_string(),
                id: c
                    .CategoryID()
                    .context("Failed to get id from ICategory")?
                    .to_string(),
            })
        }
    }
}

impl fmt::Display for Category {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{} ({}): {}", self.category_type, self.name, self.id)
    }
}
