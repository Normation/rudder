// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

//! The Rudder-specific inventory section.
//!
//! This is the part of the inventory the server needs to identify the node and its agent, and
//! the only one it treats as mandatory. It has no FusionInventory equivalent: upstream knows
//! nothing about it, the Perl agent gets it from a Rudder-specific module added by our
//! patches.

/// Inventory hooks are only supported on Unix for now: we have no way yet to tell whether a
/// script is safe to execute as an administrator on Windows.
#[cfg(unix)]
pub mod hooks;

use std::{
    fs::read_to_string,
    path::{Path, PathBuf},
};

use anyhow::{Context, Result, bail};
use regex::regex;
use serde::Serialize;
use tracing::{debug, instrument, warn};

/// Where the agent is installed. The only place this is written down.
#[cfg(unix)]
const INSTALL_DIR: &str = "/opt/rudder";
#[cfg(windows)]
const INSTALL_DIR: &str = r"C:\Program Files\Rudder";

/// Where the agent keeps what it builds while it runs. The only place this is written down.
///
/// Windows keeps it under the installation directory instead of a directory of its own.
#[cfg(unix)]
const STATE_DIR: &str = "/var/rudder";
#[cfg(windows)]
const STATE_DIR: &str = r"C:\Program Files\Rudder";

/// The directory the agent is installed in.
pub fn install_dir() -> &'static Path {
    Path::new(INSTALL_DIR)
}

/// The directory the agent keeps its state in.
pub fn state_dir() -> &'static Path {
    Path::new(STATE_DIR)
}

/// The identifier of the node, which the server treats as mandatory.
pub fn uuid_path() -> PathBuf {
    install_dir().join("etc").join("uuid.hive")
}

/// The certificate the node authenticates itself with.
pub fn agent_cert_path() -> PathBuf {
    install_dir().join("etc").join("ssl").join("agent.cert")
}

/// The features of the agent, one per line.
pub fn agent_capabilities_path() -> PathBuf {
    install_dir().join("etc").join("agent-capabilities")
}

/// The `key=value` file the agent version is read from.
pub fn agent_version_path() -> PathBuf {
    install_dir()
        .join("share")
        .join("versions")
        .join("rudder-agent-version")
}

/// The identifier of the policy server of the node.
pub fn policy_server_uuid_path() -> PathBuf {
    state_dir()
        .join("cfengine-community")
        .join("rudder-server-uuid.txt")
}

/// Fields are declared in the order FusionInventory serializes them, to keep both outputs
/// easy to compare.
#[derive(Debug, PartialEq, Serialize)]
#[serde(rename_all = "UPPERCASE")]
pub struct Rudder {
    agent: Agent,
    agent_capabilities: AgentCapabilities,
    /// Always reported: the version of this module stands in when the agent does not name its
    /// own, so the server never has to look it up in the software list.
    agent_version: String,
    /// A JSON array, as a string. Absent when there is no hook directory.
    #[serde(skip_serializing_if = "Option::is_none")]
    custom_properties: Option<String>,
    hostname: String,
    uuid: String,
}

/// Reads a file the inventory cannot be built without, and says which one when it cannot.
///
/// The three values read this way identify the node, its policy server and the key it
/// authenticates itself with, and the server has no use for an inventory missing any of them.
///
/// An empty file is refused along with an absent one. It is the same amount of information, and
/// it used to be worse than a failure: a truncated `uuid.hive`, which a disk filling up or an
/// interrupted upgrade is enough to leave behind, gave a successful run and an inventory naming
/// a node that does not exist. Failing names the file, so that the machine can be repaired.
fn required(path: &Path, what: &str) -> Result<String> {
    let content = read_to_string(path)
        .with_context(|| format!("Reading the {what} from '{}'", path.display()))?;
    let value = content.trim();
    if value.is_empty() {
        bail!("The {what} at '{}' is empty", path.display());
    }
    Ok(value.to_string())
}

/// The user the agent runs as, which the server refuses an inventory without.
///
/// This is the name `whoami` prints, and it used to be read by running it: the one command the
/// module could not do without, on a machine where every other one is optional. The kernel
/// answers the same question without a process, so it is asked directly.
#[cfg(unix)]
fn owner() -> Result<String> {
    let uid = nix::unistd::geteuid();
    let user = nix::unistd::User::from_uid(uid)
        .with_context(|| format!("Looking up the user id {uid} we run as"))?;
    match user {
        Some(user) => Ok(user.name),
        // A user id with no entry in the password database, which is not an error of its own.
        None => bail!("No user in the password database for the user id {uid} we run as"),
    }
}

/// The command that names the user we run as, where there is no password database to ask.
#[cfg(not(unix))]
fn owner() -> Result<String> {
    crate::cmd("whoami".to_string(), &[], None)
}

