use crate::r_com_update::Info;
use anyhow::{Error, Result};
use serde::{Deserialize, Serialize};
use std::ops::Deref;
use windows::Win32::System::UpdateAgent::IUpdateCollection;

#[derive(Debug, Deserialize, Serialize)]
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

    pub fn try_from_com(c: IUpdateCollection) -> Result<Self, Error> {
        unsafe {
            let count = c.Count()?;
            let mut updates = Vec::new();
            for i in 0..count {
                updates.push(Info::try_from_com(c.get_Item(i)?)?)
            }
            Ok(Self {
                com_ptr: Some(c),
                updates,
            })
        }
    }
}

impl Deref for Collection {
    type Target = Vec<Info>;
    fn deref(&self) -> &Self::Target {
        &self.updates
    }
}
