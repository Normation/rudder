// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

//! Command line

use std::path::PathBuf;

use clap::Parser;

const DEFAULT_CONFIG: &str = "/opt/rudder/etc/rudder-mcp.conf";

#[derive(Debug, Parser)]
#[command(version, about = "Rudder MCP server")]
pub struct Cli {
    /// Configuration file
    #[arg(short, long, default_value = DEFAULT_CONFIG)]
    pub config: PathBuf,
}
