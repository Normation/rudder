// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

//! What every section needs: running a command, looking one up, naming the machine, and the
//! handful of conversions the values we read go through.

use std::{env, path::PathBuf, process::Command, str, sync::mpsc, thread, time::Duration};

use anyhow::{Context, Result, bail};
use tracing::{Span, debug, warn};

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
/// This is FusionInventory's own list, taken from the regexp `getDmidecodeInfos`.
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

/// The name of the machine, without its domain.
pub(crate) fn hostname() -> Result<String> {
    nix::unistd::gethostname()
        .context("Reading the hostname")?
        .into_string()
        .map_err(|_| anyhow::anyhow!("Non-UTF8 hostname"))
}

/// The fully qualified domain name of the local machine.
///
/// `hostname --fqdn` resolves the hostname to get the domain part, and the hostname alone is
/// reported when it cannot be resolved. The server needs this to identify the node, and only
/// rejects it when it is empty or a loopback name.
pub(crate) fn fqdn() -> Result<String> {
    let hostname = hostname()?;
    Ok(cmd("hostname", &["--fqdn"]).unwrap_or_else(|e| {
        warn!("Could not resolve the fully qualified name, reporting '{hostname}': {e:#}");
        hostname
    }))
}

/// Runs a command and returns its output.
///
/// A command that is not installed, one that cannot be run and one that runs and fails are all
/// the same outcome here: an error naming the command, for the caller to report or to fall back
/// from as its section needs. Falling back is the caller's business, as a value most runs never
/// use is one most runs should not have built.
pub(crate) fn cmd(program: &str, args: &[&str]) -> Result<String> {
    let output = Command::new(program).args(args).output();
    let arguments = || args.join(" ");
    let value = match &output {
        Ok(out) if out.status.success() => str::from_utf8(&out.stdout)?.to_owned(),
        Ok(out) => bail!(
            "Command '{program} {}' failed: {}",
            arguments(),
            str::from_utf8(&out.stderr)?
        ),
        Err(e) => bail!("Could not run '{program} {}': {e}", arguments()),
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

/// Converts a number of bytes to the megabytes the server expects, dropping a zero value as
/// the platform not having answered.
pub(crate) fn megabytes(bytes: u64) -> Option<u64> {
    (bytes > 0).then_some(bytes / 1024 / 1024)
}

/// Runs a function on another thread, and gives up on it after the given delay.
///
/// For the values the kernel can hold us on for as long as it likes, with no way to interrupt it:
/// reading the size of a filesystem on an unresponsive network mount, in particular. We therefore
/// abandon the thread instead of waiting for it, and leave it to the end of the process to clean
/// up. That is only acceptable because a run inventories once and exits.
pub(crate) fn with_timeout<T: Send + 'static>(
    timeout: Duration,
    f: impl FnOnce() -> T + Send + 'static,
) -> Option<T> {
    let (tx, rx) = mpsc::channel();
    // A new thread does not inherit the current span, so we carry it over to keep what the
    // function logs attached to the section being built.
    let span = Span::current();
    thread::spawn(move || {
        let _entered = span.enter();
        // Once we have given up, the receiver is gone and there is nobody left to report to.
        let _ = tx.send(f());
    });
    rx.recv_timeout(timeout).ok()
}

#[cfg(test)]
mod tests {
    use pretty_assertions::assert_eq;

    use super::*;

    #[test]
    fn it_returns_the_output_of_a_command() {
        assert_eq!(cmd("echo", &["  hello  "]).unwrap(), "hello");
    }

    /// A command that is not installed and one that fails are both errors, and both name the
    /// command, as that is all a caller has to decide what to report.
    #[test]
    fn it_fails_on_a_command_it_cannot_run() {
        let err = cmd("this-command-does-not-exist", &[]).unwrap_err();
        assert!(
            err.to_string().contains("this-command-does-not-exist"),
            "the error does not name the command: {err}"
        );
        let err = cmd("false", &[]).unwrap_err();
        assert!(err.to_string().contains("false"), "{err}");
    }

    #[test]
    fn it_finds_executables_in_path() {
        assert_eq!(find_in_path("this-program-does-not-exist"), None);
        // Present on every platform we support.
        assert!(find_in_path("sh").is_some());
    }

    #[test]
    fn it_reads_the_fqdn_of_this_machine() {
        let fqdn = fqdn().expect("no fully qualified name");
        assert!(!fqdn.is_empty());
        // A name, not a line of output.
        assert!(!fqdn.contains(char::is_whitespace), "{fqdn}");
    }

    /// The values are the ones `getDmidecodeInfos` skips in `Tools/Generic.pm`, written as
    /// `dmidecode` prints them, so that both agents stay silent about the same fields.
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
    fn it_returns_the_value_of_a_function_that_answers_in_time() {
        assert_eq!(with_timeout(Duration::from_secs(30), || 42), Some(42));
    }

    #[test]
    fn it_gives_up_on_a_function_that_blocks() {
        let start = std::time::Instant::now();
        let timeout = Duration::from_millis(200);
        // Blocks far longer than we are willing to wait, like an unresponsive mount.
        let blocked = with_timeout(timeout, || {
            thread::sleep(Duration::from_secs(30));
            42
        });
        assert_eq!(blocked, None);
        // We came back on time instead of waiting for it.
        assert!(start.elapsed() < Duration::from_secs(5));
    }
}
