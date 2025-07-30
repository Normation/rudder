use crate::{Commands, CommandsParameters, get_used_cmd};
use anyhow::{Context, Result};
use clap::Parser;

use std::{collections::HashMap, env, path::PathBuf};

#[derive(Parser)]
#[command(version, about, long_about = None)]
pub struct Cli {
    /// Command to be executed
    command: String,

    /// Audit mode
    #[arg(short, long)]
    audit: bool,

    /// Arguments to the command
    args: Option<Vec<String>>,

    /// Controls the running mode of the command
    #[arg(long)]
    dry_run: bool,

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
    stdin_no_newline: bool,

    /// File to store the output of the command
    #[arg(long)]
    output_to_file: Option<PathBuf>,

    /// Controls the strip of the content inside the output file
    #[arg(long)]
    strip_output: bool, // Default to false

    /// UID used by the executed command
    #[arg(long)]
    uid: Option<String>,

    /// GID used by the executed command
    #[arg(long)]
    gid: Option<String>,

    /// Umask used by the executed command
    #[arg(long)]
    umask: Option<String>,

    /// Controls the sharing of env
    #[arg(long)]
    share_env: bool, // Default to false
}

impl Cli {
    pub fn run() -> Result<()> {
        let cli = Cli::parse();
        let audit = cli.audit;

        let p = Cli::get_parameters(cli);
        let cmd = get_used_cmd(&p);

        if audit && !p.run_in_audit_mode {
            println!("dry-run: {cmd}");
        } else {
            let output = Commands::run(&p, audit)
                .with_context(|| format!("Failed to run command '{cmd}'"))?;
            let output = serde_json::to_string_pretty(&output)?;

            println!("Command '{cmd}':\n{output}");
        }

        Ok(())
    }

    fn get_parameters(cli: Cli) -> CommandsParameters {
        let args = if let Some(args) = &cli.args {
            Some(args.join(" "))
        } else {
            None
        };
        let env = if cli.share_env {
            Some(env::vars().collect::<HashMap<String, String>>())
        } else {
            None
        };
        CommandsParameters {
            command: cli.command,
            args,
            run_in_audit_mode: !cli.dry_run,
            in_shell: cli.in_shell,
            shell_path: cli.shell_path.unwrap_or_else(Commands::default_shell_path),
            chdir: cli.chdir,
            timeout: cli.timeout.unwrap_or_else(Commands::default_timeout),
            stdin: cli.stdin,
            stdin_add_newline: !cli.stdin_no_newline,
            compliant_codes: None,
            repaired_codes: Commands::default_repaired_codes(),
            output_to_file: cli.output_to_file,
            strip_output: cli.strip_output,
            uid: cli.uid,
            gid: cli.gid,
            umask: cli.umask,
            env_vars: env,
            show_content: true,
        }
    }
}
