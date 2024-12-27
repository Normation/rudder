mod rpm;
mod yum;

use anyhow::Result;
use serde::Serialize;

#[derive(Debug, PartialEq, Serialize)]
#[serde(rename_all = "UPPERCASE")]
pub struct Package {
    name: String,
    version: String,
    comments: String,
    publisher: Option<String>,
}

#[derive(Debug, PartialEq, Serialize)]
#[serde(rename_all = "UPPERCASE")]
pub struct Update {
    name: String,
    version: String,
    arch: String,
    from: String,
    kind: String,
    source: String,
    description: String,
    severity: String,
    /// Comma separated list
    #[serde(rename = "ID")]
    ids: String,
}

pub trait PackageManager {
    fn installed() -> Result<Vec<Package>>;
}

pub trait UpdateManager {
    fn updates() -> Result<Vec<Update>>;
}
