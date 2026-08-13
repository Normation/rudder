// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

#![allow(clippy::regex_creation_in_loops)]
#![allow(dead_code)]

pub mod bios;
pub mod cli;
pub mod cpu;
pub mod drives;
/// Only Linux for now: the interface type, speed and gateway come from `/sys` and `/proc`.
#[cfg(target_os = "linux")]
pub mod networks;
pub mod packages;
pub mod rudder;

use std::{
    env, fs,
    path::{Path, PathBuf},
    process::Command,
    process::ExitCode,
    str,
};

use anyhow::{Context, Result, bail};
use clap::Parser;
use jiff::{Timestamp, Zoned, tz::TimeZone};
#[cfg(unix)]
use nix::sys::utsname::uname;
use quick_xml::se::Serializer;
use rudder_cli::logs::{self, OutputFormat};
use rudder_module_type::os_release::OsRelease;
use serde::Serialize;
use sysinfo::{
    ProcessRefreshKind, ProcessesToUpdate, Product, System, ThreadKind, UpdateKind, Users,
};
use tracing::{debug, error, info, instrument, trace, warn};

#[cfg(target_os = "linux")]
use crate::networks::Network;
use crate::{
    bios::Bios,
    cli::Cli,
    cpu::Cpu,
    drives::Drive,
    packages::{Package, Update},
    rudder::Rudder,
};

/// Serializes the tests that spawn a process against the tests that write a program and then
/// run it.
///
/// Linux refuses to execute a file that any process holds open for writing, and
/// `Command::spawn` forks before it executes: the child inherits the file descriptors open at
/// that moment, so a fork from one test keeps a program another test is still writing open until
/// it executes. Every test that spawns anything takes this, which is enough to keep them apart.
/// Only the tests are affected.
#[cfg(test)]
pub(crate) fn no_concurrent_fork() -> std::sync::MutexGuard<'static, ()> {
    static LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());
    // A poisoned lock only means another test failed, which must not turn every other test into
    // a confusing poisoning error.
    LOCK.lock().unwrap_or_else(|e| e.into_inner())
}

/// Values read from the system are often an empty string rather than absent.
pub(crate) fn empty_to_none(value: &str) -> Option<String> {
    if value.is_empty() {
        None
    } else {
        Some(value.to_string())
    }
}

/// The placeholders the firmware writes into DMI instead of leaving a value out.
///
/// Whitespace is already gone when they are compared, so `Not Specified` and `NotSpecified`
/// are the same placeholder, as they are to FusionInventory.
const DMI_PLACEHOLDERS: &[&str] = &[
    "n/a",
    "none",
    "unknown",
    "notspecified",
    "notpresent",
    "notavailable",
    "<badindex>",
    "<outofspec>",
    "<outofspec><outofspec>",
    "tobefilledbyo.e.m.",
];

/// A DMI value, or nothing when the firmware only wrote a placeholder into it.
///
/// A field the firmware says nothing about is rarely absent: it holds an empty string, or one
/// of a handful of stand-ins for "no value", and reporting `BVERSION` as `unknown` would be
/// reporting a BIOS version the machine does not have. FusionInventory drops the same list, in
/// `Tools/Generic.pm`, for every value it reads out of `dmidecode`, so this keeps both agents
/// silent about the same fields.
///
/// This is deliberately the list FusionInventory has and no longer: `Default string` and
/// `System Product Name` are placeholders too, and both agents report them.
pub(crate) fn dmi_value(value: &str) -> Option<String> {
    let value = value.trim();
    let compared: String = value
        .chars()
        .filter(|c| !c.is_whitespace())
        .flat_map(char::to_lowercase)
        .collect();
    if compared.is_empty() || DMI_PLACEHOLDERS.contains(&compared.as_str()) {
        return None;
    }
    Some(value.to_string())
}

/// Where the distribution describes itself, in the order the specification reads them.
const OS_RELEASE_PATHS: [&str; 2] = ["/etc/os-release", "/usr/lib/os-release"];

/// What the distribution says it is, or the generic Linux when it says nothing.
///
/// `OsRelease` names the system `Linux`, with no version at all, when it finds nothing to read,
/// and a machine we cannot identify is inventoried under that name rather than not at all. The
/// server gets less to reason about, as it matches policies and updates on the name and the
/// version of the operating system, but everything else the node has to report is still worth
/// having.
///
/// That is a quiet outcome the administrator has to be told about, so this warns whichever way
/// it happens: no file to read, or one that describes nothing. An empty `/etc/os-release` is not
/// fallen back from, `/usr/lib/os-release` only being read when the first is absent, as the
/// specification asks.
fn os_release() -> Result<OsRelease> {
    // The file `OsRelease` reads: the first of the two that exists.
    match OS_RELEASE_PATHS
        .map(Path::new)
        .into_iter()
        .find(|p| p.exists())
    {
        Some(path) if fs::read_to_string(path).is_ok_and(|c| !c.trim().is_empty()) => (),
        Some(path) => warn!(
            "'{}' says nothing about the operating system, reporting a generic Linux",
            path.display()
        ),
        None => warn!(
            "No {} to read the operating system from, reporting a generic Linux",
            OS_RELEASE_PATHS.join(" or ")
        ),
    }
    OsRelease::new().context("Reading the operating system release")
}

/// The name of the machine, without its domain.
#[cfg(unix)]
pub(crate) fn hostname() -> Result<String> {
    nix::unistd::gethostname()
        .context("Reading the hostname")?
        .into_string()
        .map_err(|_| anyhow::anyhow!("Non-UTF8 hostname"))
}

/// The name of the machine, which Windows names in the environment of every process.
#[cfg(windows)]
pub(crate) fn hostname() -> Result<String> {
    env::var("COMPUTERNAME").context("Reading the hostname from COMPUTERNAME")
}

/// The fully qualified domain name of the local machine.
///
/// `hostname --fqdn` resolves the hostname to get the domain part, and falls back to the
/// hostname alone when it cannot be resolved. The server needs this to identify the node, and
/// only rejects it when it is empty or a loopback name.
pub(crate) fn fqdn() -> Result<String> {
    let hostname = hostname()?;
    cmd("hostname", &["--fqdn"], Some(&hostname))
}

/// Runs a command and returns its output, or the given fallback value.
///
/// A command we cannot run at all, because it is not installed, is the same nominal outcome as
/// one that runs and fails: a caller that gave us a fallback gets it either way.
pub(crate) fn cmd<T: AsRef<str>>(command: T, args: &[T], fallback: Option<T>) -> Result<String> {
    let program = command.as_ref();
    let output = Command::new(program)
        .args(args.iter().map(|s| s.as_ref()))
        .output();
    let arguments = || {
        args.iter()
            .map(|s| s.as_ref())
            .collect::<Vec<&str>>()
            .join(" ")
    };
    let value = match (&output, &fallback) {
        (Ok(out), _) if out.status.success() => str::from_utf8(&out.stdout)?.to_owned(),
        (_, Some(fallback)) => fallback.as_ref().to_string(),
        (Ok(out), None) => bail!(
            "Command '{program} {}' failed: {}",
            arguments(),
            str::from_utf8(&out.stderr)?
        ),
        (Err(e), None) => bail!("Could not run '{program} {}': {e}", arguments()),
    };
    Ok(value.trim().to_string())
}

/// Looks up an executable in `PATH`, like `which` does.
///
/// Used to tell a command we do not need from one we cannot do without, as the tools we run are
/// not installed in the same place on every distribution.
pub(crate) fn find_in_path(program: &str) -> Option<PathBuf> {
    let found = env::split_paths(&env::var_os("PATH")?).find_map(|dir| {
        let path = dir.join(program);
        path.is_file().then_some(path)
    });
    match &found {
        Some(path) => debug!("Found '{program}' at '{}'", path.display()),
        None => debug!("Did not find '{program}' in PATH"),
    }
    found
}

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

