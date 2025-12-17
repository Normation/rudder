use crate::r_com_update::CategoryCollection;
use crate::r_com_update::kb::ArticleCollection;
use anyhow::Error;
use anyhow::Result;
use serde::{Deserialize, Deserializer, Serialize};
use std::fmt;
use std::fmt::Formatter;
use windows::Win32::System::UpdateAgent::*;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Info {
    #[serde(skip)]
    pub com_ptr: Option<IUpdate>,
    #[serde(flatten)]
    pub data: InfoData,
}
impl Info {
    pub fn try_from_com(u: IUpdate) -> Result<Self, Error> {
        let data = InfoData::try_from_com(&u)?;
        Ok(Self {
            com_ptr: Some(u),
            data,
        })
    }
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct InfoData {
    update_id: String,
    revision_number: i32,
    title: String,
    kbs: ArticleCollection,
    msrc_severity: String,
    categories: CategoryCollection,
    #[serde(skip)]
    is_downloaded: bool,
    #[serde(skip)]
    is_installed: bool,
    is_mandatory: bool,
    superseded_update_ids: Vec<String>,
}
impl InfoData {
    pub fn try_from_com(u: &IUpdate) -> Result<Self, Error> {
        unsafe {
            let update_identity: IUpdateIdentity = u.Identity()?;
            Ok(Self {
                update_id: update_identity.UpdateID()?.to_string(),
                revision_number: update_identity.RevisionNumber()?,
                title: u.Title()?.to_string(),
                kbs: ArticleCollection::try_from_com(&u.KBArticleIDs()?)?,
                msrc_severity: u.MsrcSeverity()?.to_string(),
                categories: CategoryCollection::try_from_com(&u.Categories()?)?,
                is_downloaded: u.IsDownloaded()?.as_bool(),
                is_installed: u.IsInstalled()?.as_bool(),
                is_mandatory: u.IsMandatory()?.as_bool(),
                superseded_update_ids: vec![],
            })
        }
    }
}
