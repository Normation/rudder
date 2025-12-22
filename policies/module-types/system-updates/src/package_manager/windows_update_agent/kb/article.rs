use serde::{Deserialize, Serialize};
#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct Article(String);
impl Article {
    pub fn new(id: String) -> Self {
        Self(format!("KB{}", id))
    }
}