/// Fields are declared in the order FusionInventory serializes them, to keep both outputs
/// easy to compare.
#[derive(Debug, PartialEq, Serialize)]
#[serde(rename_all = "UPPERCASE")]
pub struct Hardware {
    /// When the last user logged in, as `EEE MMM dd HH:mm`, the format the server parses.
    #[serde(rename = "DATELASTLOGGEDUSER", skip_serializing_if = "Option::is_none")]
    date_last_logged_user: Option<String>,
    #[serde(rename = "LASTLOGGEDUSER", skip_serializing_if = "Option::is_none")]
    last_logged_user: Option<String>,
    /// Total usable RAM, in megabytes, the unit the server assumes.
    #[serde(skip_serializing_if = "Option::is_none")]
    memory: Option<u64>,
    /// The hostname, without its domain.
    #[serde(skip_serializing_if = "Option::is_none")]
    name: Option<String>,
    /// The kernel build string, which the server keeps as the node description.
    #[serde(rename = "OSCOMMENTS", skip_serializing_if = "Option::is_none")]
    os_comments: Option<String>,
    /// Total swap, in megabytes.
    #[serde(skip_serializing_if = "Option::is_none")]
    swap: Option<u64>,
    #[serde(rename = "UUID", skip_serializing_if = "Option::is_none")]
    uuid: Option<String>,
    /// Absent when we cannot tell, in which case the server assumes a physical machine.
    #[serde(rename = "VMSYSTEM", skip_serializing_if = "Option::is_none")]
    machine_type: Option<MachineType>,
}

/// A kind of machine the server has a name for.
///
/// These are the values `FusionInventoryParser` matches `HARDWARE/VMSYSTEM` against, and the
/// only ones it does anything with. They are not the identifiers the server stores them under,
/// which are the `entryName` of its `VmType` and differ (`hyperv`, `vbox`): what has to match is
/// what it reads, not what it keeps. The parser lowercases before matching, and these are
/// already lowercase.
#[derive(Debug, PartialEq, Eq, Clone)]
pub enum MachineType {
    Physical,
    Xen,
    VirtualBox,
    VMware,
    Qemu,
    HyperV,
    Virtuozzo,
    OpenVz,
    Lxc,
    /// Virtualized by something the server has no name for. It reads this as an unknown kind of
    /// virtual machine, which is exactly what it is.
    VirtualMachine,
    /// A technology we have no name for either, reported as it named itself.
    ///
    /// The server reads anything it does not know as an unknown virtual machine, the same as
    /// [`Self::VirtualMachine`], so nothing is lost by passing it through. What is gained is
    /// that the inventory says which technology it was, for whoever has to add it here, where
    /// the value would otherwise be flattened into `virtual machine` and never noticed.
    Unknown(String),
}

impl MachineType {
    /// The value the server parses, which is the whole contract of this type.
    fn as_str(&self) -> &str {
        match self {
            Self::Physical => "physical",
            Self::Xen => "xen",
            Self::VirtualBox => "virtualbox",
            Self::VMware => "vmware",
            Self::Qemu => "qemu",
            Self::HyperV => "hyper-v",
            Self::Virtuozzo => "virtuozzo",
            Self::OpenVz => "openvz",
            Self::Lxc => "lxc",
            Self::VirtualMachine => "virtual machine",
            Self::Unknown(name) => name,
        }
    }
}

impl Serialize for MachineType {
    fn serialize<S: serde::Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        serializer.serialize_str(self.as_str())
    }
}

/// Names the kind of machine out of what `systemd-detect-virt` answered.
///
/// It names the technology on its standard output, and exits with a failure when it finds no
/// virtualization at all, in which case the machine is a physical one. The names are the ones the
/// server knows; a technology it does not know is still a virtual machine.
///
/// See <https://www.freedesktop.org/software/systemd/man/systemd-detect-virt.html>.
fn parse_machine_type(success: bool, stdout: &str) -> Option<MachineType> {
    let answer = stdout.trim();
    match (success, answer) {
        (false, "none") => Some(MachineType::Physical),
        (true, "lxc" | "lxc-libvirt") => Some(MachineType::Lxc),
        (true, "openvz") => Some(MachineType::OpenVz),
        (true, "xen") => Some(MachineType::Xen),
        (true, "vmware") => Some(MachineType::VMware),
        (true, "oracle") => Some(MachineType::VirtualBox),
        (true, "qemu" | "kvm") => Some(MachineType::Qemu),
        (true, "microsoft") => Some(MachineType::HyperV),
        // A technology the server has no name for, which it will read as an unknown virtual
        // machine. It is reported as it named itself rather than flattened, and said out loud:
        // `systemd-detect-virt` knows some thirty of these and this list holds ten, so a machine
        // landing here is a name worth adding rather than a machine worth guessing about.
        (true, _) => {
            warn!(
                "'{SYSTEMD_DETECT_VIRT}' reports '{answer}', which the server has no name for: \
                 reporting it as it is, to be read as an unknown virtual machine"
            );
            Some(MachineType::Unknown(answer.to_string()))
        }
        // A failure that says anything else is not an answer we can use.
        (false, _) => None,
    }
}

/// The terminal a process is attached to, named as `ps` names it.
///
/// The name of the executable sits between parentheses in `stat` and can hold anything, spaces
/// and parentheses included, so the fields are counted from the last parenthesis.
#[cfg(target_os = "linux")]
fn terminal_of(pid: &sysinfo::Pid) -> Option<String> {
    let stat = fs::read_to_string(format!("/proc/{pid}/stat")).ok()?;
    let after_name = stat.rsplit_once(')')?.1;
    // After the name come the state, the parent, the group, the session, then the terminal.
    terminal(after_name.split_whitespace().nth(4)?.parse().ok()?)
}

#[cfg(not(target_os = "linux"))]
fn terminal_of(_pid: &sysinfo::Pid) -> Option<String> {
    None
}

/// Names the terminal a device number designates.
///
/// The kernel packs a major and a minor number into it, and uses zero for a process attached to
/// no terminal at all. We name the classes of terminal a process is actually attached to, and
/// report nothing for anything else rather than a name we would not be sure of.
fn terminal(tty_nr: i32) -> Option<String> {
    if tty_nr <= 0 {
        return None;
    }
    let major = (tty_nr >> 8) & 0xfff;
    let minor = (tty_nr & 0xff) | ((tty_nr >> 12) & 0xfff00);
    match major {
        // The virtual consoles, then the serial lines numbered after them.
        4 if minor < 64 => Some(format!("tty{minor}")),
        4 => Some(format!("ttyS{}", minor - 64)),
        // The pseudo terminals, in blocks of 256 for each major.
        136..=143 => Some(format!("pts/{}", minor + (major - 136) * 256)),
        _ => None,
    }
}

/// When a process started, in the local time FusionInventory reports it in.
///
/// The server keeps this as it is written, so the only thing that matters is that a date means
/// the same as the one another agent would have reported for the same process.
fn process_started(timestamp: u64) -> Option<String> {
    let started = Timestamp::from_second(i64::try_from(timestamp).ok()?)
        .ok()?
        .to_zoned(TimeZone::system());
    Some(started.strftime("%Y-%m-%d %H:%M").to_string())
}

/// The share of the memory of the machine a process holds, as the percentage `ps` prints.
///
/// Truncated to a tenth rather than rounded, which is what `ps` does, so that a process is
/// reported as FusionInventory reports it, passing the value of `ps` through. Rounding would
/// also double the share of the smallest processes.
///
/// Nothing when we do not know how much memory the machine has, as every share would be
/// meaningless then.
fn memory_share(resident: u64, total: u64) -> Option<String> {
    if total == 0 {
        return None;
    }
    #[expect(
        clippy::cast_precision_loss,
        reason = "a percentage with one decimal does not need more precision than this"
    )]
    let share = resident as f64 / total as f64 * 100.0;
    Some(format!("{:.1}", (share * 10.0).floor() / 10.0))
}

/// What a process is running, as `ps` prints it.
///
/// A kernel thread has no command line at all, and `ps` names it by the name the kernel gives
/// it, between brackets, as in `[kworker/u51:0]`. Those brackets are how a reader tells a
/// kernel thread from a process that happens to carry the same name, so we write them too and
/// both agents call the same process the same thing. They are the majority of the section on
/// an idle machine.
fn command_line(cmd: &str, name: &str) -> String {
    match cmd.trim() {
        "" => format!("[{}]", printable(name)),
        cmd => printable(cmd),
    }
}

