// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021 Normation SAS

use crate::MODULE_DIR;
use crate::db::{DbEvent, EventDatabase};
use crate::event::Events;
use anyhow::{Result, bail};
use chrono::{SecondsFormat, Utc};
use cli_table::format::{HorizontalLine, Separator, VerticalLine};
use gumdrop::Options;
use std::path::PathBuf;

#[derive(Debug, Options)]
pub struct Cli {
    #[options(help = "JSON data file")]
    data: Option<PathBuf>,

    #[options(help = "directory where the database is stored")]
    state_dir: Option<PathBuf>,

    #[options(help = "list events in the database")]
    list: bool,

    #[options(help = "be verbose")]
    verbose: bool,

    #[options(help = "print help message")]
    help: bool,
}

impl Cli {
    pub fn run() -> Result<()> {
        let opts = Self::parse_args_default_or_exit();

        if opts.help_requested() {
            println!("{}", Self::usage());
            return Ok(());
        }

        if opts.verbose {
            println!("Parsed options: {:#?}", &opts);
        }

        let dir = opts.state_dir.unwrap_or(PathBuf::from(MODULE_DIR));
        let mut db = EventDatabase::new(Some(dir.as_path()))?;

        if opts.list {
            let events = db.events()?;
            show_events(events)?;
            return Ok(());
        }

        if let Some(file) = opts.data.as_ref() {
            if !file.is_file() {
                anyhow::bail!("Data file is not a file");
            }
            let data = std::fs::read_to_string(file)?;
            let events: Events = serde_json::from_str(&data)?;

            let now = Utc::now();
            let classes = events.update(&mut db, now)?;

            println!("{}", serde_json::to_string(&classes)?);
            Ok(())
        } else {
            bail!("Data file is required");
        }
    }
}

fn shorten(s: &str, max_len: usize) -> String {
    if s.len() > max_len {
        format!("{}…", &s[..max_len - 1])
    } else {
        s.to_string()
    }
}

pub fn show_events(events: Vec<DbEvent>) -> Result<()> {
    use cli_table::{Cell, Style, Table, print_stdout};

    let table = events
        .into_iter()
        .map(|e| {
            vec![
                // No need for a full uuid, make the table more readable
                shorten(&e.id, 9).cell(),
                shorten(&e.schedule_id, 9).cell(),
                e.schedule_type.cell(),
                e.name.cell(),
                e.schedule.cell(),
                e.created.to_rfc3339_opts(SecondsFormat::Secs, true).cell(),
                e.run_time
                    .map(|d| d.to_rfc3339_opts(SecondsFormat::Secs, true))
                    .unwrap_or("".to_string())
                    .cell(),
                e.not_before
                    .map(|d| d.to_rfc3339_opts(SecondsFormat::Secs, true))
                    .unwrap_or("".to_string())
                    .cell(),
                e.not_after
                    .map(|d| d.to_rfc3339_opts(SecondsFormat::Secs, true))
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
            "Event Id".cell().bold(true),
            "Schedule Id".cell().bold(true),
            "Type".cell().bold(true),
            "Name".cell().bold(true),
            "Schedule".cell().bold(true),
            "Created".cell().bold(true),
            "Run time".cell().bold(true),
            "Not before".cell().bold(true),
            "Not after".cell().bold(true),
        ]);
    print_stdout(table)?;
    Ok(())
}
