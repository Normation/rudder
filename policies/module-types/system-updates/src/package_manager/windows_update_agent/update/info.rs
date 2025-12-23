use super::super::kb::ArticleCollection;
use super::CategoryCollection;
use anyhow::Error;
use anyhow::Result;
use serde::{Deserialize, Serialize};
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