/// A command line with the characters an inventory cannot carry taken out of it.
///
/// A process is free to hold anything at all in its arguments, control characters included, and
/// any user of the machine can start one. XML has no way to carry those: they are forbidden in
/// a document, and so is a character reference naming one, so `&#1;` is not an escape for them
/// but another way of writing the same invalid document. Left alone, one process is enough to
/// make the whole inventory unparsable, and the node then reports nothing at all.
///
/// So they are replaced, as `ps` replaces them, which is how FusionInventory never carries one
/// either. A newline becomes a space, since `ps` prints one process per line, and the rest
/// become a question mark.
///
/// `ps` writes that question mark as a dot under some locales, and, under `LANG=C`, replaces
/// every byte of a legitimately accented or non-latin command line with one as well. We keep
/// those characters: the server stores them, and losing what a process really runs to imitate
/// an artifact of the locale `ps` was called under would help nobody.
fn printable(value: &str) -> String {
    value
        .chars()
        .map(|c| match c {
            // `ps` prints one process per line, so a command line holds no line of its own.
            '\n' | '\r' => ' ',
            c if c.is_control() => '?',
            c => c,
        })
        .collect()
}

/// The share of a processor a process has held, as the percentage `ps` prints.
///
/// This is the `%CPU` of `ps`, which is not the instantaneous usage it reads like: it is the
/// CPU time the process has used over its whole life, divided by how long it has been running.
/// A process that spent a second computing and then idled for an hour is reported near zero.
/// `sysinfo` offers the instantaneous one instead, which is a delta between two refreshes and
/// so is zero for every process on the single refresh a run does.
///
/// The arithmetic is the one of `pr_pcpu` in `procps`, in the order it performs it, so that a
/// value lands on the same tenth: the accumulated time is in the CPU milliseconds
/// `total_time * 1000 / Hertz` gives, and dividing it by the seconds the process has been
/// alive gives tenths of a percent directly.
///
/// It can pass 100% on a machine with more than one processor, where a process that used two
/// of them for its whole life is reported at 200%. `ps` drops the decimal there, and so do we.
fn cpu_share(accumulated_milliseconds: u64, run_time_seconds: u64) -> String {
    // A process started within the second has no share yet, and no time to divide by.
    if run_time_seconds == 0 {
        return "0.0".to_string();
    }
    let tenths = accumulated_milliseconds / run_time_seconds;
    if tenths > 999 {
        return (tenths / 10).to_string();
    }
    format!("{}.{}", tenths / 10, tenths % 10)
}

/// Converts a number of bytes to the megabytes the server expects, dropping a zero value as
/// the platform not having answered.
fn megabytes(bytes: u64) -> Option<u64> {
    (bytes > 0).then_some(bytes / 1024 / 1024)
}

/// The version of the operating system, and the service pack it may carry.
///
/// FusionInventory reports the version out of one of two modules, and which one runs decides
/// what a version is:
///
/// * `Distro::LSB` runs on any distribution that has `lsb_release`, which is almost all of
///   them, and reports the `Release:` it prints. That is the bare number, `26.04` and not
///   `26.04 LTS (Resolute Raccoon)`, and it is what `VERSION_ID` holds.
/// * `Distro::NonLSB` runs on SUSE, on Oracle, and where there is no `lsb_release`. Falling
///   back to `/etc/os-release`, it reports `VERSION`, which is where SUSE carries its service
///   pack as `15-SP5` and where the two are split apart.
///
/// We read `/etc/os-release` and nothing else, so we take `VERSION_ID` as the version, and
/// `VERSION` only when it names a service pack, which is the one case its extra content is
/// what we are after. Both agents then report `26.04` for the machine above, and `15` with a
/// service pack of `5` for a SLES 15 SP5 one.
fn version_and_service_pack(
    version: Option<&str>,
    version_id: Option<&str>,
) -> (String, Option<String>) {
    // A version ending in "-SP" names no service pack, and is not the SUSE case.
    if let Some((base, service_pack)) = version.and_then(|v| v.rsplit_once("-SP"))
        && !service_pack.is_empty()
    {
        return (base.to_string(), Some(service_pack.to_string()));
    }
    // `VERSION_ID` is optional, where a distribution that omits it leaves us with `VERSION`.
    (version_id.or(version).unwrap_or_default().to_string(), None)
}

/// The command that lists the logins of the machine, the most recent first.
const LAST: &str = "last";

/// The last user to have logged in, and when.
fn last_logged_user() -> (Option<String>, Option<String>) {
    if find_in_path(LAST).is_none() {
        debug!("No '{LAST}', reporting no last logged user");
        return (None, None);
    }
    match cmd(LAST, &[], None) {
        Ok(output) => parse_last_logged_user(&output),
        Err(e) => {
            debug!("Could not run '{LAST}': {e:#}");
            (None, None)
        }
    }
}

/// Reads the user and the date of the most recent login `last` prints.
///
/// It prints the most recent first, and like FusionInventory we keep the first line that is not
/// the machine starting or stopping. The columns between the user and the date vary, so the date
/// is the four fields that start on a day of the week, which is the `EEE MMM dd HH:mm` the
/// server parses. A login we cannot date is still a login, and is reported without one.
fn parse_last_logged_user(output: &str) -> (Option<String>, Option<String>) {
    for line in output.lines() {
        let mut fields = line.split_whitespace();
        let Some(user) = fields.next() else {
            continue;
        };
        // The pseudo users of the boot, and the footer naming the file itself.
        if matches!(user, "reboot" | "shutdown" | "wtmp" | "btmp") {
            continue;
        }
        let fields: Vec<&str> = fields.collect();
        let date = fields
            .iter()
            .position(|field| is_week_day(field))
            .and_then(|start| fields.get(start..start + 4))
            .map(|date| date.join(" "));
        return (Some(user.to_string()), date);
    }
    (None, None)
}

fn is_week_day(field: &str) -> bool {
    matches!(
        field.to_lowercase().as_str(),
        "mon" | "tue" | "wed" | "thu" | "fri" | "sat" | "sun"
    )
}

/// The command that tells whether the machine is virtualized.
const SYSTEMD_DETECT_VIRT: &str = "systemd-detect-virt";

/// The DMI identifier of the machine, which the server keeps as its motherboard UUID.
///
/// This is how a virtual machine is told apart from a clone of itself. It is only readable by
/// root, and absent on the platforms without DMI, in which case we report nothing.
fn machine_uuid() -> Option<String> {
    let uuid = Product::uuid().and_then(|u| dmi_value(&u));
    if uuid.is_none() {
        // Expected when we do not run as root, which is why this is not a warning.
        debug!("Could not read the DMI identifier of the machine");
    }
    uuid
}

impl Hardware {
    /// How the machine is virtualized, if it is and if we can tell.
    ///
    /// `systemd-detect-virt` is asked first and believed: it is the only source that sees a
    /// container, which DMI cannot, and it tells a Xen host from a Xen guest. The firmware is
    /// only read when the command is not installed or answers nothing we can use, and it
    /// answers a narrower question, described in [`Bios::machine_type`].
    ///
    /// The server treats a missing value as a physical machine, so a system neither can name is
    /// reported rather than refused.
    fn machine_type(bios: Option<&Bios>) -> Option<MachineType> {
        if let Some(machine_type) = Self::from_systemd_detect_virt() {
            debug!(
                "Detected machine type '{}' with '{SYSTEMD_DETECT_VIRT}'",
                machine_type.as_str()
            );
            return Some(machine_type);
        }
        match bios.and_then(Bios::machine_type) {
            Some(machine_type) => {
                debug!(
                    "Detected machine type '{}' from the firmware",
                    machine_type.as_str()
                );
                Some(machine_type)
            }
            None => {
                debug!("Nothing names the kind of this machine, reporting none");
                None
            }
        }
    }

    /// What `systemd-detect-virt` answers, or nothing when it is absent or unhelpful.
    fn from_systemd_detect_virt() -> Option<MachineType> {
        if find_in_path(SYSTEMD_DETECT_VIRT).is_none() {
            debug!("No '{SYSTEMD_DETECT_VIRT}', falling back to the firmware");
            return None;
        }
        let cmd = Command::new(SYSTEMD_DETECT_VIRT).output().ok()?;
        let machine_type =
            parse_machine_type(cmd.status.success(), str::from_utf8(&cmd.stdout).ok()?);
        if machine_type.is_none() {
            warn!("Could not detect the machine type from '{SYSTEMD_DETECT_VIRT}'");
        }
        machine_type
    }
}

/// The local timezone, which the server only keeps when it has both a name and an offset.
#[derive(Debug, PartialEq, Serialize)]
#[serde(rename_all = "UPPERCASE")]
pub struct Timezone {
    /// The IANA name of the zone, like `Europe/Paris`.
    name: String,
    /// The current offset from UTC, like `+0200`. It depends on the date, as most zones have
    /// a summer time.
    offset: String,
}

