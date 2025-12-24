use super::{Collection, InfoData, OperationResultCode};
use anyhow::{Error, Result, bail};
use serde::{Deserialize, Serialize};
use std::fmt::Display;
use windows::Win32::System::UpdateAgent::{IDownloadResult, IUpdateDownloadResult};
#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct UpdateDownloadResult {
    pub h_result: i32,
    pub result_code: OperationResultCode,
    pub update: InfoData,
}

impl UpdateDownloadResult {
    pub fn try_from_com(
        r: IUpdateDownloadResult,
        u: InfoData,
    ) -> Result<UpdateDownloadResult, Error> {
        unsafe {
            Ok(Self {
                h_result: r.HResult()?,
                result_code: OperationResultCode::new(r.ResultCode()?.0)?,
                update: u,
            })
        }
    }
}

impl Display for UpdateDownloadResult {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(
            f,
            "h_result: {}, result_code: {}",
            self.h_result, self.result_code
        )
    }
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct DownloadResult {
    pub h_result: i32,
    pub result_code: OperationResultCode,
    pub update_results: Vec<UpdateDownloadResult>,
}

impl DownloadResult {
    pub fn try_from_com(
        r: IDownloadResult,
        collection: &Collection,
    ) -> Result<DownloadResult, Error> {
        unsafe {
            Ok(Self {
                h_result: r.HResult()?,
                result_code: OperationResultCode::new(r.ResultCode()?.0)?,
                update_results: (0..collection.updates.len())
                    .map(|i| {
                        let info = match collection.updates.get(i) {
                            Some(info) => info.data.clone(),
                            None => bail!("Could not retrieve update info for index {}", i),
                        };
                        UpdateDownloadResult::try_from_com(r.GetUpdateResult(i as i32)?, info)
                    })
                    .collect::<Result<Vec<UpdateDownloadResult>>>()?,
            })
        }
    }
}
