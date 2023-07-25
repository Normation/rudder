#[cfg(feature = "embedded-lib")]
use rudder_commons::methods::read;
#[cfg(feature = "embedded-lib")]
use std::{fs, path::PathBuf};

#[cfg(feature = "embedded-lib")]
const METHODS_FILE: &str = "src/methods.json";

fn main() {
    #[cfg(feature = "embedded-lib")]
    {
        // Needs to be setup before build
        // Done in `Jenkinsfile`
        let libs = [
            // Unix
            PathBuf::from("../target/repos/ncf/tree"),
            // Windows
            PathBuf::from("../target/repos/dsc/plugin/src/ncf"),
        ];
        let methods = read(&libs).unwrap();
        fs::write(METHODS_FILE, serde_json::to_string(methods).unwrap()).unwrap();
        for l in libs {
            println!("cargo:rerun-if-changed={}", l.display());
        }
        println!("cargo:rerun-if-changed={METHODS_FILE}");
    }
}
