#![allow(clippy::regex_creation_in_loops)]
#![allow(dead_code)]

mod os_release;
pub mod packages;

use std::{env, fs, fs::read_to_string, path::PathBuf, process::Command, str};

use anyhow::{anyhow, bail, Result};
use chrono::{DateTime, Local};
use clap::Parser;
use os_release::OsRelease;
use quick_xml::se::Serializer;
use serde::Serialize;
use sysinfo::{ProcessesToUpdate, System, Users};
#[cfg(unix)]
use uname_rs::Uname;

pub const AGENT_CERT_PATH: &str = "/opt/rudder/etc/ssl/agent.cert";
pub const UUID_PATH: &str = "/opt/rudder/etc/uuid.hive";
pub const POLICY_SERVER_HOSTNAME_PATH: &str = "/var/rudder/cfengine-community/policy_server.dat";
pub const POLICY_SERVER_UUID_PATH: &str = "/var/rudder/cfengine-community/rudder-server-uuid.txt";

fn cmd<T: AsRef<str>>(command: T, args: &[T], fallback: Option<T>) -> Result<String> {
    let cmd = Command::new(command.as_ref())
        .args(args.iter().map(|s| s.as_ref()))
        .output()?;
    Ok(if cmd.status.success() {
        str::from_utf8(&cmd.stdout)?.to_owned()
    } else if let Some(v) = fallback {
        v.as_ref().to_string()
    } else {
        bail!(
            "Could not get value from '{}' command with the '{}' arguments: {}",
            command.as_ref(),
            args.iter()
                .map(|s| s.as_ref())
                .collect::<Vec<&str>>()
                .join(" "),
            str::from_utf8(&cmd.stderr)?.to_owned()
        )
    }
    .trim()
    .to_string())
}

#[derive(Parser)]
#[command(author, version, about, long_about = None)]
struct Cli {
    #[arg(short, long, value_name = "FILE")]
    /// Write an inventory in the given FILE
    local: PathBuf,

    /// Turn debugging information on
    #[arg(short, long, action = clap::ArgAction::Count)]
    debug: u8,
}

fn main() -> Result<()> {
    let cli = Cli::parse();
    env::set_var("LANG", "C");

    let inventory = InventoryRequest::new()?;
    let mut out = String::new();
    let mut ser = Serializer::with_root(&mut out, Some("REQUEST"))?;
    ser.indent(' ', 2);
    inventory.serialize(ser)?;
    fs::write(cli.local, out.as_bytes())?;
    Ok(())
}

#[derive(Debug, PartialEq, Serialize)]
#[serde(rename_all = "UPPERCASE")]
pub struct InventoryRequest {
    #[serde(rename = "CONTENT")]
    inventory: Inventory,
    #[serde(rename = "DEVICEID")]
    device_id: String,
    query: &'static str,
}

impl InventoryRequest {
    pub fn new() -> Result<Self> {
        Ok(Self {
            inventory: Inventory::new()?,
            query: "INVENTORY",
            // Not actually used by Rudder
            device_id: "placeholder".to_string(),
        })
    }
}

#[derive(Debug, PartialEq, Serialize)]
#[serde(rename_all = "UPPERCASE")]
pub struct Hardware {
    #[serde(rename = "VMSYSTEM")]
    machine_type: &'static str,
}

impl Hardware {
    fn new() -> Result<Self> {
        let cmd = Command::new("systemd-detect-virt").output()?;
        let stdout = str::from_utf8(&cmd.stdout)?.trim();
        let machine_type = match (cmd.status.success(), stdout) {
            // https://www.freedesktop.org/software/systemd/man/systemd-detect-virt.html
            (false, "none") => "physical",
            (true, "lxc") => "lxc",
            (true, "lxc-libvirt") => "lxc",
            (true, "openvz") => "openvz",
            (true, "xen") => "xen",
            (true, "vmware") => "vmware",
            (true, "oracle") => "virtualbox",
            (true, "qemu") => "qemu",
            (true, "kvm") => "qemu",
            (true, "microsoft") => "hyper-v",
            (true, _) => "virtual machine",
            _ => bail!("Could not detect VM system"),
        };
        Ok(Self { machine_type })
    }
}

#[derive(Debug, PartialEq, Serialize)]
#[serde(rename_all = "UPPERCASE")]
pub struct Cpu {
    name: String,
    manufacturer: String,
}

#[derive(Debug, PartialEq, Serialize)]
#[serde(rename_all = "UPPERCASE")]
pub struct User {
    id: String,
    login: String,
}

