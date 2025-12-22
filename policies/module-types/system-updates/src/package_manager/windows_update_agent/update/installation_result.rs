use super::{Collection, InfoData, OperationResultCode};
use anyhow::{Error, Result, bail};
use serde::{Deserialize, Serialize};
use windows::Win32::System::UpdateAgent::{IInstallationResult, IUpdateInstallationResult};

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct UpdateInstallationResult {
    pub h_result: i32,
    pub result_code: OperationResultCode,
    pub reboot_required: bool,
    pub update: InfoData,
}

impl UpdateInstallationResult {
    pub fn try_from_com(
        r: IUpdateInstallationResult,
        u: InfoData,
    ) -> Result<UpdateInstallationResult, Error> {
        unsafe {
            Ok(Self {
                h_result: r.HResult()?,
                result_code: OperationResultCode::new(r.ResultCode()?.0)?,
                reboot_required: r.RebootRequired()?.as_bool(),
                update: u,
            })
        }
    }
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct InstallationResult {
    pub h_result: i32,
    pub result_code: OperationResultCode,
    pub reboot_required: bool,
    pub update_results: Vec<UpdateInstallationResult>,
}

impl InstallationResult {
    pub fn try_from_com(
        r: IInstallationResult,
        collection: &Collection,
    ) -> Result<InstallationResult, Error> {
        unsafe {
            Ok(Self {
                h_result: r.HResult()?,
                result_code: OperationResultCode::new(r.ResultCode()?.0)?,
                reboot_required: r.RebootRequired()?.as_bool(),
                update_results: (0..collection.updates.len())
                    .map(|i| {
                        let info = match collection.updates.get(i) {
                            Some(info) => info.data.clone(),
                            None => bail!("Could not retrieve update info for index {}", i),
                        };
                        UpdateInstallationResult::try_from_com(r.GetUpdateResult(i as i32)?, info)
                    })
                    .collect::<Result<Vec<UpdateInstallationResult>>>()?,
            })
        }
    }
}
