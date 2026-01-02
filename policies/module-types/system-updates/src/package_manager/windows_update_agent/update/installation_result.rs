use std::collections::HashMap;
// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS
use super::{Collection, InfoData, OperationResultCode};
use crate::package_manager::PackageId;
use anyhow::{Error, Result, bail};
use serde::{Deserialize, Serialize};
use std::fmt::Display;
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

impl Display for UpdateInstallationResult {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(
            f,
            "h_result: {}, result_code: {}, reboot_required: {}",
            self.h_result, self.result_code, self.reboot_required
        )
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
    pub fn get_details(&self, collection: &Collection) -> Result<HashMap<PackageId, String>> {
        if self.update_results.len() != collection.len() {
            bail!(
                "Given input collection and install result length do not match: got {} input length and {} results",
                self.update_results.len(),
                collection.len()
            )
        }
        let mut h = HashMap::new();
        for i in 0..collection.len() {
            h.insert(
                PackageId::from(collection[i].data.clone()),
                format!("\nInstall result:\n{}", self.update_results[i].clone()),
            );
        }
        Ok(h)
    }
    pub fn try_from_com(
        r: IInstallationResult,
        collection: &Collection,
    ) -> Result<InstallationResult, Error> {
        unsafe {
            Ok(Self {
                h_result: r.HResult()?,
                result_code: OperationResultCode::new(r.ResultCode()?.0)?,
                reboot_required: r.RebootRequired()?.as_bool(),
                update_results: (0..collection.len())
                    .map(|i| {
                        let info = match collection.get(i) {
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