/// The IANA names that are another way of writing `UTC`.
///
/// The zone database keeps a name it has ever published working forever, as a link to the zone
/// that replaced it, and these seventeen all link to `UTC`. `UTC` itself is the name they link
/// to, so it is not one of them.
const UTC_ALIASES: &[&str] = &[
    "Etc/GMT",
    "Etc/GMT+0",
    "Etc/GMT-0",
    "Etc/GMT0",
    "Etc/Greenwich",
    "Etc/UCT",
    "Etc/UTC",
    "Etc/Universal",
    "Etc/Zulu",
    "GMT",
    "GMT+0",
    "GMT-0",
    "GMT0",
    "Greenwich",
    "UCT",
    "Universal",
    "Zulu",
];

/// The name of a zone, under the one name FusionInventory would report it by, when we know it.
///
/// FusionInventory asks `DateTime::TimeZone` for the zone, which resolves the links of the
/// database before naming it, so a machine whose `/etc/localtime` points at `Etc/UTC` is
/// reported as `UTC`. `jiff` reports the name it was given, which is the more accurate answer:
/// `Etc/UTC` is what the machine is really configured with. The server keeps the name as it is
/// written, though, so two agents disagreeing means the same machine changes timezone in the
/// interface depending on which one inventoried it.
///
/// So we follow FusionInventory here. Only the links to `UTC` are resolved, of the 249 the
/// database holds: a machine set to UTC is the common case by far, and the rest are region
/// renames, where a node keeps whichever of `Asia/Calcutta` and `Asia/Kolkata` it is set to.
/// Those still differ from FusionInventory, which reports the new name for both.
fn zone_name(name: &str) -> String {
    if UTC_ALIASES.contains(&name) {
        return "UTC".to_string();
    }
    name.to_string()
}

impl Timezone {
    /// Both values come from the same zone, so they cannot disagree.
    fn new() -> Option<Self> {
        let now = Zoned::now();
        Some(Self {
            name: zone_name(now.time_zone().iana_name()?),
            offset: now.strftime("%z").to_string(),
        })
    }
}

#[derive(Debug, PartialEq, Serialize)]
#[serde(rename_all = "UPPERCASE")]
pub struct User {
    login: String,
}