impl Inventory {
    pub fn new() -> Result<Self> {
        #[cfg(unix)]
        let uts = Uname::new()?;
        let hostname = hostname::get()?
            .into_string()
            .map_err(|_| anyhow!("Non-UTF8 hostname"))?;
        let os_release = OsRelease::new()?;

        let users_src = Users::new_with_refreshed_list();

        let mut sys = System::new();

        let users = users_src
            .iter()
            .map(|u| User {
                id: u.id().to_string(),
                login: u.name().to_string(),
            })
            .collect();

        sys.refresh_cpu_all();
        let cpus = sys
            .cpus()
            .iter()
            .map(|c| Cpu {
                name: c.brand().to_string(),
                manufacturer: c.vendor_id().to_string(),
            })
            .collect();

        sys.refresh_processes(ProcessesToUpdate::All, true);

        let processes = sys
            .processes()
            .iter()
            .map(|(pid, p)| Process {
                cmd: match p
                    .cmd()
                    .iter()
                    .map(|s| s.to_string_lossy().to_string())
                    .collect::<Vec<String>>()
                    .join(" ")
                    .trim()
                {
                    "" => p.exe().unwrap().to_string_lossy().to_string(),
                    c => c.to_string(),
                },
                cpu_usage: p.cpu_usage().to_string(),
                pid: pid.to_string(),
                // <STARTED>2022-12-16 11:55</STARTED>
                started: {
                    let datetime = DateTime::from_timestamp(p.start_time() as i64, 0).unwrap();
                    datetime.format("%Y-%m-%d %H:%M").to_string()
                },
                user: users_src
                    .get_user_by_id(p.user_id().unwrap())
                    .unwrap()
                    .name()
                    .to_string(),
            })
            .collect();

        let arch = cmd("uname", &["-m"], None)?;

        Ok(Self {
            env: env::vars()
                .map(|(key, value)| EnvironmentVariable { key, value })
                .collect(),
            agent: format!("{}_v{}", env!("CARGO_PKG_NAME"), env!("CARGO_PKG_VERSION")),
            operating_system: OperatingSystem {
                arch,
                boot_time: "".to_string(),
                #[cfg(unix)]
                dns_domain: uts.domainname,
                // FIXME not fqdn
                fqdn: hostname.clone(),
                full_name: os_release.pretty_name,
                host_id: "".to_string(),
                #[cfg(unix)]
                kernel_name: uts.sysname.to_lowercase(),
                #[cfg(unix)]
                kernel_version: uts.release,
                name: os_release.name,
                version: os_release.version,
            },
            users,
            rudder: Rudder::new()?,
            cpus,
            hardware: Hardware::new()?,
            processes,
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
pub struct OperatingSystem {
    // <ARCH>x86_64</ARCH>
    arch: String,
    // <BOOT_TIME>2023-06-29 15:04:23</BOOT_TIME>
    boot_time: String,
    // <DNS_DOMAIN>rudder.local</DNS_DOMAIN>
    #[cfg(unix)]
    dns_domain: String,
    // <FQDN>server.rudder.local</FQDN>
    fqdn: String,
    // <FULL_NAME>CentOS Stream release 8</FULL_NAME>
    full_name: String,
    // <HOSTID>a8c0022c</HOSTID>
    #[serde(rename = "HOSTID")]
    host_id: String,
    // <KERNEL_NAME>linux</KERNEL_NAME>
    #[cfg(unix)]
    kernel_name: String,
    // <KERNEL_VERSION>4.18.0-365.el8.x86_64</KERNEL_VERSION>
    #[cfg(unix)]
    kernel_version: String,
    // <NAME>CentOS</NAME>
    name: String,
    // <VERSION>8</VERSION>
    version: String,
}

#[derive(Debug, PartialEq, Serialize)]
#[serde(rename_all = "UPPERCASE")]
pub struct Rudder {
    agent: Agent,
    hostname: String,
    // server_roles
    uuid: String,
}

impl Rudder {
    pub fn new() -> Result<Self> {
        // Let's fetch agent data
        let uuid = read_to_string(UUID_PATH)?.trim().to_string();
        let policy_server_hostname = read_to_string(POLICY_SERVER_HOSTNAME_PATH)?
            .trim()
            .to_string();
        let policy_server_uuid = read_to_string(POLICY_SERVER_UUID_PATH)?.trim().to_string();
        let certificate = read_to_string(AGENT_CERT_PATH)?.trim().to_string();
        let hostname_fallback = hostname::get()?
            .into_string()
            .map_err(|_| anyhow!("Non-utf8 hostname"))?;
        let hostname = cmd("hostname", &["--fqdn"], Some(&hostname_fallback))?;
        let owner = cmd("whoami".to_string(), &[], None)?;

        Ok(Self {
            agent: Agent {
                certificate,
                name: "cfengine-community".to_string(),
                owner,
                policy_server_hostname,
                policy_server_uuid,
            },
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
    policy_server_hostname: String,
    policy_server_uuid: String,
}

#[derive(Debug, PartialEq, Serialize)]
#[serde(rename_all = "UPPERCASE")]
pub struct Process {
    cmd: String,
    #[serde(rename = "CPUUSAGE")]
    cpu_usage: String,
    pid: String,
    started: String,
    user: String,
}

#[derive(Debug, PartialEq, Serialize)]
#[serde(rename_all = "UPPERCASE")]
pub struct AccessLog {
    #[serde(rename = "LOGDATE")]
    inventory_date: String,
}

impl AccessLog {
    fn new() -> Self {
        let local: DateTime<Local> = Local::now();
        Self {
            // 2023-07-06 15:52:43
            // local time
            inventory_date: local.format("%Y-%m-%d %H:%M:%S").to_string(),
        }
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
    // <VERSIONCLIENT>FusionInventory-Agent_v2.4.3</VERSIONCLIENT>
    // <VERSIONCLIENT>minifusion_v0.0.0-dev</VERSIONCLIENT>
    #[serde(rename = "VERSIONCLIENT")]
    agent: String,
    #[serde(rename = "OPERATINGSYSTEM")]
    operating_system: OperatingSystem,
    #[serde(rename = "LOCAL_USERS")]
    users: Vec<User>,
    rudder: Rudder,
    cpus: Vec<Cpu>,
    hardware: Hardware,
    processes: Vec<Process>,
    #[serde(rename = "ACCESSLOG")]
    access_log: AccessLog,
    // FIXME timezone
    // FIXME properties
    // FIXME disks?
}
