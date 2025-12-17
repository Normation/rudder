use serde::{Deserialize, Serialize};
use std::fmt;
#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct Article {
    id: String,
}
impl Article {
    pub fn new(id: String) -> Self {
        Self { id }
    }
}
impl fmt::Display for Article {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        write!(f, "KB{}", self.id)
    }
}
