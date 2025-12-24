use crate::campaign::FullCampaignType;
use crate::output::ResultOutput;
use crate::package_manager::{
    LinuxPackageManager, PackageId, PackageInfo, PackageList, PackageManager,
};
use anyhow::{Context, Result, bail};
use std::collections::{HashMap, HashSet};
use std::fmt::format;
use windows::Win32::Foundation::VARIANT_BOOL;
use windows::Win32::System::Com::{
    CLSCTX_INPROC_SERVER, COINIT_MULTITHREADED, CoCreateInstance, CoInitializeEx,
};
use windows::Win32::System::UpdateAgent::{
    ISystemInformation, IUpdate, IUpdateCollection, IUpdateDownloader, IUpdateInstallationResult,
    IUpdateInstaller, IUpdateSession, SystemInformation, UpdateCollection, UpdateSession,
};
use windows::core::{BSTR, h};

mod kb;
mod update;

use crate::package_manager::windows_update_agent::update::{
    Collection, InfoData, InstallationResult, UpdateDownloadResult, UpdateInstallationResult,
};
use crate::package_manager::windows_update_agent::update::{DownloadResult, OperationResultCode};

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

fn try_iupdatecollection_from_vec(v: Vec<IUpdate>) -> Result<IUpdateCollection> {
    unsafe {
        let c: IUpdateCollection = CoCreateInstance(&UpdateCollection, None, CLSCTX_INPROC_SERVER)?;
        for update in v {
            let _ = c.Add(&update)?;
        }
        Ok(c)
    }
}

