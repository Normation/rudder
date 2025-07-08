use crate::Commands;
use anyhow::Result;
use clap::Parser;

use std::path::PathBuf;

#[derive(Parser)]
#[command(version, about, long_about = None)]
pub struct Cli {
    /// Command to be executed
    command: String,
    /// Arguments to the command
    args: Option<String>,
    /// Controls the running mode of the command
    #[arg(long)]
    run_in_audit_mode: bool,
    /// Controls if the command is executed inside a shell
    #[arg(long, group = "shell")]
    in_shell: bool,
    /// Shell path (used only in shell mode)
    #[arg(long, requires = "shell")]
    shell_path: Option<String>,
    /// Directory from where to execute the command
    #[arg(long)]
    chdir: Option<String>,
    /// Timeout for command execution
    #[arg(long)]
    timeout: String, // Default to 30 seconds
    /// Input passed to the stdin of the executed command
    #[arg(long)]
    stdin: Option<String>,
    /// Controls the appending of a newline to the stdin input
    #[arg(long)]
    stdin_add_newline: bool,
    /// Compliant codes
    #[arg(long)]
    compliant_codes: String,
    /// Repaired codes
    #[arg(long)]
    repaired_codes: String, // Default to "0"
    /// File to store the output of the command
    #[arg(long)]
    output_to_file: Option<PathBuf>,
    // Controls the strip of the content inside the output file
    #[arg(long)]
    strip_output: bool,
    /// UID used by the executed command
    #[arg(long)]
    uid: Option<String>,
    /// GID used by the executed command
    #[arg(long)]
    gid: Option<String>,
    /// Umask used by the executed command
    #[arg(long)]
    umask: Option<String>,
    /// Environment variables used by the executed command
    #[arg(long)]
    env_vars: Option<String>,
    /// Controls output of diffs in the report
    #[arg(long)]
    show_content: bool,
}

impl Cli {
    pub fn run() -> Result<()> {
        let cli = Cli::parse();
        let args = cli.args.unwrap_or("".to_string());
        Commands::exec(cli.command, args);
        Ok(())
    }
}
