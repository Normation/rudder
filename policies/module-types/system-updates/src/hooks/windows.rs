use crate::hooks::common::{Hooks, RunHooks};
use anyhow::bail;
use std::path::{Path, PathBuf};

impl RunHooks for Hooks {
    fn hooks_dir(&self) -> PathBuf {
        PathBuf::from("C:/Program Files/Rudder/system-update/hooks.d")
    }

    fn is_runnable(&self, path: &Path) -> anyhow::Result<()> {
        if !path.is_file() {
            bail!(
                "{:?} is not a file and can not be executed as campaign hook",
                path
            )
        }
        Ok(())
    }
}
