use crate::r_com_update::Category;
use anyhow;
use anyhow::Context;
use serde::{Deserialize, Serialize};
use std::fmt;
use windows::Win32::System::UpdateAgent::ICategoryCollection;

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct CategoryCollection(Vec<Category>);
impl CategoryCollection {
    pub fn new() -> Self {
        CategoryCollection(Vec::new())
    }

    pub fn try_from_com(c: &ICategoryCollection) -> Result<Self, anyhow::Error> {
        unsafe {
            let count = c
                .Count()
                .context("Failed to get count from ICategoryCollection")?;
            let mut categories = Vec::new();
            for i in 0..count {
                categories.push(
                    Category::try_from_com(
                        &c.get_Item(i)
                            .context("Failed to get item from ICategoryCollection")?,
                    )
                    .context("Failed to translate ICategory to Category")?,
                )
            }
            Ok(Self(categories))
        }
    }
}

// Taken from the secret doc https://learn.microsoft.com/en-us/previous-versions/windows/desktop/ff357803(v=vs.85)
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum WellKnownCategories {
    Application,
    Connectors,
    CriticalUpdates,
    DefinitionUpdates,
    DeveloperKits,
    FeaturePacks,
    Guidance,
    SecurityUpdates,
    ServicePacks,
    Tools,
    UpdateRollups,
    Updates,
}

impl WellKnownCategories {
    pub fn as_str(&self) -> &'static str {
        match self {
            Self::Application => "5C9376AB-8CE6-464A-B136-22113DD69801",
            Self::Connectors => "434DE588-ED14-48F5-8EED-A15E09A991F6",
            Self::CriticalUpdates => "E6CF1350-C01B-414D-A61F-263D14D133B4",
            Self::DefinitionUpdates => "E0789628-CE08-4437-BE74-2495B842F43B",
            Self::DeveloperKits => "E140075D-8433-45C3-AD87-E72345B36078",
            Self::FeaturePacks => "B54E7D24-7ADD-428F-8B75-90A396FA584F",
            Self::Guidance => "9511D615-35B2-47BB-927F-F73D8E9260BB",
            Self::SecurityUpdates => "0FA1201D-4330-4FA8-8AE9-B877473B6441",
            Self::ServicePacks => "68C5B0A3-D1A6-4553-AE49-01D3A7827828",
            Self::Tools => "B4832BD8-E735-4761-8DAF-37F882276DAB",
            Self::UpdateRollups => "28BC880E-0592-4CBF-8F95-C79B17911D5F",
            Self::Updates => "CD5FFD1E-E932-4E3A-BF74-18BF0B1BBD83",
        }
    }

    pub fn from_str(s: &str) -> Option<Self> {
        match s.to_uppercase().as_str() {
            "5C9376AB-8CE6-464A-B136-22113DD69801" => Some(Self::Application),
            "434DE588-ED14-48F5-8EED-A15E09A991F6" => Some(Self::Connectors),
            "E6CF1350-C01B-414D-A61F-263D14D133B4" => Some(Self::CriticalUpdates),
            "E0789628-CE08-4437-BE74-2495B842F43B" => Some(Self::DefinitionUpdates),
            "E140075D-8433-45C3-AD87-E72345B36078" => Some(Self::DeveloperKits),
            "B54E7D24-7ADD-428F-8B75-90A396FA584F" => Some(Self::FeaturePacks),
            "9511D615-35B2-47BB-927F-F73D8E9260BB" => Some(Self::Guidance),
            "0FA1201D-4330-4FA8-8AE9-B877473B6441" => Some(Self::SecurityUpdates),
            "68C5B0A3-D1A6-4553-AE49-01D3A7827828" => Some(Self::ServicePacks),
            "B4832BD8-E735-4761-8DAF-37F882276DAB" => Some(Self::Tools),
            "28BC880E-0592-4CBF-8F95-C79B17911D5F" => Some(Self::UpdateRollups),
            "CD5FFD1E-E932-4E3A-BF74-18BF0B1BBD83" => Some(Self::Updates),
            _ => None,
        }
    }
}

impl fmt::Display for WellKnownCategories {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{:?}", self)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn test_from_str() {
        assert_eq!(
            WellKnownCategories::from_str("5C9376AB-8CE6-464A-b136-22113dd69801"),
            WellKnownCategories::Application
        );
        assert_eq!(WellKnownCategories::from_str("nothing"), None)
    }
}