fn query_wua(session: &IUpdateSession, query: &str) -> ResultOutput<Collection> {
    let mut r = ResultOutput::new(Ok(Collection::new()));
    r.stdout("Creating the UpdateSearcher".to_string());
    let raw_searcher = unsafe { session.CreateUpdateSearcher() };
    let searcher = match raw_searcher {
        Err(e) => {
            r.stderr(format!("CreateUpdateSearcher failed: {}", e));
            return r;
        }
        Ok(s) => s,
    };
    r.stdout(format!(
        "Searching for updates managed by WUA using query '{}'",
        query
    ));
    let raw_search_result = unsafe { searcher.Search(&BSTR::from(query)) };
    let search_result = match raw_search_result {
        Err(e) => {
            r.stderr(format!("Search failed: {}", e));
            return r.into_err();
        }
        Ok(rsr) => rsr,
    };
    let raw_com_updates = unsafe { search_result.Updates() };
    let com_updates = match raw_com_updates {
        Err(e) => {
            r.stderr(format!(
                "Could not retrieve updates from the COM API result: {}",
                e
            ));
            return r.into_err();
        }
        Ok(cu) => cu,
    };
    ResultOutput {
        inner: Collection::try_from_com(com_updates),
        stderr: r.stderr,
        stdout: r.stdout,
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

fn download_updates(
    session: &IUpdateSession,
    collection: &Collection,
) -> ResultOutput<DownloadResult> {
    let mut r = ResultOutput::new(Ok(DownloadResult {
        h_result: 1,
        result_code: OperationResultCode::OrcNoStarted,
        update_results: vec![],
    }));
    /// Retrieve the update collection to dl
    let com_ptr = match &collection.com_ptr {
        None => {
            r.stderr("No IUpdateCollection found to download".to_string());
            return r;
        }
        Some(com_ptr) => com_ptr,
    };
    /// Prepare the downloader
    let downloader_setup = unsafe { session.CreateUpdateDownloader() };
    let downloader = match downloader_setup {
        Err(e) => {
            r.stderr(format!("Could not create the IUpdateDownloader: {}", e));
            return r;
        }
        Ok(d) => d,
    };
    /// Set the collection to the downloader
    let set_result = unsafe { downloader.SetUpdates(com_ptr) };
    if let Err(e) = set_result {
        r.stderr(format!(
            "Could not set the update collection to download: {}",
            e
        ));
        return r;
    };
    /// Log each ready to download package
    r.stdout.push(format!(
        "The following {} updates will be downloaded:",
        collection.updates.len()
    ));
    collection.updates.iter().for_each(|u| {
        r.stdout(format!("{}, ", u.data.title));
    });
    /// Download the updates
    let raw_download_result = unsafe { downloader.Download() };
    match raw_download_result {
        Err(e) => {
            r.stderr(format!("Could not download the updates: {}", e));
            return r.into_err();
        }
        Ok(rdr) => ResultOutput {
            inner: DownloadResult::try_from_com(rdr, collection),
            stderr: r.stderr,
            stdout: r.stdout,
        },
    }
}

fn install_updates(
    session: &IUpdateSession,
    collection: &Collection,
) -> ResultOutput<InstallationResult> {
    let mut r = ResultOutput::new(Ok(InstallationResult {
        h_result: 1,
        result_code: OperationResultCode::OrcNoStarted,
        reboot_required: false,
        update_results: vec![],
    }));
    let installer_setup = unsafe { session.CreateUpdateInstaller() };
    let installer = match installer_setup {
        Err(e) => {
            r.stderr(format!("Could not create the IUpdateInstaller: {}", e));
            return r;
        }
        Ok(d) => d,
    };
    /// Retrieve the update collection to install
    let com_ptr = match &collection.com_ptr {
        None => {
            r.stderr("No IUpdateCollection found to install".to_string());
            return r;
        }
        Some(com_ptr) => com_ptr,
    };
    /// Set the collection to the downloader
    let set_result = unsafe { installer.SetUpdates(com_ptr) };
    if let Err(e) = set_result {
        r.stderr(format!(
            "Could not set the update collection to install: {}",
            e
        ));
        return r;
    };
    /// Set the installer settings
    let set_settings = unsafe { installer.SetAllowSourcePrompts(VARIANT_BOOL::from(false)) };
    if let Err(e) = set_settings {
        r.stderr(format!(
            "Could not set the update installer AllowSourcePrompts settings to false: {}",
            e
        ));
        return r;
    }
    /// Log each ready to install update
    r.stdout.push(format!(
        "The following {} downloaded updates will be installed:",
        collection.updates.len()
    ));
    collection.updates.iter().for_each(|u| {
        r.stdout(format!("{}, ", u.data.title));
    });
    /// Install the updates
    let raw_install_result = unsafe { installer.Install() };
    match raw_install_result {
        Err(e) => {
            r.stderr(format!("Could not install the updates: {}", e));
            return r.into_err();
        }
        Ok(rir) => ResultOutput {
            inner: InstallationResult::try_from_com(rir, collection),
            stderr: r.stderr,
            stdout: r.stdout,
        },
    }
}

pub struct WindowsUpdateAgent {}

impl LinuxPackageManager for WindowsUpdateAgent {
    fn list_installed(&mut self) -> ResultOutput<PackageList> {
        let mut r = ResultOutput::new(Ok(PackageList::new(HashMap::new())));
        let result_session = initialize_com();
        let session = match result_session.inner {
            Err(_) => return r.step(result_session.into_err()),
            Ok(session) => session,
        };
        let updates = match query_wua(&session, "IsInstalled=1").inner {
            Err(e) => {
                r.stderr(format!("Failed to retrieve installed updates {}", e));
                return r.into_err();
            }
            Ok(u) => u,
        };
        r.stdout("Look for installed packages".to_string());
        r.stdout("Available updates:".to_string());
        updates
            .iter()
            .for_each(|u| match serde_json::to_string(&u) {
                Err(e) => {
                    r.stderr(format!(
                        "Could not serialize update '{}': {}",
                        u.data.title, e
                    ));
                    r.stdout(format!("  <{}>", u.data.title));
                }
                Ok(s) => r.stdout(format!("  {}", s)),
            });
        ResultOutput {
            inner: Ok(updates_to_package_list(updates)),
            stdout: r.stdout,
            stderr: r.stderr,
        }
    }

    fn upgrade(&mut self, update_type: &FullCampaignType) -> ResultOutput<()> {
        let mut r = ResultOutput::new(Ok(()));
        /// Get a COM session
        let result_session = initialize_com();
        let session = match result_session.inner {
            Err(_) => return r.step(result_session.into_err()),
            Ok(session) => session,
        };
        /// Compute the updates to install
        let raw_updates_to_download = query_wua(&session, "IsInstalled=0");
        r.log_step(&raw_updates_to_download);
        let updates_to_download = match raw_updates_to_download.inner {
            Err(ref e) => {
                r.stderr(format!("Failed to retrieve available updates {}", e));
                return r.step(raw_updates_to_download.into_err());
            }
            Ok(u) => u,
        };
        /// Early return if everything is up to date
        if updates_to_download.updates.is_empty() {
            r.stdout("No Updates found to install".to_string());
            return r;
        }
        /// Download the available updates
        let raw_update_download_result = download_updates(&session, &updates_to_download);
        r.log_step(&raw_update_download_result);
        let update_download_result = match raw_update_download_result.inner {
            Err(e) => {
                r.stderr(format!("Failed to retrieve download status result: {}", e));
                return r;
            }
            Ok(x) => x,
        };
        /// Log the download result
        let (d_succeeded, d_failed): (
            Vec<UpdateDownloadResult>,
            Vec<UpdateDownloadResult>,
        ) = update_download_result
            .update_results
            .into_iter()
            .partition(|update| matches!(update.result_code, OperationResultCode::OrcSucceeded));
        r.stdout("Successfully installed updates:".to_string());
        if d_succeeded.is_empty() {
            r.stdout(" - None".to_string());
        } else {
            d_succeeded
                .iter()
                .for_each(|u| r.stdout(format!(" - {}", u.update.title)));
        };
        r.stdout("Failed installed updates:".to_string());
        if d_succeeded.is_empty() {
            r.stdout(" - None".to_string());
        } else {
            d_failed
                .iter()
                .for_each(|u| r.stdout(format!(" - {} -> {}", u.update.title, u.h_result)));
        };
        r.stdout(format!(
            "Download summary: {} success, {} failed, {} total",
            d_succeeded.len(),
            d_failed.len(),
            updates_to_download.updates.len()
        ));
        /// Early return if 0 download succeeded
        if d_succeeded.is_empty() {
            r.stderr("No updates could be downloaded, exiting".to_string());
            return r.into_err();
        }

        /// Else proceed to the installation of the successfull updates
        /// Build a collection of the successfully downloaded installs
        let downloaded_data = d_succeeded
            .iter()
            .map(|r| r.update.clone())
            .collect::<Vec<InfoData>>();
        let raw_to_install = try_iupdatecollection_from_vec(
            updates_to_download
                .updates
                .iter()
                .filter(|u| downloaded_data.contains(&u.data))
                .map(|u| u.com_ptr.clone())
                .flatten()
                .collect::<Vec<IUpdate>>(),
        );

        let com_to_install = match raw_to_install {
            Err(e) => {
                r.stderr(format!("Failed to build the collection of updates to install from the successfully downloaded ones: {}", e));
                return r.into_err();
            }
            Ok(rti) => rti,
        };
        // Make it a Collection
        let updates_to_install = match Collection::try_from_com(com_to_install) {
            Err(e) => {
                r.stderr(format!("Failed to build the collection of updates to install from the successfully downloaded ones: {}", e));
                return r.into_err();
            }
            Ok(c) => c,
        };
        let raw_install_result = install_updates(&session, &updates_to_install);
        r.log_step(&raw_install_result);
        let update_install_result = match raw_install_result.inner {
            Err(e) => {
                r.stderr(format!("Failed to retrieve install status result: {}", e));
                return r;
            }
            Ok(x) => x,
        };
        /// Log the download result
        let (i_succeeded, i_failed): (
            Vec<UpdateInstallationResult>,
            Vec<UpdateInstallationResult>,
        ) = update_install_result
            .update_results
            .into_iter()
            .partition(|update| matches!(update.result_code, OperationResultCode::OrcSucceeded));
        r.stdout("Successfully installed updates:".to_string());
        if i_succeeded.is_empty() {
            r.stdout(" - None".to_string());
        } else {
            i_succeeded
                .iter()
                .for_each(|u| r.stdout(format!(" - {}", u.update.title)));
        };
        r.stdout("Failed installed updates:".to_string());
        if i_succeeded.is_empty() {
            r.stdout(" - None".to_string());
        } else {
            i_failed
                .iter()
                .for_each(|u| r.stdout(format!(" - {} -> {}", u.update.title, u.h_result)));
        };
        r.stdout(format!(
            "Install summary: {} success, {} failed, {} total",
            i_succeeded.len(),
            i_failed.len(),
            updates_to_install.updates.len()
        ));
        ResultOutput {
            inner: Ok(()),
            stdout: r.stdout,
            stderr: r.stderr,
        }
    }

    fn reboot_pending(&self) -> ResultOutput<bool> {
        let mut r = ResultOutput::new(Ok(true));
        let raw_system_info =
            unsafe { CoCreateInstance(&SystemInformation, None, CLSCTX_INPROC_SERVER) };
        let system_info: ISystemInformation = match raw_system_info {
            Err(e) => {
                r.stderr(format!("Could not retrieve the SystemInformation object to detect if a reboot is required: {}", e));
                return r.into_err();
            }
            Ok(s) => s,
        };
        let raw_reboot_required = unsafe { system_info.RebootRequired() };
        match raw_reboot_required {
            Err(e) => {
                r.stderr(format!(
                    "Could not detect if the system requires a reboot or not: {}",
                    e
                ));
                return r.into_err();
            }
            Ok(b) => ResultOutput {
                inner: Ok(b.as_bool()),
                stderr: r.stderr,
                stdout: r.stdout,
            },
        }
    }

    fn services_to_restart(&self) -> ResultOutput<Vec<String>> {
        /// No support on Windows for now
        ResultOutput {
            inner: Ok(vec![]),
            stderr: vec![],
            stdout: vec![],
        }
    }
}
