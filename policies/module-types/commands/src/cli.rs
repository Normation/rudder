use crate::{
    Commands, CommandsParameters, default_repaired_codes, default_shell_path, default_timeout,
};
use anyhow::{Context, Result};
use clap::Parser;

use std::path::PathBuf;

#[derive(Parser)]
#[command(version, about, long_about = None)]
pub struct Cli {
    /// Command to be executed
    command: String,
    /// Arguments to the command
    args: Option<Vec<String>>,
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
    timeout: Option<String>, // Default to 30 seconds
    /// Input passed to the stdin of the executed command
    #[arg(long)]
    stdin: Option<String>,
    /// Controls the appending of a newline to the stdin input
    #[arg(long)]
    stdin_add_newline: bool,
    /// Compliant codes
    #[arg(long)]
    compliant_codes: Option<String>,
    /// Repaired codes
    #[arg(long)]
    repaired_codes: Option<String>, // Default to "0"
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
        let p = Cli::get_parameters();
        Commands::run(&p).with_context(|| format!("Failed to run command '{}'", p.command))?;
        // TODO: Make this error message more exhaustive.
        Ok(())
    }

    fn get_parameters() -> CommandsParameters {
        let cli = Cli::parse();
        let args = if let Some(args) = &cli.args {
            Some(args.join(" "))
        } else {
            None
        };
        CommandsParameters {
            command: cli.command,
            args,
            run_in_audit_mode: cli.run_in_audit_mode,
            in_shell: cli.in_shell,
            shell_path: cli.shell_path.unwrap_or_else(default_shell_path),
            chdir: cli.chdir,
            timeout: cli.timeout.unwrap_or_else(default_timeout),
            stdin: cli.stdin,
            stdin_add_newline: cli.stdin_add_newline,
            compliant_codes: cli.compliant_codes.unwrap_or("".to_string()),
            repaired_codes: cli.repaired_codes.unwrap_or_else(default_repaired_codes),
            output_to_file: cli.output_to_file,
            strip_output: cli.strip_output,
            uid: cli.uid,
            gid: cli.gid,
            umask: cli.umask,
            env_vars: cli.env_vars,
            show_content: cli.show_content,
        }
    }
}