impl Rudder {
    /// `hostname` is the name the server identifies the node by, and is expected to be the
    /// fully qualified one.
    #[instrument(level = "debug", name = "rudder", skip(hostname))]
    pub fn new(hostname: String) -> Result<Self> {
        // Let's fetch agent data
        let uuid = required(&uuid_path(), "node identifier")?;
        let policy_server_uuid = required(&policy_server_uuid_path(), "policy server identifier")?;
        let certificate = required(&agent_cert_path(), "agent certificate")?;
        let owner = owner()?;

        Ok(Self {
            agent: Agent {
                certificate,
                name: "cfengine-community".to_string(),
                owner,
                policy_server_uuid,
            },
            agent_capabilities: AgentCapabilities::read(&agent_capabilities_path()),
            agent_version: agent_version(&agent_version_path()),
            #[cfg(unix)]
            custom_properties: hooks::custom_properties(&hooks::dir()),
            #[cfg(not(unix))]
            custom_properties: None,
            hostname,
            uuid,
        })
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
        if !path.exists() {
            warn!(
                "No agent capability file at '{}', reporting none",
                path.display()
            );
        }
        let capabilities = read_to_string(path)
            .unwrap_or_default()
            .lines()
            .map(str::trim)
            .filter(|l| !l.is_empty())
            .map(str::to_string)
            .collect();
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

    /// These paths are a contract with the agent packaging: changing one of them stops every
    /// node from being inventoried, so they are pinned here.
    #[cfg(unix)]
    #[test]
    fn it_looks_for_the_agent_files_where_they_are_installed() {
        assert_eq!(uuid_path(), Path::new("/opt/rudder/etc/uuid.hive"));
        assert_eq!(
            agent_cert_path(),
            Path::new("/opt/rudder/etc/ssl/agent.cert")
        );
        assert_eq!(
            agent_capabilities_path(),
            Path::new("/opt/rudder/etc/agent-capabilities")
        );
        assert_eq!(
            agent_version_path(),
            Path::new("/opt/rudder/share/versions/rudder-agent-version")
        );
        assert_eq!(
            policy_server_uuid_path(),
            Path::new("/var/rudder/cfengine-community/rudder-server-uuid.txt")
        );
        assert_eq!(hooks::dir(), Path::new("/var/rudder/hooks.d"));
    }

    /// Nothing may reintroduce a path of its own outside of the two directories.
    #[test]
    fn every_path_is_under_one_of_the_two_roots() {
        for path in [
            uuid_path(),
            agent_cert_path(),
            agent_capabilities_path(),
            agent_version_path(),
        ] {
            assert!(
                path.starts_with(install_dir()),
                "'{}' is not under '{}'",
                path.display(),
                install_dir().display()
            );
        }
        let mut state = vec![policy_server_uuid_path()];
        #[cfg(unix)]
        state.push(hooks::dir());
        for path in state {
            assert!(
                path.starts_with(state_dir()),
                "'{}' is not under '{}'",
                path.display(),
                state_dir().display()
            );
        }
    }

    /// The file has to be named, as the administrator has to know which one to repair, and the
    /// error used to be a bare "No such file or directory (os error 2)".
    #[test]
    fn it_names_the_file_it_cannot_read() {
        let dir = tempdir().unwrap();
        let path = dir.path().join("uuid.hive");
        let err = required(&path, "node identifier").unwrap_err();
        let message = format!("{err:#}");
        assert!(message.contains("uuid.hive"), "{message}");
        assert!(message.contains("node identifier"), "{message}");
    }

    /// A truncated file used to give a successful run and an inventory naming a node that does
    /// not exist, which is worse than no inventory at all.
    #[test]
    fn it_refuses_an_empty_file_as_it_refuses_a_missing_one() {
        let dir = tempdir().unwrap();
        for content in ["", "\n", "   \n\t "] {
            let path = dir.path().join("uuid.hive");
            fs::write(&path, content).unwrap();
            let err = required(&path, "node identifier").unwrap_err();
            let message = format!("{err:#}");
            assert!(message.contains("is empty"), "for {content:?}: {message}");
            assert!(message.contains("uuid.hive"), "{message}");
        }
    }

    #[test]
    fn it_reads_a_file_it_can_use() {
        let dir = tempdir().unwrap();
        let path = dir.path().join("uuid.hive");
        // The real file ends with a newline, which is not part of the value.
        fs::write(&path, "58a41e56-3043-4b64-a0bd-06c975907acd\n").unwrap();
        assert_eq!(
            required(&path, "node identifier").unwrap(),
            "58a41e56-3043-4b64-a0bd-06c975907acd"
        );
    }

    /// The user we run as, which used to be the one command the module could not do without.
    #[test]
    #[cfg(unix)]
    fn it_names_the_user_it_runs_as_without_running_anything() {
        let owner = owner().expect("no user for the user id we run as");
        assert!(!owner.is_empty());
        assert!(!owner.contains(char::is_whitespace), "{owner}");
        // The same answer `whoami` gives, which is what FusionInventory reports.
        if crate::find_in_path("whoami").is_some() {
            let _guard = crate::no_concurrent_fork();
            let whoami = crate::cmd("whoami".to_string(), &[], None).unwrap();
            assert_eq!(owner, whoami);
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
