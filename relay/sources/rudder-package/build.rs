use clap::CommandFactory;
use clap_complete::{generate_to, shells::Bash};
use std::io::Error;
use std::{env, fs};

include!("src/cli.rs");

/// Dummy wrapper to generate completions for "rudder package"
#[derive(Parser, Debug)]
enum Wrapper {
    /// Manage plugins
    Package(Args),
}

fn main() -> Result<(), Error> {
    let outdir = match env::var_os("COMPLETION_OUT_DIR") {
        None => return Ok(()),
        Some(outdir) => outdir,
    };
    fs::create_dir_all(&outdir)?;

    let mut cmd = Wrapper::command();
    let path = generate_to(Bash, &mut cmd, "rudder", outdir)?;

    println!("cargo:warning=completion file is generated: {path:?}");

    Ok(())
}
