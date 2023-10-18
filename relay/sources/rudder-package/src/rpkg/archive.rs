use crate::rpkg;
use anyhow::{bail, Context, Result};
use ar::Archive;
use std::{
    fs::{self, *},
    io::{self, Read, Write, Cursor},
    path::Path,
    str,
};
use lzma_rs;
use tar;

#[derive(Clone)]
struct Rpkg {
    path: String,
    metadata: rpkg::plugin::Metadata,
}

impl Rpkg {
    fn from_path(path: &str) -> Result<Rpkg> {
        let r = Rpkg {
            path: String::from(path),
            metadata: read_metadata(path)?,
        };
        Ok(r)
    }

    fn unpack_embedded_txz(&self, txz_name: &str) -> Result<(), anyhow::Error> {
        // Build the destination path
        let raw_dst = self.metadata.content.get(txz_name).unwrap();
        let dst = Path::new("/tmp")
            .join(Path::new(raw_dst).strip_prefix("/").unwrap());

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
            lzma_rs::xz_decompress(&mut f,&mut unxz_archive)?;
            let mut tar_archive = tar::Archive::new(Cursor::new(unxz_archive));
            tar_archive.unpack(dst)?;
            return Ok(());
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

pub fn install(path: &str) -> Result<()> {
    let r = Rpkg::from_path(path)?;
    for (txz_name, _dst) in r.metadata.clone().content {
        match r.unpack_embedded_txz(&txz_name) {
            Err(err) => panic!("{}", err),
            Ok(v) => (),
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_read_rpkg_metadata() {
        assert!(read_metadata("./tests/malformed_metadata.rpkg").is_err());
        assert!(read_metadata("./tests/without_metadata.rpkg").is_err());
        read_metadata("./tests/with_metadata.rpkg").unwrap();
    }
}
