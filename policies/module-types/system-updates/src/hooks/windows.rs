use crate::hooks::common::{Hooks, RunHooks};
use std::path::{Path, PathBuf};

impl RunHooks for Hooks {
    fn hooks_dir(&self) -> PathBuf {
        PathBuf::from("C:/Program Files/Rudder/system-update/hooks.d")
    }

    fn is_runnable(&self, _path: &Path) -> anyhow::Result<()> {
        todo!()
    }
}
