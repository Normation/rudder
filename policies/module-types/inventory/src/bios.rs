// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

//! Machine identity, as the `BIOS` section.
//!
//! This is the one piece of hardware information we do want. It is not a hardware catalogue:
//! it is what identifies the machine the node runs on, and the server keeps the manufacturer
//! and the serial number of it in a record of its own. In a virtualized or cloud context it is
//! also what names the hypervisor or the instance kind.
//!
//! The system and motherboard values come from `sysinfo`, which reads them from DMI. The three
//! values of the BIOS itself are not exposed by `sysinfo`, and are read from the same DMI
//! directory, so they are only reported on Linux.

use serde::Serialize;
use sysinfo::{Motherboard, Product};
use tracing::{debug, instrument};

use crate::{MachineType, dmi_value};

/// Where the kernel exposes the DMI values.
#[cfg(target_os = "linux")]
const DMI_DIR: &str = "/sys/class/dmi/id";

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
    /// The identity of the machine, or nothing when DMI does not name its model.
    #[instrument(level = "debug", name = "bios")]
    pub fn inventory() -> Option<Self> {
        let system_model = value(Product::name())?;
        let board = Motherboard::new();
        let bios = Self {
            date: dmi("bios_date"),
            manufacturer: dmi("bios_vendor"),
            version: dmi("bios_version"),
            board_manufacturer: value(board.as_ref().and_then(Motherboard::vendor_name)),
            system_manufacturer: value(Product::vendor_name()),
            system_model,
            system_serial_number: value(Product::serial_number()),
        };
        debug!(
            "Machine is a '{}' from '{}'",
            bios.system_model,
            bios.system_manufacturer.as_deref().unwrap_or("unknown")
        );
        Some(bios)
    }

    /// The kind of machine the firmware describes, when there is nothing better to ask.
    ///
    /// These are the rules of `_getType` in FusionInventory's `Virtualization/Vmsystem.pm`, in
    /// the order it applies them, which is what decides between two that both match. It reads
    /// them from the same section, so a machine the two agents both fall back to here is
    /// reported the same by both.
    ///
    /// **A container is invisible to this.** DMI describes the machine underneath it, so a
    /// container is reported as whatever hosts it, and one on a physical host is reported as
    /// nothing at all. Only `systemd-detect-virt` answers that question, which is why this is
    /// the fallback and not the source.
    ///
    /// Nothing when no rule matches, which is a physical machine and a virtualization we do not
    /// know the marks of alike: the server assumes a physical machine, and asserting one on
    /// this evidence would be claiming more than DMI can say.
    pub(crate) fn machine_type(&self) -> Option<MachineType> {
        let system_manufacturer = self.system_manufacturer.as_deref().unwrap_or_default();
        let bios_manufacturer = self.manufacturer.as_deref().unwrap_or_default();
        let bios_version = self.version.as_deref().unwrap_or_default();
        let system_model = self.system_model.as_str();

        if system_manufacturer.contains("QEMU") {
            Some(MachineType::Qemu)
        } else if system_manufacturer.contains("Microsoft") && system_model.contains("Virtual") {
            Some(MachineType::HyperV)
        } else if system_manufacturer.contains("VMware") {
            Some(MachineType::VMware)
        } else if bios_manufacturer.contains("QEMU") || bios_manufacturer.contains("Bochs") {
            Some(MachineType::Qemu)
        } else if bios_manufacturer.contains("VirtualBox") || bios_manufacturer.contains("innotek")
        {
            Some(MachineType::VirtualBox)
        } else if bios_manufacturer.starts_with("Xen") {
            Some(MachineType::Xen)
        } else if system_model.contains("VMware") {
            Some(MachineType::VMware)
        } else if system_model.contains("Virtual Machine") {
            Some(MachineType::VirtualMachine)
        } else if system_model.contains("KVM") {
            Some(MachineType::Qemu)
        } else if bios_version.contains("VirtualBox") {
            Some(MachineType::VirtualBox)
        } else {
            None
        }
    }
}

/// `sysinfo` trims the DMI values but hands us the ones the firmware said nothing about, which
/// it leaves plenty of.
fn value(read: Option<String>) -> Option<String> {
    dmi_value(&read?)
}

/// Reads one of the one-line DMI files.
///
/// They hold an empty string or a placeholder rather than being absent when the firmware says
/// nothing, and are only readable by root for some of them.
#[cfg(target_os = "linux")]
fn dmi(name: &str) -> Option<String> {
    let value = std::fs::read_to_string(std::path::Path::new(DMI_DIR).join(name)).ok()?;
    dmi_value(&value)
}

#[cfg(not(target_os = "linux"))]
fn dmi(_name: &str) -> Option<String> {
    None
}

#[cfg(test)]
mod tests {
    use pretty_assertions::assert_eq;
    use quick_xml::se::Serializer;

    use super::*;

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

    /// Builds a `Bios` from the four values the rules look at.
    fn dmi_of(
        system_manufacturer: &str,
        system_model: &str,
        bios_manufacturer: &str,
        bios_version: &str,
    ) -> Bios {
        Bios {
            date: None,
            manufacturer: dmi_value(bios_manufacturer),
            version: dmi_value(bios_version),
            board_manufacturer: None,
            system_manufacturer: dmi_value(system_manufacturer),
            system_model: system_model.to_string(),
            system_serial_number: None,
        }
    }

