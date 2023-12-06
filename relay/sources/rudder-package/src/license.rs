// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023 Normation SAS

use anyhow::{anyhow, Result};
use chrono::{DateTime, Utc};
use serde::Serialize;
use std::{collections::HashMap, fs, path::Path};

/// Very simple signature file reader
/// We mainly need to extract expiration date for each plugin

#[derive(Debug, PartialEq, Eq, Clone, Serialize)]
pub struct License {
    pub start_date: DateTime<Utc>,
    pub end_date: DateTime<Utc>,
}

#[derive(Debug, PartialEq, Eq, Clone)]
pub struct Licenses {
    pub inner: HashMap<String, License>,
}

impl Licenses {
    pub fn from_path(path: &Path) -> Result<Self> {
        fn find_key_value<'a>(key: &'a str, content: &'a str) -> Option<&'a str> {
            content
                .lines()
                .find(|l| l.starts_with(&format!("{key}=")))
                .and_then(|l| l.split('=').nth(1))
        }

        let mut res = HashMap::new();
        if path.exists() {
            for entry in fs::read_dir(path)? {
                let entry = entry?;
                let path = entry.path();
                if path.extension().map(|e| e.to_string_lossy().into_owned())
                    == Some("license".to_string())
                {
                    let s = fs::read_to_string(path)?;
                    let plugin = find_key_value("softwareid", &s)
                        .ok_or(anyhow!("Could not find software id in license"))?
                        .to_owned();
                    let start_date_r = find_key_value("startdate", &s)
                        .ok_or(anyhow!("Could not find start date in license"))?
                        .to_owned();
                    let start_date = DateTime::parse_from_rfc3339(&start_date_r)?.into();
                    let end_date_r = find_key_value("enddate", &s)
                        .ok_or(anyhow!("Could not find end date in license"))?
                        .to_owned();
                    let end_date = DateTime::parse_from_rfc3339(&end_date_r)?.into();
                    res.insert(
                        plugin,
                        License {
                            start_date,
                            end_date,
                        },
                    );
                }
            }
        }
        Ok(Self { inner: res })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn it_parses_license() {
        let licenses = Licenses::from_path(Path::new("tests/licenses")).unwrap();
        dbg!(licenses);
    }
}
