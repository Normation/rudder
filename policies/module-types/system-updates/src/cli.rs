// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS
#![allow(unused_imports)]

use crate::{
    campaign::{check_update, FullSchedule},
    cli,
    db::{Event, PackageDatabase},
    package_manager::PackageManager,
    CampaignType, PackageParameters, RebootType, Schedule, SystemUpdateModule, MODULE_DIR,
};
use anyhow::{bail, Result};
use chrono::{Duration, SecondsFormat};
use cli_table::format::{HorizontalLine, Separator, VerticalLine};
use gumdrop::Options;
use rudder_module_type::{
    inventory::system_node_id, parameters::Parameters, ModuleType0, PolicyMode,
};
use std::{fs, path::PathBuf};
use uuid::Uuid;

#[derive(Debug, Options)]
pub struct Cli {
    #[options(help = "print help message")]
    help: bool,
    #[options(help = "be verbose")]
    verbose: bool,
    #[options(command)]
    command: Option<Command>,
}

#[derive(Debug, Options)]
enum Command {
    #[options(help = "list upgrade events")]
    List(ListOpts),
    #[options(help = "show details about a specific event")]
    Show(ShowOpts),
    #[options(help = "run an upgrade")]
    Run(RunOpts),
    #[options(help = "drop database content")]
    Clear(ClearOpts),
}

#[derive(Debug, Options)]
struct ListOpts {
    #[options(help = "print help message")]
    help: bool,
    #[options(help = "directory where the database is stored")]
    state_dir: Option<PathBuf>,
}

#[derive(Debug, Options)]
struct ShowOpts {
    #[options(help = "print help message")]
    help: bool,
    #[options(help = "directory where the database is stored")]
    state_dir: Option<PathBuf>,
    #[options(free, help = "event id")]
    events: Vec<String>,
}

#[derive(Debug, Options)]
struct RunOpts {
    #[options(help = "print help message")]
    help: bool,
    #[options(help = "directory where the database is stored")]
    state_dir: Option<PathBuf>,
    #[options(long = "dry-run", help = "do not apply changes")]
    dry_run: bool,
    #[options(long = "security", help = "only apply security upgrades")]
    security: bool,
    #[options(help = "package manager to use (defaults to system detection)")]
    package_manager: Option<PackageManager>,
    /*
    "campaign_name": "My campaign",
    "reboot_type": "as_needed",
    "package_list": [],
    */
}

#[derive(Debug, Options)]
struct ClearOpts {
    #[options(help = "directory where the database is stored")]
    state_dir: Option<PathBuf>,
}

impl Cli {
    pub fn run() -> Result<()> {
        let opts = Self::parse_args_default_or_exit();
        if opts.verbose {
            println!("Parsed options: {:#?}", &opts);
        }

        match opts.command {
            Some(Command::List(l)) => {
                let dir = l.state_dir.unwrap_or(PathBuf::from(MODULE_DIR));
                let db = PackageDatabase::new(Some(dir.as_path()))?;
                let events = db.events()?;
                if events.is_empty() {
                    println!("No events found.");
                } else {
                    show_events(events)?;
                }
            }
            Some(Command::Show(s)) => {
                let dir = s.state_dir.unwrap_or(PathBuf::from(MODULE_DIR));
                let db = PackageDatabase::new(Some(dir.as_path()))?;
                if s.events.is_empty() {
                    bail!("Missing event id argument");
                }
                for event_id in s.events {
                    let event = db.event(event_id)?;
                    println!("{}", event);
                }
            }
            Some(Command::Run(opts)) => {
                let state_dir = opts.state_dir.unwrap_or(PathBuf::from(MODULE_DIR));
                let package_parameters = PackageParameters {
                    campaign_type: if opts.security {
                        CampaignType::SecurityUpdate
                    } else {
                        CampaignType::SystemUpdate
                    },
                    package_manager: opts
                        .package_manager
                        .unwrap_or_else(|| PackageManager::detect().unwrap()),
                    event_id: Uuid::new_v4().to_string(),
                    campaign_name: "CLI".to_string(),
                    schedule: Schedule::Immediate,
                    reboot_type: RebootType::Disabled,
                    package_list: vec![],
                    report_file: None,
                    schedule_file: None,
                };

                check_update(
                    state_dir.as_path(),
                    FullSchedule::Immediate,
                    package_parameters,
                )?;
            }
            Some(Command::Clear(l)) => {
                let dir = l.state_dir.unwrap_or(PathBuf::from(MODULE_DIR));
                let db = PackageDatabase::new(Some(dir.as_path()))?;
                db.clean(Duration::seconds(0))?;
            }
            None => {
                eprintln!(
                    "Error: No command specified\n\nAvailable commands:\n{}",
                    opts.self_command_list().unwrap()
                );
            }
        }
        Ok(())
    }
}

fn shorten(s: &str, max_len: usize) -> String {
    if s.len() > max_len {
        format!("{}…", &s[..max_len - 1])
    } else {
        s.to_string()
    }
}

pub fn show_events(events: Vec<Event>) -> Result<()> {
    use cli_table::{print_stdout, Cell, Style, Table};

    let table = events
        .into_iter()
        .map(|e| {
            let status = e
                .report
                .as_ref()
                .map(|r| r.status.to_string())
                .unwrap_or("".to_string());
            vec![
                // No need for a full uuid, make the table more readable
                shorten(&e.id, 9).cell(),
                e.campaign_name.cell(),
                e.status.to_string().cell(),
                e.scheduled_datetime
                    .to_rfc3339_opts(SecondsFormat::Secs, true)
                    .cell(),
                e.run_datetime
                    .map(|d| d.to_rfc3339_opts(SecondsFormat::Secs, true))
                    .unwrap_or("".to_string())
                    .cell(),
                e.report_datetime
                    .map(|d| d.to_rfc3339_opts(SecondsFormat::Secs, true))
                    .unwrap_or("".to_string())
                    .cell(),
                status.cell(),
                e.report
                    .map(|r| r.software_updated.len().to_string())
                    .unwrap_or("".to_string())
                    .cell(),
            ]
        })
        .table()
        .separator(
            Separator::builder()
                .column(Some(VerticalLine::new('|')))
                .title(Some(HorizontalLine::new('+', '+', '+', '-')))
                .build(),
        )
        .title(vec![
            "Id".cell().bold(true),
            "Name".cell().bold(true),
            "Status".cell().bold(true),
            "Scheduled".cell().bold(true),
            "Run".cell().bold(true),
            "Report".cell().bold(true),
            "Result".cell().bold(true),
            "Updates".cell().bold(true),
        ]);
    print_stdout(table)?;
    Ok(())
}
