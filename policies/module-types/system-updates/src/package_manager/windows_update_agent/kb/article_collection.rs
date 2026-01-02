// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS
use super::article::Article;
use anyhow;
use anyhow::Context;
use serde::{Deserialize, Serialize};
use std::ops::Deref;
use windows::Win32::System::UpdateAgent::IStringCollection;
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ArticleCollection(Vec<Article>);

impl ArticleCollection {
    pub fn new() -> Self {
        ArticleCollection(Vec::new())
    }
}

impl TryFrom<&IStringCollection> for ArticleCollection {
    type Error = anyhow::Error;
    fn try_from(s: &IStringCollection) -> Result<ArticleCollection, Self::Error> {
        let mut kbs = ArticleCollection::new();
        let count = unsafe { s.Count().context("Failed to get KB count")? };
        for i in 0..count {
            let article =
                Article::new(unsafe { s.get_Item(i).context("Failed to get KB ID")?.to_string() })?;
            kbs.0.push(article);
        }
        Ok(kbs)
    }
}

impl Deref for ArticleCollection {
    type Target = Vec<Article>;
    fn deref(&self) -> &Self::Target {
        &self.0
    }
}
