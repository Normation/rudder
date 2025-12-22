use anyhow::{Error, Result};
use serde::{Deserialize, Serialize};

//https://learn.microsoft.com/en-us/windows/win32/api/wuapi/ne-wuapi-operationresultcode
#[derive(Debug, Clone, Deserialize, Serialize)]
pub enum OperationResultCode {
    OrcNoStarted = 0,
    OrcInProgress = 1,
    OrcSucceeded = 2,
    OrcSucceededWithErrors = 3,
    OrcFailed = 4,
    OrcAborted = 5,
}

impl OperationResultCode {
    pub fn new(code: i32) -> Result<OperationResultCode> {
        match code {
            0 => Ok(OperationResultCode::OrcNoStarted),
            1 => Ok(OperationResultCode::OrcInProgress),
            2 => Ok(OperationResultCode::OrcSucceeded),
            3 => Ok(OperationResultCode::OrcFailed),
            4 => Ok(OperationResultCode::OrcAborted),
            _ => Err(Error::msg("unknown operation result code")),
        }
    }
}
