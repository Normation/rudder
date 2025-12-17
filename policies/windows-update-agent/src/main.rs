use crate::r_com_update::{Collection, update};
use anyhow::Context;
use anyhow::{Result, bail};
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

fn download_updates(session: &IUpdateSession, collection: &Collection) -> Result<()> {
    unsafe {
        let downloader = session.CreateUpdateDownloader()?;
        match &collection.com_ptr {
            None => bail!("No IUpdateCollection found to download"),
            Some(c) => {
                downloader
                    .SetUpdates(c)
                    .context("Failed to set updates to IUpdateDownloader")?;
                let download_result = downloader
                    .Download()
                    .context("Failed to download updates")?;
                for i in 0..c.Count()? {
                    let r = download_result.GetUpdateResult(i)?;
                    let update = collection.get(i as usize).unwrap();
                    println!("{} - {} {}", update, r.HResult()?, r.ResultCode()?.0)
                }
            }
        }
        Ok(())
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
            println!("{}", u);
        });
        println!("");
        println!("Downloading available updates...");
        download_updates(&session, availables_updates).unwrap()
    }
}
