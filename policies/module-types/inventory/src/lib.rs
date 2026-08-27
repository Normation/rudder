// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

//! A Rudder inventory, in the FusionInventory format the server parses.
//!
//! Every section lives in a module of its own, which owns the types it serializes, what it reads
//! them from, and the differences from FusionInventory it carries. This module holds what the
//! inventory is as a whole: the document, and the order the sections are built in.

#![allow(dead_code)]

pub mod bios;
pub mod cli;
pub mod cpu;
pub mod drives;
pub mod hardware;
pub mod os;
pub mod packages;
pub mod rudder;
pub mod users;
pub mod util;

use std::{env, fs, process::ExitCode};

use anyhow::{Context, Result};
use clap::Parser;
use jiff::Zoned;
use quick_xml::se::Serializer;
use rudder_cli::logs::{self, OutputFormat};
use serde::Serialize;
use sysinfo::{System, Users};
use tracing::{debug, error, info, instrument, trace};

use crate::{
    bios::{Bios, VmSystem},
    cli::Cli,
    cpu::Cpu,
    drives::Drive,
    hardware::Hardware,
    os::OperatingSystem,
    rudder::Rudder,
    users::User,
    util::{fqdn, hostname},
};

/// Collects an inventory, as the command line asks for.
///
/// The binary is only a shim around this, so that everything it does can be exercised from
/// tests.
pub fn entry() -> ExitCode {
    let cli = Cli::parse();
    if let Err(e) = logs::init(cli.debug, cli.quiet, OutputFormat::Human, None) {
        // Nothing to log with yet.
        eprintln!("{e:?}");
        return ExitCode::FAILURE;
    }
    debug!(
        "Running {} v{}",
        env!("CARGO_PKG_NAME"),
        env!("CARGO_PKG_VERSION")
    );
    trace!("Arguments:\n{cli:#?}");
    match run(&cli) {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            error!("{e:?}");
            ExitCode::FAILURE
        }
    }
}

pub fn run(cli: &Cli) -> Result<()> {
    // Commands are parsed by us, so we need their output in a language we know. This has to
    // happen before we run any of them.
    // SAFETY: The module is single-threaded.
    unsafe {
        env::set_var("LANG", "C");
    }

    let inventory = InventoryRequest::new()?;
    let mut out = String::new();
    let mut ser = Serializer::with_root(&mut out, Some("REQUEST"))?;
    ser.indent(' ', 2);
    inventory.serialize(ser)?;
    fs::write(&cli.local, out.as_bytes())
        .with_context(|| format!("Writing the inventory to '{}'", cli.local.display()))?;
    info!(
        "Wrote a {} bytes inventory to '{}'",
        out.len(),
        cli.local.display()
    );
    Ok(())
}

#[derive(Debug, PartialEq, Serialize)]
#[serde(rename_all = "UPPERCASE")]
pub struct InventoryRequest {
    #[serde(rename = "CONTENT")]
    inventory: Inventory,
    #[serde(rename = "DEVICEID")]
    device_id: String,
}

impl InventoryRequest {
    pub fn new() -> Result<Self> {
        Ok(Self {
            inventory: Inventory::new()?,
            // Not actually used by Rudder
            device_id: "placeholder".to_string(),
        })
    }
}

/// This structure is designed to match FusionInventory format
///
/// Blame them for the strange key names.
#[derive(Debug, PartialEq, Serialize)]
#[serde(rename_all = "UPPERCASE")]
pub struct Inventory {
    #[serde(rename = "ENVS")]
    env: Vec<EnvironmentVariable>,
    /// The agent that produced this inventory, like `rudder-module-inventory_v0.0.0-dev`.
    #[serde(rename = "VERSIONCLIENT")]
    agent: String,
    #[serde(rename = "OPERATINGSYSTEM")]
    operating_system: OperatingSystem,
    #[serde(rename = "LOCAL_USERS")]
    users: Vec<User>,
    rudder: Rudder,
    #[serde(rename = "BIOS", skip_serializing_if = "Option::is_none")]
    bios: Option<Bios>,
    cpus: Vec<Cpu>,
    #[serde(rename = "DRIVES")]
    drives: Vec<Drive>,
    hardware: Hardware,
    #[serde(rename = "ACCESSLOG")]
    access_log: AccessLog,
}

impl Inventory {
    #[instrument(level = "debug", name = "inventory")]
    pub fn new() -> Result<Self> {
        // Read once for the whole inventory: the `OPERATINGSYSTEM` and `RUDDER` sections both
        // report the qualified name, and resolving it twice could give two answers.
        let fqdn = fqdn()?;
        // The short name, where FQDN holds the qualified one.
        let hostname = hostname()?;
        let os_release = os::os_release()?;

        let users_src = Users::new_with_refreshed_list();

        let mut sys = System::new();
        sys.refresh_memory();
        sys.refresh_cpu_all();

        // The firmware values are read once: the `BIOS` section reports them, and `VMSYSTEM`
        // says what they make of the machine.
        let bios = Bios::inventory();
        let vm_system = VmSystem::of(bios.as_ref());

        let env: Vec<EnvironmentVariable> = env::vars()
            .map(|(key, value)| EnvironmentVariable { key, value })
            .collect();
        debug!("Found {} environment variables", env.len());

        Ok(Self {
            env,
            agent: format!("{}_v{}", env!("CARGO_PKG_NAME"), env!("CARGO_PKG_VERSION")),
            operating_system: OperatingSystem::inventory(&os_release, fqdn.clone())?,
            users: users::inventory(&users_src),
            rudder: Rudder::inventory(fqdn)?,
            bios,
            cpus: cpu::inventory(&sys),
            drives: drives::inventory(),
            hardware: Hardware::inventory(&sys, hostname, vm_system),
            access_log: AccessLog::new(),
        })
    }
}

#[derive(Debug, PartialEq, Serialize)]
#[serde(rename_all = "UPPERCASE")]
pub struct EnvironmentVariable {
    key: String,
    #[serde(rename = "VAL")]
    value: String,
}

#[derive(Debug, PartialEq, Serialize)]
#[serde(rename_all = "UPPERCASE")]
pub struct AccessLog {
    #[serde(rename = "LOGDATE")]
    inventory_date: String,
}

impl AccessLog {
    fn new() -> Self {
        Self {
            // 2023-07-06 15:52:43, in local time
            inventory_date: Zoned::now().strftime("%Y-%m-%d %H:%M:%S").to_string(),
        }
    }
}
