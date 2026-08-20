// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

//! Machine identity, as the `BIOS` section.
//!
//! This is the one piece of hardware information we do want. It is not a hardware catalogue:
//! it is what identifies the machine the node runs on, and the server keeps the manufacturer
//! and the serial number of it in a record of its own. In a virtualized or cloud context it is
//! also what names the hypervisor or the instance kind.
//!
//! Every value is read from the SMBIOS tables of the machine, where FusionInventory runs
//! `dmidecode` over the same ones.

use serde::Serialize;
use tracing::{debug, instrument};

use crate::dmi::Dmi;

/// Fields are declared in the order FusionInventory serializes them, to keep both outputs
/// easy to compare.
///
/// `ASSETTAG`, `MMODEL`, `MSN` and `SKUNUMBER` are not produced, as nothing on the server
/// reads them.
#[derive(Debug, PartialEq, Serialize)]
#[serde(rename_all = "UPPERCASE")]
pub struct Bios {
    /// The release date of the BIOS, which the server expects as `MM/DD/YYYY`, the format DMI
    /// uses.
    #[serde(rename = "BDATE", skip_serializing_if = "Option::is_none")]
    date: Option<String>,
    #[serde(rename = "BMANUFACTURER", skip_serializing_if = "Option::is_none")]
    manufacturer: Option<String>,
    #[serde(rename = "BVERSION", skip_serializing_if = "Option::is_none")]
    version: Option<String>,
    /// The server falls back to this one when the system manufacturer is missing, which
    /// happens on the virtual machines that describe no motherboard.
    #[serde(rename = "MMANUFACTURER", skip_serializing_if = "Option::is_none")]
    board_manufacturer: Option<String>,
    /// Becomes the manufacturer of the machine.
    #[serde(rename = "SMANUFACTURER", skip_serializing_if = "Option::is_none")]
    system_manufacturer: Option<String>,
    /// The model of the machine. The server drops the whole entry without it, and with it the
    /// manufacturer and the serial number, so we do not report a `BIOS` section at all when we
    /// cannot name the model.
    #[serde(rename = "SMODEL")]
    system_model: String,
    /// Becomes the serial number of the machine.
    #[serde(rename = "SSN", skip_serializing_if = "Option::is_none")]
    system_serial_number: Option<String>,
}

impl Bios {
    /// The identity of the machine, or nothing when we could read no SMBIOS table or when they
    /// do not name the model of the machine.
    #[instrument(level = "debug", name = "bios", skip(dmi))]
    pub fn inventory(dmi: Option<&Dmi>) -> Option<Self> {
        let dmi = dmi?;
        let system_model = dmi.system_model()?;
        let bios = Self {
            date: dmi.bios_date(),
            manufacturer: dmi.bios_vendor(),
            version: dmi.bios_version(),
            board_manufacturer: dmi.board_manufacturer(),
            system_manufacturer: dmi.system_manufacturer(),
            system_model,
            system_serial_number: dmi.system_serial_number(),
        };
        debug!(
            "Machine is a '{}' from '{}'",
            bios.system_model,
            bios.system_manufacturer.as_deref().unwrap_or("unknown")
        );
        Some(bios)
    }
}

#[cfg(test)]
mod tests {
    use pretty_assertions::assert_eq;
    use quick_xml::se::Serializer;

    use super::*;
    use crate::dmi::value;

    /// The serialized shape has to stay the one FusionInventory produces, field for field.
    #[test]
    fn it_serializes_a_bios_like_fusion_inventory() {
        let bios = Bios {
            date: Some("02/02/2022".to_string()),
            manufacturer: Some("EDK II".to_string()),
            version: Some("1.16.3-2".to_string()),
            board_manufacturer: Some("QEMU".to_string()),
            system_manufacturer: Some("QEMU".to_string()),
            system_model: "Standard PC (Q35 + ICH9, 2009)".to_string(),
            system_serial_number: Some("42".to_string()),
        };
        let mut out = String::new();
        let mut ser = Serializer::with_root(&mut out, Some("BIOS")).unwrap();
        ser.indent(' ', 2);
        bios.serialize(ser).unwrap();
        assert_eq!(
            out,
            concat!(
                "<BIOS>\n",
                "  <BDATE>02/02/2022</BDATE>\n",
                "  <BMANUFACTURER>EDK II</BMANUFACTURER>\n",
                "  <BVERSION>1.16.3-2</BVERSION>\n",
                "  <MMANUFACTURER>QEMU</MMANUFACTURER>\n",
                "  <SMANUFACTURER>QEMU</SMANUFACTURER>\n",
                "  <SMODEL>Standard PC (Q35 + ICH9, 2009)</SMODEL>\n",
                "  <SSN>42</SSN>\n",
                "</BIOS>",
            )
        );
    }

    /// A machine whose tables we could not read, which is every run without root: the section
    /// holds nothing we could fill in, so there is no section.
    #[test]
    fn it_reports_no_bios_without_the_tables() {
        assert_eq!(Bios::inventory(None), None);
    }

    #[test]
    fn it_never_reports_an_unnamed_machine() {
        // Whatever this machine is, an entry without a model would be dropped by the server,
        // so we must either name it or report nothing.
        if let Some(bios) = Bios::inventory(Dmi::read().as_ref()) {
            assert!(!bios.system_model.is_empty());
        }
    }

    /// Reads the DMI of the machine we run on, which takes root: a run without it reports no
    /// section, and one with it has to report a machine.
    #[test]
    fn it_reads_the_dmi_of_this_machine() {
        let Some(bios) = Bios::inventory(Dmi::read().as_ref()) else {
            // Not root, or a machine without DMI, which we report nothing for.
            return;
        };
        assert!(!bios.system_model.is_empty());
        // Every value we report has one: an empty element would be worse than none.
        for value in [
            &bios.date,
            &bios.manufacturer,
            &bios.version,
            &bios.board_manufacturer,
            &bios.system_manufacturer,
            &bios.system_serial_number,
        ] {
            assert!(value.as_deref() != Some(""), "an empty value was reported");
        }
    }

    /// The values of this machine are real ones, not the stand-ins the firmware writes when it
    /// has nothing to say.
    #[test]
    fn it_reports_no_placeholder_of_this_machine() {
        let Some(bios) = Bios::inventory(Dmi::read().as_ref()) else {
            return;
        };
        for reported in [
            Some(&bios.system_model),
            bios.date.as_ref(),
            bios.manufacturer.as_ref(),
            bios.version.as_ref(),
            bios.board_manufacturer.as_ref(),
            bios.system_manufacturer.as_ref(),
            bios.system_serial_number.as_ref(),
        ]
        .into_iter()
        .flatten()
        {
            assert_eq!(
                value(reported),
                Some(reported.clone()),
                "'{reported}' is a placeholder, not a value"
            );
        }
    }
}