    /// What the firmware of each of these actually says, so that the rules are checked against
    /// the strings they exist for rather than against themselves.
    #[test]
    fn it_names_the_kind_of_machine_the_firmware_describes() {
        for (manufacturer, model, bios_vendor, bios_version, expected) in [
            // The machine this is written on.
            (
                "QEMU",
                "Standard PC (Q35 + ICH9, 2009)",
                "EDK II",
                "",
                MachineType::Qemu,
            ),
            // A KVM guest whose firmware names it in the model instead.
            ("Red Hat", "KVM", "SeaBIOS", "", MachineType::Qemu),
            ("", "", "Bochs", "", MachineType::Qemu),
            // VMware, which names itself in either field.
            (
                "VMware, Inc.",
                "VMware Virtual Platform",
                "Phoenix",
                "",
                MachineType::VMware,
            ),
            ("", "VMware7,1", "", "", MachineType::VMware),
            // VirtualBox, from the vendor of its BIOS or from its version.
            (
                "innotek GmbH",
                "VirtualBox",
                "innotek GmbH",
                "",
                MachineType::VirtualBox,
            ),
            ("", "", "", "VirtualBox 6.1", MachineType::VirtualBox),
            // Hyper-V, which needs both fields: Microsoft also makes physical machines.
            (
                "Microsoft Corporation",
                "Virtual Machine",
                "",
                "",
                MachineType::HyperV,
            ),
            ("", "", "Xen", "", MachineType::Xen),
            // A hypervisor we have no name for, which still says it is one.
            ("", "Virtual Machine", "", "", MachineType::VirtualMachine),
        ] {
            let bios = dmi_of(manufacturer, model, bios_vendor, bios_version);
            assert_eq!(
                bios.machine_type(),
                Some(expected.clone()),
                "{manufacturer} / {model} / {bios_vendor} / {bios_version}"
            );
        }
    }

    #[test]
    fn it_names_no_kind_of_machine_for_a_physical_one() {
        // Real physical machines, which have to stay unnamed: the server reads no value as a
        // physical machine, and DMI cannot tell one from a virtualization we do not know.
        for (manufacturer, model, bios_vendor) in [
            ("LENOVO", "20HRCTO1WW", "LENOVO"),
            ("Dell Inc.", "PowerEdge R640", "Dell Inc."),
            ("HP", "ProLiant DL380 Gen10", "HP"),
            ("", "", ""),
        ] {
            let bios = dmi_of(manufacturer, model, bios_vendor, "");
            assert_eq!(bios.machine_type(), None, "{manufacturer} / {model}");
        }
        // Microsoft without a virtual model is a Surface, not a Hyper-V guest.
        assert_eq!(
            dmi_of("Microsoft Corporation", "Surface Pro 7", "", "").machine_type(),
            None
        );
    }

    /// A container has the firmware of whatever hosts it, so this names the host and not the
    /// container. It is why `systemd-detect-virt` is asked first.
    #[test]
    fn it_reports_a_container_as_the_machine_under_it() {
        let bios = dmi_of("QEMU", "Standard PC (Q35 + ICH9, 2009)", "EDK II", "");
        assert_eq!(bios.machine_type(), Some(MachineType::Qemu));
    }

    #[test]
    fn it_never_reports_an_unnamed_machine() {
        // Whatever this machine is, an entry without a model would be dropped by the server,
        // so we must either name it or report nothing.
        if let Some(bios) = Bios::inventory() {
            assert!(!bios.system_model.is_empty());
        }
    }

    /// Reads the DMI of the machine we run on. The model, the vendor and the values of the BIOS
    /// are readable by anyone, where the serial number and the UUID are not, so this covers the
    /// read and assemble path even without privileges.
    #[test]
    fn it_reads_the_dmi_of_this_machine() {
        let Some(bios) = Bios::inventory() else {
            // A machine without DMI, which we report nothing for.
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

    #[cfg(target_os = "linux")]
    #[test]
    fn it_reads_a_dmi_value_of_this_machine() {
        // The date of the BIOS is readable by anyone, and is a date.
        if let Some(date) = dmi("bios_date") {
            assert_eq!(date.split('/').count(), 3, "{date}");
        }
    }

    #[test]
    fn it_drops_the_empty_values_the_firmware_leaves_behind() {
        // What an unset DMI field looks like once sysinfo has trimmed it.
        assert_eq!(value(Some(String::new())), None);
        assert_eq!(value(Some("  \n".to_string())), None);
        assert_eq!(value(None), None);
        assert_eq!(value(Some(" QEMU\n".to_string())), Some("QEMU".to_string()));
        // A placeholder is not a value either: this machine reports one as its BIOS version.
        assert_eq!(value(Some("unknown".to_string())), None);
    }

    /// The values of this machine are real ones, not the stand-ins the firmware writes when it
    /// has nothing to say.
    #[test]
    fn it_reports_no_placeholder_of_this_machine() {
        let Some(bios) = Bios::inventory() else {
            return;
        };
        for value in [
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
                dmi_value(value),
                Some(value.clone()),
                "'{value}' is a placeholder, not a value"
            );
        }
    }

    #[test]
    fn it_reads_no_dmi_value_for_an_unknown_file() {
        assert_eq!(dmi("no_such_dmi_file"), None);
    }
}
