// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS
use super::super::kb::ArticleCollection;
use super::CategoryCollection;
use crate::package_manager::PackageId;
use anyhow::Result;
use anyhow::{Context, Error};
use serde::{Deserialize, Serialize};
use windows::Win32::System::UpdateAgent::*;
use windows::core::Interface;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct Info {
    #[serde(skip)]
    pub com_ptr: Option<IUpdate>,
    #[serde(flatten)]
    pub data: InfoData,
}
impl TryFrom<IUpdate> for Info {
    type Error = Error;
    fn try_from(u: IUpdate) -> Result<Self, Error> {
        let data = InfoData::try_from(&u).context("Could not convert IUpdate to InfoData")?;
        Ok(Self {
            com_ptr: Some(u),
            data,
        })
    }
}

#[derive(Debug, Clone, Deserialize, Serialize, PartialEq)]
pub struct InfoData {
    pub update_id: String,
    pub revision_number: i32,
    pub title: String,
    pub kbs: ArticleCollection,
    pub msrc_severity: String,
    pub categories: CategoryCollection,
    #[serde(skip)]
    pub is_downloaded: bool,
    #[serde(skip)]
    pub is_installed: bool,
    pub is_mandatory: bool,
    pub superseded_update_ids: Vec<String>,
    pub is_reboot_required: bool,
}
impl TryFrom<&IUpdate> for InfoData {
    type Error = Error;
    fn try_from(u: &IUpdate) -> Result<Self, Error> {
        let update_identity: IUpdateIdentity = unsafe {
            u.Identity()
                .context("Could not retrieve update identity from IUpdate")?
        };
        let update_id = unsafe {
            update_identity
                .UpdateID()
                .context("Could not retrieve update identity")?
                .to_string()
        };
        let revision_number = unsafe {
            update_identity.RevisionNumber().context(format!(
                "Could not retrieve revision number from update id {}",
                update_id
            ))?
        };
        let title = unsafe {
            u.Title()
                .context(format!(
                    "Could not retrieve title from update id {}",
                    update_id
                ))?
                .to_string()
        };
        let raw_kbs = unsafe {
            u.KBArticleIDs().context(format!(
                "Could not retrieve KBs from update id {}",
                update_id
            ))?
        };
        let kbs = ArticleCollection::try_from(&raw_kbs)?;
        let msrc_severity = unsafe {
            u.MsrcSeverity()
                .context(format!(
                    "Could not retrieve msrc Severity from update id {}",
                    update_id
                ))?
                .to_string()
        };
        let raw_categories = unsafe {
            u.Categories().context(format!(
                "Could not retrieve categories from update id {}",
                update_id
            ))?
        };
        let categories = CategoryCollection::try_from(&raw_categories)?;
        let is_downloaded = unsafe {
            u.IsDownloaded()
                .context(format!(
                    "Could not retrieve is_downloaded from update id {}",
                    update_id
                ))?
                .into()
        };
        let is_installed = unsafe {
            u.IsInstalled()
                .context(format!(
                    "Could not retrieve is_installed from update id {}",
                    update_id
                ))?
                .into()
        };
        let is_mandatory = unsafe {
            u.IsInstalled()
                .context(format!(
                    "Could not retrieve is_installed from update id {}",
                    update_id
                ))?
                .into()
        };
        // Try to cast the IUpdate object to IUpdate2 as it offers more methods
        let u2: IUpdate2 = u
            .cast()
            .context(format!("Could not cast update {} to IUpdate2", update_id))?;
        let is_reboot_required = unsafe {
            u2.RebootRequired()
                .context(format!(
                    "Could not retrieve reboot required value from update id {}",
                    update_id
                ))?
                .into()
        };

        // Look for the superseded updates
        let mut superseded_update_ids = vec![];
        let raw_superseded_collection: IStringCollection = unsafe {
            u.SupersededUpdateIDs().context(format!(
                "Could not retrieve superseded updates for update {}",
                update_id
            ))?
        };
        let count = unsafe {
            raw_superseded_collection
                .Count()
                .context("Failed to get superseded KB count")?
        };
        for i in 0..count {
            let superseded: String = unsafe {
                raw_superseded_collection
                    .get_Item(i)
                    .context(format!(
                        "Failed to get superseded KB ID from update {}",
                        update_id
                    ))?
                    .to_string()
            };
            superseded_update_ids.push(superseded);
        }

        Ok(InfoData {
            update_id,
            revision_number,
            title,
            kbs,
            msrc_severity,
            categories,
            is_downloaded,
            is_installed,
            is_mandatory,
            superseded_update_ids,
            is_reboot_required,
        })
    }
}

impl From<InfoData> for PackageId {
    fn from(val: InfoData) -> Self {
        PackageId {
            name: val.title,
            arch: "noarch".to_string(),
        }
    }
}
