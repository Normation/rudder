// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

//! Pending updates on a SUSE system.
//!
//! `zypper` is asked for XML rather than for the table it prints by default. The table wraps its
//! columns to the width of the terminal, pads them with the spaces a package name may itself
//! hold, and is interleaved with the warnings about expired repositories that any real machine
//! produces. The XML holds the same values, one attribute each, and says which of the lines
//! around it are not data.

use anyhow::{Context, Result};
use quick_xml::{
    Reader, XmlVersion,
    events::{BytesStart, Event, attributes::Attribute},
};
use tracing::{debug, warn};

use crate::{
    cmd, find_in_path,
    packages::{Update, UpdateManager, kind, severity},
};

const ZYPPER: &str = "zypper";

pub struct Zypper;

impl UpdateManager for Zypper {
    fn is_available() -> bool {
        find_in_path(ZYPPER).is_some()
    }

    fn updates() -> Result<Vec<Update>> {
        // `--non-interactive` so that a repository asking to import a key cannot stop the run,
        // and `--xmlout` before the command, as zypper wants its own options first.
        //
        // No fallback value: unlike `yum check-update`, `zypper list-updates` exits zero
        // whether or not it found anything, so a failure is a real one. Swallowing it would
        // report a machine as having no update pending, which the server reads as fully
        // patched, so it is said out loud instead.
        match cmd(
            ZYPPER,
            &["--xmlout", "--non-interactive", "list-updates"],
            None,
        ) {
            Ok(out) => Self::parse_updates(&out),
            Err(e) => {
                warn!(
                    "Could not list the pending updates with zypper: {e:#}. None will be \
                     reported, which is not the same as there being none."
                );
                Ok(vec![])
            }
        }
    }
}

impl Zypper {
    /// Reads the `<update>` elements of what `zypper --xmlout list-updates` printed.
    ///
    /// Only the packages are kept. `zypper` also calls a patch an update, which is an advisory
    /// grouping packages rather than a package to install, and would be counted twice.
    fn parse_updates(xml: &str) -> Result<Vec<Update>> {
        let mut reader = Reader::from_str(xml);
        let mut res = vec![];
        let mut current: Option<Update> = None;
        let mut in_description = false;
        loop {
            let event = reader
                .read_event()
                .context("Reading the XML zypper printed")?;
            match event {
                Event::Eof => break,
                // An element with no children is `Empty` and has no `End` of its own, so it is
                // finished here rather than waited for.
                Event::Start(ref e) | Event::Empty(ref e) => {
                    let self_closing = matches!(event, Event::Empty(_));
                    match e.name().as_ref() {
                        b"update" => {
                            current = Self::update_from(e);
                            if self_closing && let Some(update) = current.take() {
                                res.push(update);
                            }
                        }
                        // The repository the update comes from, named by its alias.
                        b"source" => {
                            if let Some(update) = current.as_mut() {
                                for attribute in e.attributes().flatten() {
                                    if attribute.key.as_ref() == b"alias" {
                                        update.source = Some(text_of(&attribute));
                                    }
                                }
                            }
                        }
                        b"description" => in_description = !self_closing,
                        _ => (),
                    }
                }
                Event::Text(ref e) => {
                    if in_description && let Some(update) = current.as_mut() {
                        let text = e.decode().unwrap_or_default().trim().to_string();
                        if !text.is_empty() {
                            update.description = Some(text);
                        }
                    }
                }
                Event::End(ref e) => match e.name().as_ref() {
                    b"description" => in_description = false,
                    b"update" => {
                        if let Some(update) = current.take() {
                            res.push(update);
                        }
                    }
                    _ => (),
                },
                _ => (),
            }
        }
        debug!("Read {} updates from the XML zypper printed", res.len());
        Ok(res)
    }

    /// An `<update>` element, or nothing when it is not a package to install.
    fn update_from(e: &BytesStart<'_>) -> Option<Update> {
        let mut name = String::new();
        let mut version = String::new();
        let mut arch = None;
        let mut update_kind = None;
        let mut update_severity = None;
        let mut is_patch = false;
        for attribute in e.attributes().flatten() {
            let value = text_of(&attribute);
            match attribute.key.as_ref() {
                b"name" => name = value,
                // The version to install, where `edition-old` is the one in place, which the
                // section does not report.
                b"edition" => version = value,
                b"arch" => arch = Some(value),
                b"kind" => is_patch = value == "patch",
                // Only a patch carries these two.
                b"category" => update_kind = Some(kind(&value)),
                b"severity" => update_severity = severity(&value),
                _ => (),
            }
        }
        if is_patch || name.is_empty() {
            return None;
        }
        Some(Update {
            arch,
            from: ZYPPER.to_string(),
            kind: update_kind,
            name,
            source: None,
            version,
            description: None,
            severity: update_severity,
            ids: None,
        })
    }
}

