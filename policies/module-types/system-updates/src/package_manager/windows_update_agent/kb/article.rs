use serde::{Deserialize, Serialize};
#[derive(Debug, Clone, Deserialize, Serialize, PartialEq)]
pub struct Article(String);
impl Article {
    pub fn new(id: String) -> Self {
        Self(format!("KB{}", id))
    }
}
