// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

use serde::{Deserialize, Serialize};
use std::fmt::Display;
use windows::core::HRESULT;

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct RudderHRESULT {
    pub code: i32,
    pub message: String,
}

impl Display for RudderHRESULT {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::result::Result<(), std::fmt::Error> {
        write!(f, "HRESULT 0x{:08X} {}", self.code, self.message)
    }
}
impl From<HRESULT> for RudderHRESULT {
    fn from(hr: HRESULT) -> Self {
        RudderHRESULT {
            code: hr.0,
            message: hr.message(),
        }
    }
}
