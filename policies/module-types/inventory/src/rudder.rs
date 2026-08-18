// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

//! The `RUDDER` section: what identifies the node to its server, and what its agent can do.

pub mod hooks;

use std::{fs::read_to_string, io::ErrorKind, path::Path};

use anyhow::{Context, Result};
use nix::unistd::{User as PasswdEntry, geteuid};
use regex::regex;
use serde::Serialize;
use tracing::{debug, instrument, warn};

use crate::util::required;

/// The identifier of the node, which the server treats as mandatory.
const UUID_PATH: &str = "/opt/rudder/etc/uuid.hive";

/// The certificate the node authenticates itself with.
const AGENT_CERT_PATH: &str = "/opt/rudder/etc/ssl/agent.cert";

/// The features of the agent, one per line.
const AGENT_CAPABILITIES_PATH: &str = "/opt/rudder/etc/agent-capabilities";

/// The `key=value` file the agent version is read from.
const AGENT_VERSION_PATH: &str = "/opt/rudder/share/versions/rudder-agent-version";

/// Where an administrator drops the inventory hooks we run.
const HOOKS_DIR: &str = "/var/rudder/hooks.d";

/// The identifier of the policy server of the node.
///
/// NOTE: Currently this one needs to be produced by the agent before calling the inventory tool.
///       This is something we will want to change at some point.
const POLICY_SERVER_UUID_PATH: &str = "/var/rudder/cfengine-community/rudder-server-uuid.txt";

/// Fields are declared in the order FusionInventory serializes them, to keep both outputs
/// easy to compare.
#[derive(Debug, PartialEq, Serialize)]
#[serde(rename_all = "UPPERCASE")]
pub struct Rudder {
    agent: Agent,
    agent_capabilities: AgentCapabilities,
    agent_version: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    custom_properties: Option<String>,
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
    #[instrument(level = "debug", name = "rudder", skip(fqdn))]
    pub fn inventory(fqdn: String) -> Result<Self> {
        // Let's fetch agent data
        let uuid = required(Path::new(UUID_PATH), "node identifier")?;
        let policy_server_uuid = required(
            Path::new(POLICY_SERVER_UUID_PATH),
            "policy server identifier",
        )?;
        let certificate = required(Path::new(AGENT_CERT_PATH), "agent certificate")?;

        Ok(Self {
            agent: Agent {
                certificate,
                name: "cfengine-community".to_string(),
                owner: owner()?,
                policy_server_uuid,
            },
            agent_capabilities: AgentCapabilities::read(Path::new(AGENT_CAPABILITIES_PATH)),
            agent_version: agent_version(Path::new(AGENT_VERSION_PATH)),
            custom_properties: hooks::custom_properties(Path::new(HOOKS_DIR)),
            hostname: fqdn,
            uuid,
        })
    }
}

/// The user the agent runs as (which is generally root).
///
/// This is the effective identifier, which is what decides what the run can read, rather than the
/// real one a `setuid` binary would keep. `whoami` answers the same question, and asking the
/// kernel saves running it.
///
/// A user the password database has no entry for is reported by identifier.
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
    policy_server_uuid: String,
}

/// The features of the agent the server can rely on, one per line in a file written at
/// installation. The server lowercases them, so we report them as they are.
#[derive(Debug, PartialEq, Serialize)]
pub struct AgentCapabilities {
    #[serde(rename = "AGENT_CAPABILITY")]
    capabilities: Vec<String>,
}

impl AgentCapabilities {
    /// An unreadable file gives no capability, like an empty one: the agent is then only
    /// assumed to support the base features.
    fn read(path: &Path) -> Self {
        let capabilities: Vec<String> = match read_to_string(path) {
            Ok(content) => content
                .lines()
                .map(str::trim)
                .filter(|l| !l.is_empty())
                .map(str::to_string)
                .collect(),
            Err(e) if e.kind() == ErrorKind::NotFound => {
                debug!("No agent capability file at '{}'", path.display());
                vec![]
            }
            Err(e) => {
                warn!(
                    "Could not read the agent capabilities from '{}', reporting none: {e}",
                    path.display()
                );
                vec![]
            }
        };
        debug!("Found {} agent capabilities", capabilities.len());
        Self { capabilities }
    }
}

