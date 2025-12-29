use super::Info;
use anyhow::{Context, Error, Result};
use serde::{Deserialize, Serialize};
use std::ops::Deref;
use windows::Win32::System::UpdateAgent::IUpdateCollection;

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct Collection {
    #[serde(skip)]
    pub com_ptr: Option<IUpdateCollection>,
    #[serde(flatten)]
    pub updates: Vec<Info>,
}
impl Collection {
    pub fn new() -> Self {
        Self {
            com_ptr: None,
            updates: Vec::new(),
        }
    }
}

impl TryFrom<IUpdateCollection> for Collection {
    type Error = Error;
    fn try_from(c: IUpdateCollection) -> Result<Self, Error> {
        let mut updates = Vec::new();
        let count = unsafe {
            c.Count()
                .context("Could not retrieve IUpdateCollection length")?
        };
        for i in 0..count {
            let update = unsafe {
                c.get_Item(i).context(format!(
                    "Could not retrieve IUpdateCollection item with index {}",
                    i
                ))?
            };
            let info = Info::try_from(update)
                .context("Could not convert IUpdateCollection to UpdateInfo")?;
            updates.push(info)
        }
        Ok(Self {
            com_ptr: Some(c),
            updates,
        })
    }
}

impl Deref for Collection {
    type Target = Vec<Info>;
    fn deref(&self) -> &Self::Target {
        &self.updates
    }
}