impl Inventory {
    #[instrument(level = "debug", name = "inventory")]
    pub fn new() -> Result<Self> {
        #[cfg(unix)]
        // The values are borrowed from it, so it has to outlive the inventory we build below.
        let uts = uname().context("Reading the kernel identification")?;
        let fqdn = fqdn()?;
        // The short name, where FQDN holds the qualified one.
        let hostname = hostname()?;
        let os_release = os_release()?;

        let users_src = Users::new_with_refreshed_list();

        let mut sys = System::new();
        sys.refresh_memory();

        let users: Vec<User> = users_src
            .iter()
            .map(|u| User {
                login: u.name().to_string(),
            })
            .collect();

        sys.refresh_cpu_all();
        let cpus = Cpu::inventory(&sys);

        // Only what the section reports: `refresh_processes` would ask for the disk usage and
        // the executable we have no use for, leave out the owner we do need, and list the
        // threads of every process along with the processes themselves.
        sys.refresh_processes_specifics(
            ProcessesToUpdate::All,
            true,
            ProcessRefreshKind::nothing()
                .with_cmd(UpdateKind::Always)
                .with_cpu()
                .with_memory()
                .with_user(UpdateKind::Always),
        );
        let total_memory = sys.total_memory();

        let processes: Vec<Process> = sys
            .processes()
            .iter()
            // The threads of a process are not processes, where the kernel threads are: `ps`
            // lists those, between brackets, and so do we. Asking for no task is not enough on
            // its own, as `sysinfo` documents that ruling a refresh out does not guarantee the
            // information is left alone when it comes for free.
            .filter(|(_, p)| p.thread_kind() != Some(ThreadKind::Userland))
            .map(|(pid, p)| Process {
                cmd: command_line(
                    &p.cmd()
                        .iter()
                        .map(|s| s.to_string_lossy())
                        .collect::<Vec<_>>()
                        .join(" "),
                    &p.name().to_string_lossy(),
                ),
                cpu_usage: cpu_share(p.accumulated_cpu_time(), p.run_time()),
                mem: memory_share(p.memory(), total_memory),
                pid: pid.to_string(),
                started: process_started(p.start_time()),
                tty: terminal_of(pid),
                // Unknown for the processes we are not allowed to look at, where an empty
                // element would be worse than none.
                user: p
                    .user_id()
                    .and_then(|id| users_src.get_user_by_id(id))
                    .map(|u| u.name().to_string()),
                // `ps` reports it in kilobytes.
                virtual_memory: p.virtual_memory() / 1024,
            })
            .collect();

        let (softwares, software_updates) = packages::inventory(&os_release)?;

        let (last_logged_user, date_last_logged_user) = last_logged_user();

        let (version, service_pack) = version_and_service_pack(
            os_release.version.as_deref(),
            os_release.version_id.as_deref(),
        );
        if let Some(ref service_pack) = service_pack {
            debug!("Operating system is version {version} service pack {service_pack}");
        }

        debug!(
            "Found {} users, {} processes and {} environment variables",
            users.len(),
            processes.len(),
            env::vars().count()
        );

        #[cfg(target_os = "linux")]
        let networks = networks::inventory();

        // The firmware names the kind of machine when `systemd-detect-virt` cannot, so the
        // section is built before the one that may need it.
        let bios = Bios::inventory();
        let machine_type = Hardware::machine_type(bios.as_ref());

        Ok(Self {
            env: env::vars()
                .map(|(key, value)| EnvironmentVariable { key, value })
                .collect(),
            agent: format!("{}_v{}", env!("CARGO_PKG_NAME"), env!("CARGO_PKG_VERSION")),
            operating_system: OperatingSystem {
                // Already in the uname data, no need to run the command for it.
                #[cfg(unix)]
                arch: uts.machine().to_string_lossy().into_owned(),
                #[cfg(not(unix))]
                arch: System::cpu_arch(),
                fqdn: fqdn.clone(),
                full_name: os_release.pretty_name,
                #[cfg(unix)]
                kernel_name: uts.sysname().to_string_lossy().to_lowercase(),
                #[cfg(unix)]
                kernel_version: uts.release().to_string_lossy().into_owned(),
                name: os_release.name,
                service_pack,
                timezone: Timezone::new(),
                version,
            },
            users,
            rudder: Rudder::new(fqdn)?,
            // Read before the struct, as the kind of machine falls back to what it holds.
            bios,
            cpus,
            drives: drives::inventory(),
            hardware: Hardware {
                date_last_logged_user,
                last_logged_user,
                memory: megabytes(sys.total_memory()),
                name: Some(hostname),
                #[cfg(unix)]
                os_comments: Some(uts.version().to_string_lossy().into_owned()),
                #[cfg(not(unix))]
                os_comments: None,
                swap: megabytes(sys.total_swap()),
                uuid: machine_uuid(),
                machine_type,
            },
            #[cfg(target_os = "linux")]
            networks,
            processes,
            softwares,
            software_updates,
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
    // <FQDN>server.rudder.local</FQDN>
    fqdn: String,
    // <FULL_NAME>CentOS Stream release 8</FULL_NAME>
    full_name: String,
    // <KERNEL_NAME>linux</KERNEL_NAME>
    #[cfg(unix)]
    kernel_name: String,
    // <KERNEL_VERSION>4.18.0-365.el8.x86_64</KERNEL_VERSION>
    #[cfg(unix)]
    kernel_version: String,
    // <NAME>CentOS</NAME>
    name: String,
    /// Only SUSE has one, as part of its version string.
    #[serde(rename = "SERVICE_PACK", skip_serializing_if = "Option::is_none")]
    service_pack: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    timezone: Option<Timezone>,
    // <VERSION>8</VERSION>
    version: String,
}

#[derive(Debug, PartialEq, Serialize)]
#[serde(rename_all = "UPPERCASE")]
pub struct Process {
    cmd: String,
    #[serde(rename = "CPUUSAGE")]
    cpu_usage: String,
    /// Share of the physical memory of the machine, as the percentage `ps` prints.
    #[serde(skip_serializing_if = "Option::is_none")]
    mem: Option<String>,
    pid: String,
    // <STARTED>2022-12-16 11:55</STARTED>
    #[serde(skip_serializing_if = "Option::is_none")]
    started: Option<String>,
    /// The terminal the process is attached to, if any.
    #[serde(rename = "TTY", skip_serializing_if = "Option::is_none")]
    tty: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    user: Option<String>,
    /// Virtual size, in kilobytes, the unit `ps` prints and the server assumes.
    #[serde(rename = "VIRTUALMEMORY")]
    virtual_memory: u64,
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
    #[cfg(target_os = "linux")]
    #[serde(rename = "NETWORKS")]
    networks: Vec<Network>,
    processes: Vec<Process>,
    #[serde(rename = "SOFTWARES")]
    softwares: Vec<Package>,
    #[serde(rename = "SOFTWAREUPDATES")]
    software_updates: Vec<Update>,
    #[serde(rename = "ACCESSLOG")]
    access_log: AccessLog,
}

#[cfg(test)]
mod tests {
    use pretty_assertions::assert_eq;

    use super::*;

    /// Every element name has to be the one FusionInventory uses. The `UPPERCASE` rename
    /// keeps underscores, so any field whose element has none needs an explicit rename, which
    /// this pins down.
    #[test]
    fn it_serializes_the_hardware_section_like_fusion_inventory() {
        let hardware = Hardware {
            date_last_logged_user: Some("Thu Oct 23 18:09".to_string()),
            last_logged_user: Some("root".to_string()),
            memory: Some(32022),
            name: Some("dev".to_string()),
            os_comments: Some("#29-Ubuntu SMP".to_string()),
            swap: Some(2048),
            uuid: Some("72d25ff8-c736-436b-b62c-1501cd47b63b".to_string()),
            machine_type: Some(MachineType::Qemu),
        };
        let mut out = String::new();
        let mut ser = Serializer::with_root(&mut out, Some("HARDWARE")).unwrap();
        ser.indent(' ', 2);
        hardware.serialize(ser).unwrap();
        assert_eq!(
            out,
            concat!(
                "<HARDWARE>\n",
                "  <DATELASTLOGGEDUSER>Thu Oct 23 18:09</DATELASTLOGGEDUSER>\n",
                "  <LASTLOGGEDUSER>root</LASTLOGGEDUSER>\n",
                "  <MEMORY>32022</MEMORY>\n",
                "  <NAME>dev</NAME>\n",
                "  <OSCOMMENTS>#29-Ubuntu SMP</OSCOMMENTS>\n",
                "  <SWAP>2048</SWAP>\n",
                "  <UUID>72d25ff8-c736-436b-b62c-1501cd47b63b</UUID>\n",
                "  <VMSYSTEM>qemu</VMSYSTEM>\n",
                "</HARDWARE>",
            )
        );
    }

    #[test]
    fn it_splits_the_suse_service_pack_out_of_the_version() {
        // What SLES 15 and 12 report, where the service pack has to be reported on its own and
        // `VERSION_ID` would give "15.5" and no service pack at all.
        assert_eq!(
            version_and_service_pack(Some("15-SP5"), Some("15.5")),
            ("15".to_string(), Some("5".to_string()))
        );
        assert_eq!(
            version_and_service_pack(Some("12-SP3"), Some("12.3")),
            ("12".to_string(), Some("3".to_string()))
        );
        // SLES without a service pack, and openSUSE Leap, which never has one.
        assert_eq!(
            version_and_service_pack(Some("15"), Some("15")),
            ("15".to_string(), None)
        );
        assert_eq!(
            version_and_service_pack(Some("15.5"), Some("15.5")),
            ("15.5".to_string(), None)
        );
        // A version naming no service pack must not report an empty one.
        assert_eq!(
            version_and_service_pack(Some("15-SP"), Some("15")),
            ("15".to_string(), None)
        );
    }

    /// The bare number `lsb_release -r` prints, which is what FusionInventory reports on every
    /// distribution that has it, rather than the sentence `VERSION` holds.
    #[test]
    fn it_reports_the_version_without_what_the_distribution_adds_to_it() {
        assert_eq!(
            version_and_service_pack(Some("26.04 LTS (Resolute Raccoon)"), Some("26.04")),
            ("26.04".to_string(), None)
        );
        assert_eq!(
            version_and_service_pack(Some("12 (bookworm)"), Some("12")),
            ("12".to_string(), None)
        );
        // A distribution that names no `VERSION_ID` leaves us with `VERSION`.
        assert_eq!(
            version_and_service_pack(Some("rolling"), None),
            ("rolling".to_string(), None)
        );
        // And one that names neither, which the server refuses. Nothing to report is still
        // better than refusing to run over it.
        assert_eq!(version_and_service_pack(None, None), (String::new(), None));
    }

    /// Checks the whole chain on the two machines the two FusionInventory modules cover.
    #[test]
    fn it_reports_the_version_of_a_machine_as_fusion_inventory_does() {
        // A SLES 15 SP5 machine, which FusionInventory inventories with `Distro::NonLSB`.
        let sles = OsRelease::from_string(
            r#"NAME="SLES"
VERSION="15-SP5"
VERSION_ID="15.5"
PRETTY_NAME="SUSE Linux Enterprise Server 15 SP5"
ID="sles"
ID_LIKE="suse"
CPE_NAME="cpe:/o:suse:sles:15:sp5"
"#,
        );
        assert_eq!(
            version_and_service_pack(sles.version.as_deref(), sles.version_id.as_deref()),
            ("15".to_string(), Some("5".to_string()))
        );

        // The machine this is written on, which it inventories with `Distro::LSB`, where
        // `lsb_release -r` prints the "26.04" that `VERSION_ID` holds.
        let ubuntu = OsRelease::from_string(
            r#"PRETTY_NAME="Ubuntu 26.04 LTS"
NAME="Ubuntu"
VERSION_ID="26.04"
VERSION="26.04 LTS (Resolute Raccoon)"
ID=ubuntu
"#,
        );
        assert_eq!(
            version_and_service_pack(ubuntu.version.as_deref(), ubuntu.version_id.as_deref()),
            ("26.04".to_string(), None)
        );
    }

    #[test]
    fn it_returns_the_output_of_a_command() {
        let _guard = no_concurrent_fork();
        assert_eq!(cmd("echo", &["  hello  "], None).unwrap(), "hello");
    }

    #[test]
    fn it_falls_back_when_a_command_fails() {
        let _guard = no_concurrent_fork();
        assert_eq!(cmd("false", &[], Some("fallback")).unwrap(), "fallback");
    }

    /// The point of the fallback: a command that is not installed is the same outcome as one
    /// that fails, so a caller with a fallback keeps working on a system without it.
    #[test]
    fn it_falls_back_when_a_command_is_not_installed() {
        let _guard = no_concurrent_fork();
        assert_eq!(
            cmd("this-command-does-not-exist", &[], Some("fallback")).unwrap(),
            "fallback"
        );
    }

    #[test]
    fn it_fails_on_a_command_it_cannot_run_without_a_fallback() {
        let _guard = no_concurrent_fork();
        let err = cmd("this-command-does-not-exist", &[], None).unwrap_err();
        assert!(
            err.to_string().contains("this-command-does-not-exist"),
            "the error does not name the command: {err}"
        );
        let err = cmd("false", &[], None).unwrap_err();
        assert!(err.to_string().contains("false"), "{err}");
    }

    #[test]
    fn it_finds_executables_in_path() {
        assert_eq!(find_in_path("this-program-does-not-exist"), None);
        // Present on every platform we support.
        assert!(find_in_path(if cfg!(windows) { "cmd.exe" } else { "sh" }).is_some());
    }

    #[test]
    fn it_reads_the_last_login() {
        // Real `last` output, whose columns between the user and the date vary.
        let output = "root     pts/0        192.168.122.1    Thu Oct 23 18:09   still logged in
admin    pts/1        192.168.122.7    Thu Oct 23 09:14 - 11:02  (01:48)
reboot   system boot  6.1.0-34-amd64   Wed Oct 22 21:31   still running

wtmp begins Wed Oct 22 21:31:04 2025
";
        assert_eq!(
            parse_last_logged_user(output),
            (
                Some("root".to_string()),
                Some("Thu Oct 23 18:09".to_string())
            )
        );
    }

    /// `last` pads a single digit day with two spaces, and the date has to come out with one,
    /// as the format the server parses has. FusionInventory normalizes it the same way.
    #[test]
    fn it_normalizes_the_spacing_of_a_single_digit_day() {
        let output = "dev      pts/2        192.168.122.1    Thu Aug  6 16:18 - still logged in\n";
        assert_eq!(
            parse_last_logged_user(output),
            (Some("dev".to_string()), Some("Thu Aug 6 16:18".to_string()))
        );
    }

    #[test]
    fn it_skips_the_logins_that_are_not_users() {
        // A machine nobody logged into since it started.
        let output = "reboot   system boot  6.1.0-34-amd64   Wed Oct 22 21:31   still running
shutdown system down  6.1.0-34-amd64   Wed Oct 22 21:30 - 21:31  (00:00)

wtmp begins Wed Oct 22 21:30:00 2025
";
        assert_eq!(parse_last_logged_user(output), (None, None));
    }

    #[test]
    fn it_reads_a_login_without_a_host() {
        let output = "root     tty1                          Thu Oct 23 18:09\n";
        assert_eq!(
            parse_last_logged_user(output),
            (
                Some("root".to_string()),
                Some("Thu Oct 23 18:09".to_string())
            )
        );
    }

    #[test]
    fn it_reports_a_login_it_cannot_date() {
        // A line we can read a user from but no date, which is still a login.
        assert_eq!(
            parse_last_logged_user("root     pts/0\n"),
            (Some("root".to_string()), None)
        );
        assert_eq!(parse_last_logged_user(""), (None, None));
    }

    /// Every answer `systemd-detect-virt` can give that the server has a name for.
    #[test]
    fn it_names_the_kind_of_machine() {
        // It exits with a failure, and says so, when the machine is a physical one.
        assert_eq!(
            parse_machine_type(false, "none\n"),
            Some(MachineType::Physical)
        );
        for (answer, expected) in [
            ("lxc", MachineType::Lxc),
            ("lxc-libvirt", MachineType::Lxc),
            ("openvz", MachineType::OpenVz),
            ("xen", MachineType::Xen),
            ("vmware", MachineType::VMware),
            ("oracle", MachineType::VirtualBox),
            ("qemu", MachineType::Qemu),
            ("kvm", MachineType::Qemu),
            ("microsoft", MachineType::HyperV),
        ] {
            assert_eq!(
                parse_machine_type(true, answer),
                Some(expected.clone()),
                "{answer}"
            );
            // The command ends its answer with a newline.
            assert_eq!(
                parse_machine_type(true, &format!("{answer}\n")),
                Some(expected)
            );
        }
        // A technology the server has no name for is reported as it named itself, and read as
        // an unknown virtual machine. `systemd-detect-virt` knows some thirty of these.
        for answer in ["bhyve", "powervm", "apple", "some-new-hypervisor"] {
            assert_eq!(
                parse_machine_type(true, answer),
                Some(MachineType::Unknown(answer.to_string())),
                "{answer}"
            );
            // Trimmed, as it is for the ones we know.
            assert_eq!(
                parse_machine_type(true, &format!("{answer}\n")),
                Some(MachineType::Unknown(answer.to_string()))
            );
        }
        // A failure that says anything else is not an answer.
        assert_eq!(parse_machine_type(false, ""), None);
        assert_eq!(parse_machine_type(false, "error"), None);
    }

    /// The value the server parses is the whole contract of the type, and these are the cases
    /// `FusionInventoryParser` matches on. A name that stops matching stops being understood,
    /// silently, so they are pinned here.
    #[test]
    fn it_reports_a_machine_type_the_server_has_a_name_for() {
        for (machine_type, expected) in [
            (MachineType::Physical, "physical"),
            (MachineType::Xen, "xen"),
            (MachineType::VirtualBox, "virtualbox"),
            (MachineType::VMware, "vmware"),
            (MachineType::Qemu, "qemu"),
            (MachineType::HyperV, "hyper-v"),
            (MachineType::Virtuozzo, "virtuozzo"),
            (MachineType::OpenVz, "openvz"),
            (MachineType::Lxc, "lxc"),
            (MachineType::VirtualMachine, "virtual machine"),
            // Passed through, for the server to read as an unknown virtual machine.
            (MachineType::Unknown("bhyve".to_string()), "bhyve"),
        ] {
            assert_eq!(machine_type.as_str(), expected);
            // And serialized as that same value, not as the name of the variant.
            let mut out = String::new();
            let ser = Serializer::with_root(&mut out, Some("VMSYSTEM")).unwrap();
            machine_type.serialize(ser).unwrap();
            assert_eq!(out, format!("<VMSYSTEM>{expected}</VMSYSTEM>"));
        }
    }

    /// Everything below reads the machine we run on. What needs a command or a file we may not
    /// have is only asserted when it is there, and the module is expected to report nothing for
    /// it otherwise.
    #[test]
    fn it_reads_the_kind_of_this_machine() {
        let _guard = no_concurrent_fork();
        let bios = Bios::inventory();
        let from_command = Hardware::from_systemd_detect_virt();
        let reported = Hardware::machine_type(bios.as_ref());

        // Whatever this machine is, it is named by something the server can read.
        if let Some(reported) = &reported {
            assert!(!reported.as_str().is_empty());
        }
        match from_command {
            // The command is asked first and believed. The two sources are *not* asserted to
            // agree: they disagree on a container, which has the firmware of the machine
            // underneath it, and this test runs in one wherever CI does. The firmware says
            // `qemu` there and the command says `docker`, which is the whole reason for the
            // order.
            Some(from_command) => assert_eq!(
                reported,
                Some(from_command),
                "'{SYSTEMD_DETECT_VIRT}' answered and was not believed"
            ),
            // Without it, the firmware answers on its own, or nothing does.
            None => assert_eq!(reported, bios.and_then(|b| b.machine_type())),
        }
    }

    #[test]
    fn it_reads_the_fqdn_of_this_machine() {
        let _guard = no_concurrent_fork();
        let fqdn = fqdn().expect("no fully qualified name");
        assert!(!fqdn.is_empty());
        // A name, not a line of output.
        assert!(!fqdn.contains(char::is_whitespace), "{fqdn}");
    }

    #[test]
    fn it_reads_the_timezone_of_this_machine() {
        let timezone = Timezone::new().expect("no local timezone");
        assert!(!timezone.name.is_empty());
        assert_eq!(timezone.offset.len(), "+0000".len(), "{}", timezone.offset);
    }

    #[test]
    fn it_reads_the_terminal_of_a_running_process() {
        let mut sys = System::new();
        sys.refresh_processes(ProcessesToUpdate::All, true);
        let mut named = 0;
        for pid in sys.processes().keys() {
            let Some(tty) = terminal_of(pid) else {
                continue;
            };
            named += 1;
            // Every terminal we name has to look like one.
            assert!(
                tty.starts_with("tty") || tty.starts_with("pts/"),
                "'{tty}' is not the name of a terminal"
            );
            // And has to have been named for a process the kernel says has one, its device
            // number being zero otherwise. Nothing is asserted the other way round: a process
            // attached to a class of device we would not know how to name is reported without
            // a terminal although its number is not zero.
            //
            // Which processes have a terminal is the machine's business and not ours: this
            // used to assert that the first process of the machine had none, which is true
            // until the machine is a container someone allocated a terminal to.
            #[cfg(target_os = "linux")]
            {
                let stat = fs::read_to_string(format!("/proc/{pid}/stat")).unwrap_or_default();
                if let Some(number) = stat
                    .rsplit_once(')')
                    .and_then(|(_, rest)| rest.split_whitespace().nth(4))
                    .and_then(|n| n.parse::<i32>().ok())
                {
                    assert_ne!(
                        number, 0,
                        "'{tty}' was named for a process attached to no terminal"
                    );
                }
            }
        }
        debug!("{named} processes of this machine are attached to a terminal");
    }

    /// The section is built out of what `sysinfo` is asked to refresh, and asking for the wrong
    /// thing is silent: a missing owner comes out as an absent element, and a task comes out as
    /// a process of its own. Both happened, so what we depend on is pinned here.
    #[test]
    #[cfg(target_os = "linux")]
    fn it_inventories_the_processes_of_this_machine_as_ps_lists_them() {
        let users = Users::new_with_refreshed_list();
        let mut sys = System::new();
        sys.refresh_processes_specifics(
            ProcessesToUpdate::All,
            true,
            ProcessRefreshKind::nothing()
                .with_cmd(UpdateKind::Always)
                .with_cpu()
                .with_memory()
                .with_user(UpdateKind::Always),
        );
        let kept: Vec<_> = sys
            .processes()
            .iter()
            .filter(|(_, p)| p.thread_kind() != Some(ThreadKind::Userland))
            .collect();
        assert!(kept.len() > 1, "no process at all");

        // A thread of a process is not a process: the kernel names the process it belongs to in
        // `Tgid`, which is its own pid for a process. A process that ended in the meantime is
        // simply gone, and is no counter-example.
        for (pid, _) in &kept {
            let Ok(status) = fs::read_to_string(format!("/proc/{pid}/status")) else {
                continue;
            };
            let tgid = status
                .lines()
                .find_map(|l| l.strip_prefix("Tgid:"))
                .map(str::trim);
            assert_eq!(tgid, Some(pid.to_string().as_str()), "{pid} is a thread");
        }

        // The kernel threads are processes, and `ps` lists them, so every one of them has to
        // be kept. Counted rather than merely looked for, as a container has none of its own:
        // they live in the PID namespace of the machine, not in the one it runs in.
        let kernel = |ps: &mut dyn Iterator<Item = &sysinfo::Process>| {
            ps.filter(|p| p.thread_kind() == Some(ThreadKind::Kernel))
                .count()
        };
        assert_eq!(
            kernel(&mut kept.iter().map(|(_, p)| *p)),
            kernel(&mut sys.processes().values()),
            "kernel threads were dropped along with the tasks"
        );

        // The owner of a process, which is only refreshed when it is asked for. Not every
        // process is one we are allowed to look at, so this only holds for the one we run as.
        let ours = sys
            .process(sysinfo::Pid::from_u32(std::process::id()))
            .expect("the test process is not in the list");
        let owner = ours
            .user_id()
            .and_then(|id| users.get_user_by_id(id))
            .map(|u| u.name().to_string());
        assert!(owner.is_some(), "no owner for the process we run as");
    }

    #[test]
    fn it_reads_the_last_login_of_this_machine() {
        let _guard = no_concurrent_fork();
        let (user, date) = last_logged_user();
        if find_in_path(LAST).is_some() && user.is_some() {
            let date = date.expect("a login we could not date");
            // The four fields of `EEE MMM dd HH:mm`.
            assert_eq!(date.split(' ').count(), 4, "{date}");
        }
    }

    /// The device numbers of this machine, and what `ps` calls them.
    #[test]
    fn it_names_the_terminal_of_a_process() {
        assert_eq!(terminal(1025), Some("tty1".to_string()));
        assert_eq!(terminal(1088), Some("ttyS0".to_string()));
        assert_eq!(terminal(34816), Some("pts/0".to_string()));
        assert_eq!(terminal(34817), Some("pts/1".to_string()));
        // The first pseudo terminal of the next major, which continues the numbering.
        assert_eq!(terminal(137 << 8), Some("pts/256".to_string()));
        // A process attached to no terminal, which most of them are.
        assert_eq!(terminal(0), None);
        // A class of device we would not know how to name.
        assert_eq!(terminal(0x0501), None);
    }

    #[test]
    fn it_reports_a_process_start_in_local_time() {
        let started = process_started(1_754_863_789).expect("a start we cannot format");
        assert_eq!(started.len(), "2026-08-11 00:09".len(), "{started}");
        let utc = Timestamp::from_second(1_754_863_789)
            .unwrap()
            .to_zoned(TimeZone::UTC)
            .strftime("%Y-%m-%d %H:%M")
            .to_string();
        if Zoned::now().offset().seconds() == 0 {
            assert_eq!(started, utc);
        } else {
            assert_ne!(
                started, utc,
                "the start of a process is reported in UTC, not in local time"
            );
        }
    }

    #[test]
    fn it_reports_no_start_it_cannot_format() {
        // A timestamp no calendar can hold.
        assert_eq!(process_started(u64::MAX), None);
    }

    #[test]
    fn it_computes_the_share_of_memory_of_a_process() {
        // A tenth of the memory of the machine.
        assert_eq!(
            memory_share(3_200_000_000, 32_000_000_000),
            Some("10.0".to_string())
        );
        assert_eq!(
            memory_share(96_000_000, 32_000_000_000),
            Some("0.3".to_string())
        );
        assert_eq!(memory_share(0, 32_000_000_000), Some("0.0".to_string()));
        // Truncated as `ps` does, where rounding would say 0.1 and double the share.
        assert_eq!(
            memory_share(17_348 * 1024, 32_791_448 * 1024),
            Some("0.0".to_string())
        );
        // A machine whose memory we do not know makes every share meaningless.
        assert_eq!(memory_share(96_000_000, 0), None);
    }

    /// The list is FusionInventory's, in `Tools/Generic.pm`, so that both agents stay silent
    /// about the same fields.
    #[test]
    fn it_drops_the_placeholders_the_firmware_writes_into_dmi() {
        for placeholder in [
            "N/A",
            "None",
            "Unknown",
            "Not Specified",
            "Not Present",
            "Not Available",
            "<BAD INDEX>",
            "<OUT OF SPEC>",
            "<OUT OF SPEC><OUT OF SPEC>",
            "To Be Filled By O.E.M.",
        ] {
            assert_eq!(dmi_value(placeholder), None, "{placeholder}");
            // The case and the spacing of a placeholder vary, as they do for FusionInventory.
            assert_eq!(
                dmi_value(&placeholder.to_lowercase()),
                None,
                "{placeholder}"
            );
            assert_eq!(
                dmi_value(&placeholder.to_uppercase()),
                None,
                "{placeholder}"
            );
            assert_eq!(
                dmi_value(&placeholder.replace(' ', "")),
                None,
                "{placeholder}"
            );
            assert_eq!(
                dmi_value(&format!("  {placeholder}\n")),
                None,
                "{placeholder}"
            );
        }
        // Nothing at all is not a value either.
        assert_eq!(dmi_value(""), None);
        assert_eq!(dmi_value(" \n"), None);
    }

    /// Only a whole value is a placeholder, and only the ones FusionInventory knows: reporting
    /// what it reports matters more here than dropping every stand-in there is.
    #[test]
    fn it_keeps_the_dmi_values_that_only_look_like_placeholders() {
        for value in [
            // Real values that contain a placeholder without being one.
            "Unknown Manufacturer",
            "None of the above",
            "QEMU",
            "Standard PC (Q35 + ICH9, 2009)",
            // Placeholders FusionInventory reports, so we report them too.
            "Default string",
            "System Product Name",
            // A family name that means the firmware knows no better name for the processor,
            // which both agents report.
            "Other",
        ] {
            assert_eq!(dmi_value(value), Some(value.to_string()));
        }
        // The value is trimmed, as `sysinfo` and the DMI files hand it over with its newline.
        assert_eq!(dmi_value(" QEMU\n"), Some("QEMU".to_string()));
    }

    /// The seventeen names the zone database links to `UTC`, which is the list
    /// `DateTime::TimeZone` resolves them through for FusionInventory.
    #[test]
    fn it_names_a_utc_machine_utc() {
        for alias in [
            "Etc/UTC",
            "Etc/GMT",
            "Etc/GMT+0",
            "Etc/GMT-0",
            "Etc/GMT0",
            "Etc/Greenwich",
            "Etc/UCT",
            "Etc/Universal",
            "Etc/Zulu",
            "GMT",
            "GMT+0",
            "GMT-0",
            "GMT0",
            "Greenwich",
            "UCT",
            "Universal",
            "Zulu",
        ] {
            assert_eq!(zone_name(alias), "UTC", "{alias}");
        }
        // The name they all link to, which needs no resolving.
        assert_eq!(zone_name("UTC"), "UTC");
    }

    #[test]
    fn it_leaves_every_other_zone_alone() {
        // A zone of its own is reported as it is named.
        for zone in ["Europe/Paris", "America/New_York", "Australia/Eucla"] {
            assert_eq!(zone_name(zone), zone);
        }
        // A link that does not lead to UTC keeps the name the machine is set to, where
        // FusionInventory would report the zone it renames to. A known difference.
        assert_eq!(zone_name("Asia/Calcutta"), "Asia/Calcutta");
        assert_eq!(zone_name("US/Eastern"), "US/Eastern");
        // A zone that only looks like one of the aliases.
        assert_eq!(zone_name("Etc/GMT+1"), "Etc/GMT+1");
        assert_eq!(zone_name("Etc/GMT-14"), "Etc/GMT-14");
        assert_eq!(zone_name("America/Greenwich"), "America/Greenwich");
        // The comparison is on the whole name, and the names are case sensitive.
        assert_eq!(zone_name("utc"), "utc");
        assert_eq!(zone_name(""), "");
    }

    #[test]
    fn it_names_a_process_by_what_it_runs() {
        assert_eq!(
            command_line("/usr/lib/systemd/systemd --system", "systemd"),
            "/usr/lib/systemd/systemd --system"
        );
        // A kernel thread, which has no command line and is named between brackets.
        assert_eq!(command_line("", "kworker/u51:0"), "[kworker/u51:0]");
        assert_eq!(command_line("   ", "kthreadd"), "[kthreadd]");
        // The command line is trimmed, as a process can be started with trailing spaces.
        assert_eq!(command_line("  sleep 60  ", "sleep"), "sleep 60");
        // A process with neither is still reported, under a name of nothing at all, which is
        // what `ps` shows for it as well.
        assert_eq!(command_line("", ""), "[]");
    }

    /// A command line an inventory cannot carry, which any user of the machine can start a
    /// process with.
    #[test]
    fn it_reports_a_command_line_an_xml_document_can_hold() {
        // A newline becomes a space, as `ps` prints one process per line.
        assert_eq!(
            command_line("sh -c 'a\nb'", "sh"),
            "sh -c 'a b'".to_string()
        );
        assert_eq!(command_line("a\r\nb", "x"), "a  b");
        // The control characters an XML document is not allowed to hold at all.
        assert_eq!(command_line("a\x01b\x1fc\x7fd", "x"), "a?b?c?d");
        assert_eq!(command_line("a\tb", "x"), "a?b");
        // The name of a kernel thread goes through the same, brackets and all.
        assert_eq!(command_line("", "kworker\x01"), "[kworker?]");
        // What a process really runs is kept, where `ps` under `LANG=C` would lose it.
        assert_eq!(command_line("echo café 中", "echo"), "echo café 中");
        assert_eq!(command_line("./naïve --flag", "naïve"), "./naïve --flag");
    }

    /// The point of the whole thing: whatever a process holds, the inventory stays a document
    /// the server can read.
    #[test]
    fn it_writes_a_command_line_an_xml_parser_accepts() {
        let hostile = "evil\u{1}\u{2}\u{1f}\u{7f}\n\t<&>\"'";
        let process = Process {
            cmd: command_line(hostile, "evil"),
            cpu_usage: "0.0".to_string(),
            mem: None,
            pid: "1".to_string(),
            started: None,
            tty: None,
            user: None,
            virtual_memory: 0,
        };
        let mut out = String::new();
        let ser = Serializer::with_root(&mut out, Some("PROCESSES")).unwrap();
        process.serialize(ser).unwrap();
        // The markup characters are the serializer's business, and it escapes them.
        assert!(out.contains("&lt;&amp;&gt;"), "{out}");
        // Ours is that nothing forbidden is left for it to write.
        assert!(
            !out.chars().any(|c| c.is_control() && c != '\n'),
            "a control character reached the document"
        );
        // And the result is a document, which is what the server needs it to be. Reading it
        // back is the only check that means anything here.
        let mut reader = quick_xml::Reader::from_str(&out);
        loop {
            match reader.read_event() {
                Ok(quick_xml::events::Event::Eof) => break,
                Ok(_) => (),
                Err(e) => panic!("the document does not parse: {e}\n{out}"),
            }
        }
    }

    /// Every process of this machine is named, and only the kernel threads are bracketed.
    #[test]
    #[cfg(target_os = "linux")]
    fn it_names_the_processes_of_this_machine_as_ps_names_them() {
        let mut sys = System::new();
        sys.refresh_processes_specifics(
            ProcessesToUpdate::All,
            true,
            ProcessRefreshKind::nothing().with_cmd(UpdateKind::Always),
        );
        let mut bracketed = 0;
        for p in sys.processes().values() {
            let cmd = command_line(
                &p.cmd()
                    .iter()
                    .map(|s| s.to_string_lossy())
                    .collect::<Vec<_>>()
                    .join(" "),
                &p.name().to_string_lossy(),
            );
            assert!(!cmd.is_empty(), "a process was reported without a name");
            if cmd.starts_with('[') {
                bracketed += 1;
                // Only a process without a command line is bracketed, which on Linux is a
                // kernel thread.
                assert!(
                    cmd.ends_with(']'),
                    "'{cmd}' opens a bracket it does not close"
                );
                assert!(p.cmd().is_empty(), "'{cmd}' has a command line of its own");
            }
        }
        // A process is bracketed exactly when it has no command line, which is the whole rule.
        // Asserted as a count rather than as "there is at least one", as a container sees no
        // kernel thread at all: they live in the PID namespace of the machine.
        let without_command_line = sys
            .processes()
            .values()
            .filter(|p| p.cmd().is_empty())
            .count();
        assert_eq!(
            bracketed, without_command_line,
            "a process without a command line was left unnamed"
        );
    }

    /// The arithmetic of `pr_pcpu` in `procps`, which truncates at every step.
    #[test]
    fn it_computes_the_share_of_a_processor_of_a_process() {
        // A processor held for the whole life of the process, which `ps` prints without a
        // decimal as it is past 99.9%, and half of one.
        assert_eq!(cpu_share(60_000, 60), "100");
        assert_eq!(cpu_share(30_000, 60), "50.0");
        // A second of computing, then an hour of doing nothing, which `ps` reports low.
        assert_eq!(cpu_share(1_000, 3_600), "0.0");
        // The tenth is truncated, not rounded: 0.19% is reported as 0.1%.
        assert_eq!(cpu_share(19, 10), "0.1");
        assert_eq!(cpu_share(1_990, 100), "1.9");
        // A process that has used no CPU at all, which most of them have.
        assert_eq!(cpu_share(0, 3_600), "0.0");
        // Two processors held for the whole life of the process. `ps` drops the decimal past
        // 99.9%, and reports "200" rather than "200.0".
        assert_eq!(cpu_share(120_000, 60), "200");
        // The last value that keeps its decimal, and the first that loses it.
        assert_eq!(cpu_share(9_990, 10), "99.9");
        assert_eq!(cpu_share(10_000, 10), "100");
        // A process started within the second, which has no time to divide by.
        assert_eq!(cpu_share(0, 0), "0.0");
        assert_eq!(cpu_share(10, 0), "0.0");
    }

    /// Every process of this machine, against the shape the server parses as a float.
    #[test]
    fn it_reports_the_share_of_a_processor_of_the_processes_of_this_machine() {
        let mut sys = System::new();
        sys.refresh_processes_specifics(
            ProcessesToUpdate::All,
            true,
            ProcessRefreshKind::nothing().with_cpu(),
        );
        let mut used_any = false;
        for p in sys.processes().values() {
            let share = cpu_share(p.accumulated_cpu_time(), p.run_time());
            let value: f32 = share
                .parse()
                .unwrap_or_else(|e| panic!("'{share}' is not a number the server can read: {e}"));
            assert!(value >= 0.0, "{share}");
            used_any |= value > 0.0;
        }
        // A share is reported for exactly the processes that have one to report: those that
        // have held a processor for a tenth of a percent of their life. This is the check that
        // the instantaneous usage `sysinfo` offers, zero for every process on the single
        // refresh a run does, is not what is being read.
        //
        // Both sides are false on a machine with nothing to measure, a fresh container running
        // a handful of short-lived processes being one, so the two are compared rather than
        // `used_any` asserted on its own.
        let measurable = sys
            .processes()
            .values()
            .filter(|p| p.run_time() > 0)
            .any(|p| p.accumulated_cpu_time() / p.run_time() > 0);
        assert_eq!(
            used_any, measurable,
            "the share of a processor is not read from the time the processes have used"
        );
    }

    #[test]
    fn it_converts_bytes_to_megabytes() {
        assert_eq!(megabytes(33_570_320_384), Some(32_015));
        assert_eq!(megabytes(1024 * 1024), Some(1));
        // Less than a megabyte, but the platform did answer.
        assert_eq!(megabytes(1), Some(0));
        // No swap, or a platform that does not tell us.
        assert_eq!(megabytes(0), None);
    }

    #[test]
    fn it_formats_the_timezone_offset() {
        // The zone depends on the machine, its shape does not.
        let timezone = Timezone::new().expect("no local timezone");
        assert!(!timezone.name.is_empty());
        let offset = timezone.offset;
        assert!(
            offset.len() == 5
                && matches!(offset.as_bytes()[0], b'+' | b'-')
                && offset[1..].bytes().all(|b| b.is_ascii_digit()),
            "unexpected offset '{offset}'"
        );
    }
}
