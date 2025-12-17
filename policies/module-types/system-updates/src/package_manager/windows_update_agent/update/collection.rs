// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS
use super::Info;
use anyhow::{Context, Error, Result, bail};
use serde::{Deserialize, Serialize};
use std::ops::Deref;
use windows::Win32::System::Com::{CLSCTX_INPROC_SERVER, CoCreateInstance};
use windows::Win32::System::UpdateAgent::{IUpdateCollection, UpdateCollection};

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct Collection(Vec<Info>);
impl Collection {
    pub fn new(v: Vec<Info>) -> Self {
        Self(v)
    }
}

impl Default for Collection {
    fn default() -> Self {
        Self(vec![])
    }
}

impl Collection {
    pub fn filter_collection<F>(self, predicate: F) -> Collection
    where
        F: Fn(&Info) -> bool,
    {
        let v = self
            .0
            .into_iter()
            .filter(|n| predicate(n))
            .collect::<Vec<Info>>();
        Collection(v)
    }
}

impl TryFrom<&Collection> for IUpdateCollection {
    type Error = Error;
    fn try_from(collection: &Collection) -> Result<IUpdateCollection, Self::Error> {
        let c: IUpdateCollection = unsafe {
            CoCreateInstance(&UpdateCollection, None, CLSCTX_INPROC_SERVER)
                .context("Could not create a new IUpdateCollection from the COM API")?
        };
        for info in &collection.0 {
            unsafe {
                match &info.com_ptr {
                    None => {
                        bail!("Null pointer found for update {}", info.data.title)
                    }
                    Some(p) => {
                        let _ = c.Add(p).context(format!(
                            "Could not add the update {} to the COM collection",
                            info.data.title
                        ))?;
                    }
                }
            }
        }
        Ok(c)
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
        Ok(Self { 0: updates })
    }
}

impl Deref for Collection {
    type Target = Vec<Info>;
    fn deref(&self) -> &Self::Target {
        &self.0
    }
}
