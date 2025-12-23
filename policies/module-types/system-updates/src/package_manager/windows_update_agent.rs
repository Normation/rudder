use crate::campaign::FullCampaignType;
use crate::output::ResultOutput;
use crate::package_manager::{
    LinuxPackageManager, PackageId, PackageInfo, PackageList, PackageManager,
};
use anyhow::{Context, Result, bail};
use std::collections::HashMap;
use windows::Win32::Foundation::VARIANT_BOOL;
use windows::Win32::System::Com::{
    CLSCTX_INPROC_SERVER, COINIT_MULTITHREADED, CoCreateInstance, CoInitializeEx,
};
use windows::Win32::System::UpdateAgent::{
    IUpdateDownloader, IUpdateInstaller, IUpdateSession, UpdateSession,
};
use windows::core::BSTR;

mod kb;
mod update;

use crate::package_manager::windows_update_agent::update::DownloadResult;
use crate::package_manager::windows_update_agent::update::{Collection, InstallationResult};

fn initialize_com() -> ResultOutput<IUpdateSession> {
    let mut stdout = Vec::new();
    let mut stderr = Vec::new();
    let inner = (|| -> Result<IUpdateSession> {
        unsafe {
            stdout.push("Initializing the COM API".to_string());
            let _ = CoInitializeEx(None, COINIT_MULTITHREADED);
            stdout.push("Initializing a IUpdateSession object".to_string());
            // The code that creates and manages objects of this class is a DLL that runs in the same process as the caller of the function specifying the class context.
            let session: IUpdateSession =
                CoCreateInstance(&UpdateSession, None, CLSCTX_INPROC_SERVER).map_err(|e| {
                    stderr.push(format!(
                        "Failed to instanciate an IUpdateSession object: {}",
                        e
                    ));
                    e
                })?;
            Ok(session)
        }
    })();
    ResultOutput {
        inner,
        stdout,
        stderr,
    }
}

pub struct WindowsUpdateAgent {}

impl WindowsUpdateAgent {
    fn download_updates(self, collection: &Collection) -> Result<DownloadResult> {
        unsafe {
            let session = initialize_com().inner?;
            let downloader: IUpdateDownloader = session.CreateUpdateDownloader()?;
            match &collection.com_ptr {
                None => bail!("No IUpdateCollection found to download"),
                Some(c) => {
                    downloader
                        .SetUpdates(c)
                        .context("Failed to set updates to IUpdateDownloader")?;
                    let download_result = downloader
                        .Download()
                        .context("Failed to download updates")?;
                    DownloadResult::try_from_com(download_result, collection)
                }
            }
        }
    }

    fn install_updates(collection: &Collection) -> Result<InstallationResult> {
        unsafe {
            let session = initialize_com().inner?;
            let installer: IUpdateInstaller = session.CreateUpdateInstaller()?;
            match &collection.com_ptr {
                None => bail!("No IUpdateCollection found to install"),
                Some(c) => {
                    installer
                        .SetUpdates(c)
                        .context("Failed to set IUpdateInstaller")?;
                    installer
                        .SetAllowSourcePrompts(VARIANT_BOOL::from(false))
                        .context("Failed to set IUpdateInstaller's AllowSourcePrompts")?;
                    InstallationResult::try_from_com(installer.Install()?, collection)
                }
            }
        }
    }

    fn updates_to_package_list(c: Collection) -> PackageList {
        let h: HashMap<PackageId, PackageInfo> = c
            .updates
            .iter()
            .map(|u| {
                let id = PackageId {
                    name: u.data.title.clone(),
                    arch: "noarch".to_string(),
                };
                (
                    id,
                    PackageInfo {
                        version: "none:none".to_string(),
                        from: "".to_string(),
                        source: PackageManager::WindowsUpdateAgent,
                    },
                )
            })
            .collect();
        PackageList::new(h)
    }
}
impl LinuxPackageManager for WindowsUpdateAgent {
    fn list_installed(&mut self) -> ResultOutput<PackageList> {
        let mut stdout = Vec::new();
        let mut stderr = Vec::new();
        let inner = (|| -> Result<PackageList> {
            unsafe {
                let result_session = initialize_com();
                let session = match result_session.inner {
                    Err(e) => {
                        stdout.extend(result_session.stdout);
                        stderr.extend(result_session.stderr);
                        bail!(e)
                    }
                    Ok(session) => session,
                };
                stdout.push("Creating the UpdateSearcher".to_string());
                let searcher = session.CreateUpdateSearcher().map_err(|e| {
                    stderr.push(format!("CreateUpdateSearcher failed: {}", e));
                    e
                })?;
                let query = "IsInstalled=0";
                stdout.push(format!(
                    "Searching for available updates using query '{}'",
                    query
                ));
                let search_result = searcher.Search(&BSTR::from(query))?;
                let com_updates = search_result.Updates().map_err(|e| {
                    stderr.push(format!(
                        "Failed to retrieve updates from the COM API: {}",
                        e
                    ));
                    e
                })?;
                let updates: Collection = Collection::try_from_com(com_updates).map_err(|e| {
                    stderr.push(format!(
                        "Failed to convert the COM updates to safe objects; {}",
                        e
                    ));
                    e
                })?;
                stdout.push("Available updates:".to_string());
                updates
                    .iter()
                    .for_each(|u| match serde_json::to_string(&u) {
                        Ok(s) => stdout.push(format!("  {}", s)),
                        Err(e) => {
                            stderr.push(format!(
                                "Could not serialize update '{}': {}",
                                u.data.title, e
                            ));
                            stdout.push(format!("  <{}>", u.data.title));
                        }
                    });
                Ok(Self::updates_to_package_list(updates))
            }
        })();
        ResultOutput {
            inner,
            stdout,
            stderr,
        }
    }

    fn upgrade(&mut self, update_type: &FullCampaignType) -> ResultOutput<()> {
        todo!()
    }

    fn reboot_pending(&self) -> ResultOutput<bool> {
        todo!()
    }

    fn services_to_restart(&self) -> ResultOutput<Vec<String>> {
        todo!()
    }
}
