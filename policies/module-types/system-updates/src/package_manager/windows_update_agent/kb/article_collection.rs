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
    pub fn try_from_com(c: &IStringCollection) -> Result<ArticleCollection, anyhow::Error> {
        unsafe {
            let count = c.Count().context("Failed to get KB count")?;
            let mut kbs = ArticleCollection::new();
            for i in 0..count {
                kbs.0.push(Article::new(
                    c.get_Item(i).context("Failed to get KB ID")?.to_string(),
                ));
            }
            Ok(kbs)
        }
    }
}

impl Deref for ArticleCollection {
    type Target = Vec<Article>;
    fn deref(&self) -> &Self::Target {
        &self.0
    }
}
