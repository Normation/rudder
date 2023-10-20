use crate::rpkg;
use anyhow::{Context, Result};
use ar::Archive;
use lzma_rs;
use std::{
    fs::{self, *},
    io::{Cursor, Read},
    path::Path,
    str,
};
use tar;

#[derive(Clone)]
pub struct Rpkg {
    path: String,
    metadata: rpkg::plugin::Metadata,
}

impl Rpkg {
    fn from_path(path: &str) -> Result<Rpkg> {
        let r = Rpkg {
            path: String::from(path),
            metadata: read_metadata(path).unwrap(),
        };
        Ok(r)
    }

    fn get_txz_dst(&self, txz_name: &str) -> String {
        // Build the destination path
        return self.metadata.content.get(txz_name).unwrap().to_string();
    }

    fn unpack_embedded_txz(&self, txz_name: &str, dst_path: &str) -> Result<(), anyhow::Error> {
        let dst = Path::new(dst_path);
        // Loop over ar archive files
        let mut archive = Archive::new(File::open(self.path.clone()).unwrap());
        while let Some(entry_result) = archive.next_entry() {
            let txz_archive = entry_result.unwrap();
            let entry_title = str::from_utf8(txz_archive.header().identifier()).unwrap();
            if entry_title != txz_name {
                continue;
            }
            let parent = dst.parent().unwrap();
            // Verify that the directory structure exists
            fs::create_dir_all(parent).map_err(|e| {
                let context = format!(
                    "Make sure the folder '{}' exists, {}",
                    parent.to_str().unwrap(),
                    e
                );
                anyhow::Error::new(e).context(context)
            })?;
            // Unpack the txz archive
            let mut unxz_archive = Vec::new();
            let mut f = std::io::BufReader::new(txz_archive);
            lzma_rs::xz_decompress(&mut f, &mut unxz_archive)?;
            let mut tar_archive = tar::Archive::new(Cursor::new(unxz_archive));
            tar_archive.unpack(dst)?;
            return Ok(());
        }
        Ok(())
    }

    pub fn install(&self) -> Result<()> {
        let keys = self.metadata.content.keys().clone();
        for txz_name in keys {
            let dst = self.get_txz_dst(&txz_name);
            match self.unpack_embedded_txz(&txz_name, &dst) {
                Err(err) => panic!("{}", err),
                Ok(_) => (),
            }
        }
        Ok(())
    }
}

fn read_metadata(path: &str) -> Result<rpkg::plugin::Metadata> {
    let mut archive = Archive::new(File::open(path).unwrap());
    while let Some(entry_result) = archive.next_entry() {
        let mut entry = entry_result.unwrap();
        let mut buffer = String::new();
        let entry_title = str::from_utf8(entry.header().identifier()).unwrap();
        if entry_title == "metadata" {
            let _ = entry.read_to_string(&mut buffer)?;
            let m: rpkg::plugin::Metadata = serde_json::from_str(&buffer)
                .with_context(|| format!("Failed to parse {} metadata", path))?;
            return Ok(m);
        };
    }
    anyhow::bail!("No metadata found in {}", path);
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path;
    use tempfile::{tempdir, TempDir};
    extern crate dir_diff;

    #[test]
    fn test_read_rpkg_metadata() {
        assert!(read_metadata("./tests/malformed_metadata.rpkg").is_err());
        assert!(read_metadata("./tests/without_metadata.rpkg").is_err());
        read_metadata("./tests/with_metadata.rpkg").unwrap();
    }

    #[test]
    fn test_extract_txz_from_rpkg() {
        let bind;
        let r = Rpkg::from_path("./tests/archive/rudder-plugin-notify-8.0.0-2.2.rpkg").unwrap();
        let expected_dir_content = "./tests/archive/expected_dir_content";
        let effective_target = {
            let real_unpack_target = r.get_txz_dst("files.txz");
            let trimmed = Path::new(&real_unpack_target).strip_prefix("/").unwrap();
            bind = tempdir().unwrap().into_path().join(trimmed);
            bind.to_str().unwrap()
        };
        r.unpack_embedded_txz("files.txz", effective_target)
            .unwrap();
        assert!(!dir_diff::is_different(effective_target, expected_dir_content).unwrap());
    }
}
