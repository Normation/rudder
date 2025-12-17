// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS
use anyhow::{Error, Result};
use serde::{Deserialize, Serialize};

//https://learn.microsoft.com/en-us/windows/win32/api/wuapi/ne-wuapi-operationresultcode
#[derive(Debug, Clone, Copy, Deserialize, Serialize)]
pub enum OperationResultCode {
    NoStarted = 0,
    InProgress = 1,
    Succeeded = 2,
    SucceededWithErrors = 3,
    Failed = 4,
    Aborted = 5,
}

impl OperationResultCode {
    pub fn new(code: i32) -> Result<OperationResultCode> {
        match code {
            0 => Ok(OperationResultCode::NoStarted),
            1 => Ok(OperationResultCode::InProgress),
            2 => Ok(OperationResultCode::Succeeded),
            3 => Ok(OperationResultCode::Failed),
            4 => Ok(OperationResultCode::Aborted),
            _ => Err(Error::msg("unknown operation result code")),
        }
    }
}

impl std::fmt::Display for OperationResultCode {
    fn fmt(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result {
        match self {
            Self::NoStarted => write!(f, "0 (OrcNoStarted)"),
            Self::InProgress => write!(f, "1 (OrcInProgress)"),
            Self::Succeeded => write!(f, "2 (OrcSucceeded)"),
            Self::SucceededWithErrors => write!(f, "3 (OrcSucceededWithErrors)"),
            Self::Failed => write!(f, "4 (OrcFailed)"),
            Self::Aborted => write!(f, "5 (OrcAborted)"),
        }
    }
}
