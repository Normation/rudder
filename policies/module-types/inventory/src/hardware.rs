// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

//! The `HARDWARE` section: what identifies the machine, how much memory it has, and who last
//! logged into it.

use jiff::fmt::strtime::BrokenDownTime;
use nix::sys::utsname::uname;
use serde::Serialize;
use sysinfo::System;
use tracing::{debug, warn};

use crate::{
    dmi::Dmi,
    util::{cmd, find_in_path, megabytes},
};

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
}

impl Hardware {
    /// The memory and the swap come from the same `System` the other sections are built from,
    /// which has to have had its memory refreshed. The short hostname and the SMBIOS tables are
    /// read once for the whole inventory and handed over.
    pub fn inventory(sys: &System, hostname: String, dmi: Option<&Dmi>) -> Self {
        let (last_logged_user, date_last_logged_user) = last_logged_user();
        Self {
            date_last_logged_user,
            last_logged_user,
            memory: megabytes(sys.total_memory()),
            name: Some(hostname),
            os_comments: os_comments(),
            swap: megabytes(sys.total_swap()),
            uuid: machine_uuid(dmi),
        }
    }
}

/// The kernel build string, which the server keeps as the node description.
fn os_comments() -> Option<String> {
    match uname() {
        Ok(uts) => Some(uts.version().to_string_lossy().into_owned()),
        Err(e) => {
            warn!("Could not read the kernel identification, reporting no description: {e}");
            None
        }
    }
}

/// The DMI identifier of the machine, which the server keeps as its motherboard UUID.
///
/// This is how a virtual machine is told apart from a clone of itself. It is only readable by
/// root, and absent on the machines without SMBIOS tables, in which case we report nothing.
fn machine_uuid(dmi: Option<&Dmi>) -> Option<String> {
    let uuid = dmi.and_then(Dmi::system_uuid);
    if uuid.is_none() {
        // Expected when we do not run as root, which is why this is not a warning.
        debug!("Could not read the DMI identifier of the machine");
    }
    uuid
}

/// The command that lists the logins of the machine, the most recent first.
const LAST: &str = "last";

/// The format `last` prints a login date in, which is also the `EEE MMM dd HH:mm` the server
/// parses it back from.
const LAST_DATE: &str = "%a %b %e %H:%M";

/// The last user to have logged in, and when.
fn last_logged_user() -> (Option<String>, Option<String>) {
    if find_in_path(LAST).is_none() {
        debug!("No '{LAST}', reporting no last logged user");
        return (None, None);
    }
    match cmd(LAST, &[]) {
        Ok(output) => parse_last_logged_user(&output),
        Err(e) => {
            warn!("Could not run '{LAST}', reporting no last logged user: {e:#}");
            (None, None)
        }
    }
}

/// Reads the user and the date of the most recent login `last` prints.
///
/// It prints the most recent first, and like FusionInventory we keep the first line that is not
/// the machine starting or stopping.
///
/// The columns between the user and the date vary — a terminal, a host, both or neither — so the
/// date is found by handing every four consecutive fields to `jiff` and keeping the first that
/// parses as [`LAST_DATE`]. It is the parser that decides what a date is, rather than us: looking
/// for a day of the week and taking the three fields after it on trust would report
/// `Thu Oct 99 25:99` as a date. `jiff` also refuses anything left over, which is what makes four
/// fields the right window.
///
/// The fields are rejoined with single spaces, `last` padding a single digit day with two, which
/// gives the `EEE MMM dd HH:mm` the server parses. FusionInventory normalizes it the same way.
///
/// A login we cannot date is still a login, and is reported without one.
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
            .windows(4)
            .map(|window| window.join(" "))
            .find(|date| BrokenDownTime::parse(LAST_DATE, date).is_ok());
        if date.is_none() {
            warn!(
                "Could not read a date from the last login of '{user}', reporting the login \
                 without one: {line:?}"
            );
        }
        return (Some(user.to_string()), date);
    }
    (None, None)
}

#[cfg(test)]
mod tests {
    use pretty_assertions::assert_eq;
    use quick_xml::se::Serializer;

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
                "</HARDWARE>",
            )
        );
    }

    #[test]
    fn it_reads_the_last_login_of_this_machine() {
        let (user, date) = last_logged_user();
        if find_in_path(LAST).is_some() && user.is_some() {
            let date = date.expect("a login we could not date");
            // The four fields of `EEE MMM dd HH:mm`.
            assert_eq!(date.split(' ').count(), 4, "{date}");
        }
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

    /// Only a date is reported as one, which is what handing the fields to a parser buys: the
    /// first three lines below start on a day of the week, so taking the three fields after one
    /// on trust reported each of them as a date the server cannot parse.
    #[test]
    fn it_reports_no_date_for_what_only_looks_like_one() {
        for line in [
            // A day and a time no calendar and no clock have.
            "root     pts/0        Thu Oct 99 18:09",
            "root     pts/0        Thu Oct 23 25:99",
            // A month that is not one.
            "root     pts/0        Thu Foo 23 18:09",
            // The rest were already refused, and stay refused: a weekday that is not one, a host
            // whose name merely starts on a weekday abbreviation, and fewer fields than a date.
            "root     pts/0        Thuesday Oct 23 18:09",
            "root     pts/0        thu.example.com 10 20 30",
            "root     pts/0        Thu Oct 23",
        ] {
            assert_eq!(
                parse_last_logged_user(line),
                (Some("root".to_string()), None),
                "{line}"
            );
        }
    }

    /// The date is looked for wherever it sits, as the columns before it are the ones `last`
    /// happens to have printed.
    #[test]
    fn it_finds_the_date_after_any_number_of_columns() {
        for line in [
            "root Thu Oct 23 18:09",
            "root pts/0 Thu Oct 23 18:09",
            "root pts/0 192.168.122.1 Thu Oct 23 18:09",
            "root pts/0 192.168.122.1 Thu Oct 23 18:09 - 19:02 (00:53)",
            // A host that looks like the start of a date, before the date itself.
            "root pts/0 thu.example.com Thu Oct 23 18:09",
        ] {
            assert_eq!(
                parse_last_logged_user(line),
                (
                    Some("root".to_string()),
                    Some("Thu Oct 23 18:09".to_string())
                ),
                "{line}"
            );
        }
    }
}
