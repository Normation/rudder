// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

//! The `RUDDER` section: what identifies the node to its server.

use std::fs::read_to_string;

use anyhow::{Context, Result};
use nix::unistd::{User as PasswdEntry, geteuid};
use serde::Serialize;
use tracing::warn;

pub const AGENT_CERT_PATH: &str = "/opt/rudder/etc/ssl/agent.cert";
pub const UUID_PATH: &str = "/opt/rudder/etc/uuid.hive";
pub const POLICY_SERVER_HOSTNAME_PATH: &str = "/var/rudder/cfengine-community/policy_server.dat";
pub const POLICY_SERVER_UUID_PATH: &str = "/var/rudder/cfengine-community/rudder-server-uuid.txt";

#[derive(Debug, PartialEq, Serialize)]
#[serde(rename_all = "UPPERCASE")]
pub struct Rudder {
    agent: Agent,
    hostname: String,
    // server_roles
    uuid: String,
}

impl Rudder {
    /// The fully qualified name is the one the section reports, and the one the server
    /// identifies the node by, so it is read once for the whole inventory and handed over.
    ///
    /// This is the only section a run fails on: a node the server cannot identify has no
    /// inventory to send.
    pub fn inventory(fqdn: String) -> Result<Self> {
        // Let's fetch agent data
        let uuid = read_to_string(UUID_PATH)?.trim().to_string();
        let policy_server_hostname = read_to_string(POLICY_SERVER_HOSTNAME_PATH)?
            .trim()
            .to_string();
        let policy_server_uuid = read_to_string(POLICY_SERVER_UUID_PATH)?.trim().to_string();
        let certificate = read_to_string(AGENT_CERT_PATH)?.trim().to_string();

        Ok(Self {
            agent: Agent {
                certificate,
                name: "cfengine-community".to_string(),
                owner: owner()?,
                policy_server_hostname,
                policy_server_uuid,
            },
            hostname: fqdn,
            uuid,
        })
    }
}

/// The user the agent runs as, which is root.
///
/// This is the effective identifier, which is what decides what the run can read, rather than the
/// real one a `setuid` binary would keep. `whoami` answers the same question, and asking the
/// kernel saves running it.
///
/// A user the password database has no entry for is reported by identifier, as `id -u` prints it,
/// which is more than `whoami` manages: it fails there, and failing this section would cost the
/// whole inventory over a name.
fn owner() -> Result<String> {
    let uid = geteuid();
    match PasswdEntry::from_uid(uid).context("Reading the user the module runs as")? {
        Some(user) => Ok(user.name),
        None => {
            warn!("No password database entry for user {uid}, reporting the identifier");
            Ok(uid.to_string())
        }
    }
}

#[derive(Debug, PartialEq, Serialize)]
#[serde(rename_all = "UPPERCASE")]
pub struct Agent {
    #[serde(rename = "AGENT_CERT")]
    certificate: String,
    #[serde(rename = "AGENT_NAME")]
    name: String,
    owner: String,
    policy_server_hostname: String,
    policy_server_uuid: String,
}