/// Reads the agent version out of the `key=value` file written at installation.
///
/// Falls back to the version of this module, which is built and shipped with the agent and so
/// carries its version. It is the same answer in all but name, and a better one than the two
/// this used to leave: no element at all, which has the server look the agent up in the software
/// list, and nothing to report on a node whose software list is empty because `dpkg-query` is
/// not what installed the agent.
fn agent_version(path: &Path) -> String {
    let fallback = || {
        let version = env!("CARGO_PKG_VERSION").to_string();
        warn!("Reporting the version of this module, {version}, as the agent version");
        version
    };
    let Ok(content) = read_to_string(path) else {
        warn!("Could not read the agent version from '{}'", path.display());
        return fallback();
    };
    let Some(version) = regex!(r"(?m)^rudder_version=(.+)$")
        .captures(&content)
        .map(|caps| caps[1].trim().to_string())
    else {
        warn!("No 'rudder_version' in '{}'", path.display());
        return fallback();
    };
    debug!("Agent version is {version}");
    version
}

#[cfg(test)]
mod tests {
    use std::{fs, io::Write};

    use pretty_assertions::assert_eq;
    use tempfile::tempdir;

    use super::*;

    /// The user we run as, which used to be read by running `whoami`.
    #[test]
    fn it_names_the_user_it_runs_as_without_running_anything() {
        let owner = owner().expect("no user for the user id we run as");
        assert!(!owner.is_empty());
        assert!(!owner.contains(char::is_whitespace), "{owner}");
        // The same answer `whoami` gives, which is what FusionInventory reports.
        if crate::util::find_in_path("whoami").is_some() {
            let _guard = crate::util::no_concurrent_fork();
            assert_eq!(owner, crate::util::cmd("whoami", &[]).unwrap());
        }
    }

    #[test]
    fn it_reads_the_agent_version() {
        let dir = tempdir().unwrap();
        let path = dir.path().join("rudder-agent-version");
        let mut f = fs::File::create(&path).unwrap();
        // The real file holds a lot more than the version we look for.
        writeln!(
            f,
            "rudder_version=9.1.3\nmain_version=9.1.3\nnightly_tag=\nbuild-date=Thu Jul  9 13:31:17 CEST 2026"
        )
        .unwrap();
        assert_eq!(agent_version(&path), "9.1.3");
    }

    /// The agent version is always reported: without a file to read it from, this module is
    /// shipped with the agent and its own version stands in.
    #[test]
    fn it_falls_back_to_the_version_of_this_module() {
        let dir = tempdir().unwrap();
        let ours = env!("CARGO_PKG_VERSION");
        assert_eq!(agent_version(&dir.path().join("absent")), ours);
        // A file we can read that does not hold the key we need.
        let path = dir.path().join("no-key");
        fs::write(&path, "main_version=9.1.3\n").unwrap();
        assert_eq!(agent_version(&path), ours);
        // An empty one, and one holding the key with nothing after it.
        let path = dir.path().join("empty");
        fs::write(&path, "").unwrap();
        assert_eq!(agent_version(&path), ours);
        let path = dir.path().join("no-value");
        fs::write(&path, "rudder_version=\n").unwrap();
        assert_eq!(agent_version(&path), ours);
    }

    #[test]
    fn it_reads_agent_capabilities() {
        let dir = tempdir().unwrap();
        let path = dir.path().join("agent-capabilities");
        fs::write(&path, "cfengine\njq\nyaml\n\nxml\n").unwrap();
        assert_eq!(
            AgentCapabilities::read(&path).capabilities,
            vec!["cfengine", "jq", "yaml", "xml"]
        );
        assert_eq!(
            AgentCapabilities::read(&dir.path().join("absent")).capabilities,
            Vec::<String>::new()
        );
    }
}