/// The text of an attribute, which zypper writes as UTF-8 and may escape.
fn text_of(attribute: &Attribute<'_>) -> String {
    attribute
        .normalized_value(XmlVersion::Implicit1_0)
        .map(|v| v.to_string())
        .unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use pretty_assertions::assert_eq;

    use super::*;

    /// Real output of `zypper --xmlout --non-interactive list-updates`, from an openSUSE
    /// Tumbleweed machine, with the second entry added from the same shape.
    const XML: &str = r#"<?xml version='1.0'?>
<stream>
<message type="info">Loading repository data...</message>
<update-status version="0.6">
<update-list>
<update kind="package" name="openSUSE-build-key" edition="1.0-69.1" arch="x86_64" edition-old="1.0-68.1"><summary>The public gpg keys for rpm package signature verification</summary><description>This package contains the gpg keys that are used to sign the
openSUSE rpm packages.</description><license/><source url="http://download.opensuse.org/update/tumbleweed/" alias="repo-update"/></update>
<update kind="package" name="libopenssl3" edition="3.2.4-1.1" arch="x86_64" edition-old="3.2.3-1.1"><summary>Secure Sockets and Transport Layer Security</summary><description>The OpenSSL Project is a管 toolkit.</description><license/><source url="http://download.opensuse.org/tumbleweed/" alias="repo-oss"/></update>
</update-list>
</update-status>
</stream>"#;

    #[test]
    fn it_parses_updates() {
        let parsed = Zypper::parse_updates(XML).unwrap();
        assert_eq!(parsed.len(), 2);

        let key = &parsed[0];
        assert_eq!(key.name, "openSUSE-build-key");
        // The version to install, not the one in place.
        assert_eq!(key.version, "1.0-69.1");
        assert_eq!(key.arch, Some("x86_64".to_string()));
        assert_eq!(key.source, Some("repo-update".to_string()));
        assert_eq!(key.from, "zypper");
        assert!(
            key.description
                .as_deref()
                .is_some_and(|d| d.starts_with("This package contains the gpg keys"))
        );

        assert_eq!(parsed[1].name, "libopenssl3");
        assert_eq!(parsed[1].source, Some("repo-oss".to_string()));
    }

    /// A patch is an advisory grouping packages, not a package to install, and the packages it
    /// names are listed on their own. Counting both would report every update twice.
    #[test]
    fn it_leaves_the_patches_out() {
        let xml = r#"<stream><update-list>
<update kind="patch" name="openSUSE-2024-81" edition="1" arch="noarch" category="security" severity="important"><summary>Security update</summary></update>
<update kind="package" name="bash" edition="5.2-1.1" arch="x86_64"><source alias="repo-oss"/></update>
</update-list></stream>"#;
        let parsed = Zypper::parse_updates(xml).unwrap();
        assert_eq!(parsed.len(), 1);
        assert_eq!(parsed[0].name, "bash");
    }

    #[test]
    fn it_parses_no_update_from_an_empty_list() {
        let xml = r#"<stream><update-status version="0.6"><update-list></update-list></update-status></stream>"#;
        assert!(Zypper::parse_updates(xml).unwrap().is_empty());
        assert!(Zypper::parse_updates("").unwrap().is_empty());
    }

    /// The warnings a real machine prints, which are elements of their own and not updates.
    #[test]
    fn it_ignores_what_is_not_an_update() {
        let xml = r#"<stream>
<message type="warning">Repository 'Update repository' metadata expired since 2026-07-10.</message>
<update-list><update kind="package" name="sed" edition="4.9-1.1" arch="x86_64"/></update-list>
</stream>"#;
        let parsed = Zypper::parse_updates(xml).unwrap();
        assert_eq!(parsed.len(), 1);
        assert_eq!(parsed[0].name, "sed");
    }
}

#[cfg(test)]
mod real_data {
    /// Runs the parser over the unedited XML of a real openSUSE machine.
    #[test]
    #[ignore = "needs the captured output of a real machine"]
    fn it_parses_the_updates_of_a_real_machine() {
        let xml = std::fs::read_to_string("/tmp/zyp.xml").unwrap_or_default();
        let parsed = super::Zypper::parse_updates(&xml).unwrap();
        println!("{} updates parsed", parsed.len());
        for u in &parsed {
            println!(
                "  {} {} {:?} from {:?}",
                u.name, u.version, u.arch, u.source
            );
            assert!(!u.name.is_empty() && !u.version.is_empty());
        }
        assert!(!parsed.is_empty(), "no update parsed from real output");
    }
}
