use crate::r_com_update::{Collection, DownloadResult, InstallationResult};
use anyhow::Context;
use anyhow::{Result, bail};
use windows::Win32::Foundation::VARIANT_BOOL;
use windows::{Win32::System::Com::*, Win32::System::UpdateAgent::*, core::*};

pub mod r_com_update;

fn list_updates(session: &IUpdateSession) -> Result<Collection> {
    unsafe {
        let searcher = session.CreateUpdateSearcher()?;
        let search_result = searcher.Search(&BSTR::from("IsInstalled=0"))?;
        let updates = Collection::try_from_com(search_result.Updates()?)?;
        Ok(updates)
    }
}

fn download_updates(session: &IUpdateSession, collection: &Collection) -> Result<DownloadResult> {
    unsafe {
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

fn install_updates(
    session: &IUpdateSession,
    collection: &Collection,
) -> Result<InstallationResult> {
    unsafe {
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

fn main() {
    unsafe {
        let _ = CoInitializeEx(None, COINIT_MULTITHREADED);
        // The code that creates and manages objects of this class is a DLL that runs in the same process as the caller of the function specifying the class context.
        let session: IUpdateSession =
            CoCreateInstance(&UpdateSession, None, CLSCTX_INPROC_SERVER).unwrap();

        println!("Looking for available updates...");
        let availables_updates = &list_updates(&session).unwrap();
        availables_updates.iter().for_each(|u| {
            println!("{}", serde_json::to_string(&u).unwrap());
        });
        println!("");
        println!("Downloading available updates...");
        let download_result = download_updates(&session, availables_updates).unwrap();
        println!(
            "{}",
            serde_json::to_string_pretty(&download_result).unwrap()
        );
        let install_result = install_updates(&session, availables_updates).unwrap();
        println!("Installing available updates...");
        println!("{}", serde_json::to_string_pretty(&install_result).unwrap());
    }
}
