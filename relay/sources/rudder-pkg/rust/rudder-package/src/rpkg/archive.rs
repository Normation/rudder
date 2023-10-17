use std::{
    fs::*,
    str,
    io::Read,
};
use ar::Archive;
use anyhow::{Result, Context};
use crate::rpkg;

pub fn read_metadata(path: &str) -> Result<rpkg::plugin::Metadata>{
  let mut archive = Archive::new(File::open(path).unwrap());
  while let Some(entry_result) = archive.next_entry() {
    let mut entry = entry_result.unwrap();
    let mut buffer = String::new();
    let entry_title = str::from_utf8(entry.header().identifier()).unwrap();
    if entry_title == "metadata" {
      let _ = entry.read_to_string(&mut buffer)?;
      let m: rpkg::plugin::Metadata = serde_json::from_str(&buffer)
                              .with_context(|| format!("Failed to parse {} metadata", path))?;
      return Ok(m)
    };
  };
  anyhow::bail!("No metadata found in {}", path);
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