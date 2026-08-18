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

/// How the machine is virtualized, as `HARDWARE/VMSYSTEM`.
///
/// The variants are named as FusionInventory names them, since the server stores the value as it
/// is and the interface shows it: a node inventoried by either agent has to read the same.
#[derive(Debug, PartialEq, Serialize)]
pub enum VmSystem {
    /// Not virtualized, as far as the firmware says.
    Physical,
    #[serde(rename = "QEMU")]
    Qemu,
    #[serde(rename = "Hyper-V")]
    HyperV,
    #[serde(rename = "VMware")]
    VMware,
    VirtualBox,
    Xen,
    /// What a firmware that says it is virtual without saying by what is reported as.
    #[serde(rename = "Virtual Machine")]
    VirtualMachine,
}

impl VmSystem {
    /// What the firmware says the machine is, or `Physical` when it says nothing that names a
    /// hypervisor.
    ///
    /// This is the firmware half of FusionInventory's `_getType`, in `Virtualization/Vmsystem.pm`,
    /// field for field and in its order — the four blocks it runs before it starts reading
    /// `dmesg`, the loaded modules and the container files. Those we do not do, so a machine only
    /// a container or a paravirtualized guest gives itself away as is reported `Physical`.
    ///
    /// Nothing at all, which is a machine without DMI, is `Physical` as it is for FusionInventory.
    pub fn of(bios: Option<&Bios>) -> Self {
        let Some(bios) = bios else {
            return Self::Physical;
        };
        let system_manufacturer = bios.system_manufacturer.as_deref().unwrap_or_default();
        let system_model = bios.system_model.as_str();
        let manufacturer = bios.manufacturer.as_deref().unwrap_or_default();
        let version = bios.version.as_deref().unwrap_or_default();

        // The order is FusionInventory's: the first match wins, and the machine manufacturer is
        // asked before the manufacturer of the BIOS itself.
        let vm_system = if system_manufacturer.contains("QEMU") {
            Self::Qemu
        } else if system_manufacturer.contains("Microsoft") && system_model.contains("Virtual") {
            Self::HyperV
        } else if system_manufacturer.contains("VMware") {
            Self::VMware
        } else if manufacturer.contains("QEMU") || manufacturer.contains("Bochs") {
            Self::Qemu
        } else if manufacturer.contains("VirtualBox") || manufacturer.contains("innotek") {
            Self::VirtualBox
        } else if manufacturer.starts_with("Xen") {
            Self::Xen
        } else if system_model.contains("VMware") {
            Self::VMware
        } else if system_model.contains("Virtual Machine") {
            Self::VirtualMachine
        } else if system_model.contains("KVM") {
            Self::Qemu
        } else if version.contains("VirtualBox") {
            Self::VirtualBox
        } else {
            Self::Physical
        };
        debug!("The firmware describes a {vm_system:?} machine");
        vm_system
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

    /// Builds the four elements `VMSYSTEM` is decided from, the rest being irrelevant to it.
    fn firmware(
        system_manufacturer: &str,
        system_model: &str,
        manufacturer: &str,
        version: &str,
    ) -> Bios {
        Bios {
            date: None,
            manufacturer: value(manufacturer),
            version: value(version),
            board_manufacturer: None,
            system_manufacturer: value(system_manufacturer),
            system_model: system_model.to_string(),
            system_serial_number: None,
        }
    }

    /// The hypervisors `_getType` names, each from the element and in the order it reads them.
    #[test]
    fn it_names_a_hypervisor_as_fusion_inventory_does() {
        for (expected, system_manufacturer, system_model, manufacturer, version) in [
            // SMANUFACTURER, read first.
            (
                VmSystem::Qemu,
                "QEMU",
                "Standard PC (Q35 + ICH9, 2009)",
                "EDK II",
                "1.16.3",
            ),
            (
                VmSystem::HyperV,
                "Microsoft Corporation",
                "Virtual Machine",
                "American Megatrends",
                "090008",
            ),
            (
                VmSystem::VMware,
                "VMware, Inc.",
                "VMware Virtual Platform",
                "Phoenix",
                "6.00",
            ),
            // BMANUFACTURER, read next: a firmware naming no machine manufacturer.
            (VmSystem::Qemu, "", "Bochs", "Bochs", ""),
            (
                VmSystem::VirtualBox,
                "",
                "VirtualBox",
                "innotek GmbH",
                "VirtualBox",
            ),
            (VmSystem::Xen, "", "HVM domU", "Xen", "4.17"),
            // SMODEL, read third.
            (VmSystem::VMware, "Dell Inc.", "VMware7,1", "Dell", "2.1"),
            (
                VmSystem::VirtualMachine,
                "Nutanix",
                "Virtual Machine",
                "SeaBIOS",
                "1.0",
            ),
            (VmSystem::Qemu, "Red Hat", "KVM", "SeaBIOS", "1.0"),
            // BVERSION, read last.
            (
                VmSystem::VirtualBox,
                "Oracle",
                "Server",
                "Oracle",
                "VirtualBox 7.0",
            ),
            // Nothing that names a hypervisor.
            (
                VmSystem::Physical,
                "LENOVO",
                "21LB0022FR",
                "LENOVO",
                "R2CET47W",
            ),
        ] {
            let bios = firmware(system_manufacturer, system_model, manufacturer, version);
            assert_eq!(
                VmSystem::of(Some(&bios)),
                expected,
                "{system_manufacturer:?} {system_model:?} {manufacturer:?} {version:?}"
            );
        }
    }

    /// The order matters where two elements would each match: FusionInventory answers on the
    /// first, so we have to as well.
    #[test]
    fn it_reads_the_elements_in_fusion_inventorys_order() {
        // The machine manufacturer wins over the BIOS one.
        let bios = firmware("QEMU", "VirtualBox", "innotek GmbH", "VirtualBox");
        assert_eq!(VmSystem::of(Some(&bios)), VmSystem::Qemu);
        // Microsoft without a virtual model is not Hyper-V, and falls through to the rest.
        let bios = firmware(
            "Microsoft Corporation",
            "Surface Laptop",
            "Microsoft",
            "1.0",
        );
        assert_eq!(VmSystem::of(Some(&bios)), VmSystem::Physical);
    }

    /// A machine without DMI has no `BIOS` section, and is physical as far as anyone can tell,
    /// which is what FusionInventory reports for it too.
    #[test]
    fn it_reports_a_machine_without_firmware_as_physical() {
        assert_eq!(VmSystem::of(None), VmSystem::Physical);
        // A firmware that answered nothing but the model.
        let bios = firmware("", "To Be Filled By O.E.M.", "", "");
        assert_eq!(VmSystem::of(Some(&bios)), VmSystem::Physical);
    }

    /// The real values of the machines we have captured, so the strings the server stores are
    /// pinned to hardware rather than to what we expect of it.
    #[test]
    fn it_names_the_hypervisor_of_the_machines_we_have_seen() {
        // This machine, a QEMU guest of the laptop below.
        let bios = firmware(
            "QEMU",
            "Standard PC (Q35 + ICH9, 2009)",
            "EDK II",
            "unknown",
        );
        assert_eq!(VmSystem::of(Some(&bios)), VmSystem::Qemu);
        // The aarch64 machine, also QEMU.
        let bios = firmware("QEMU", "KVM Virtual Machine", "EDK II", "1.0");
        assert_eq!(VmSystem::of(Some(&bios)), VmSystem::Qemu);
        // The bare metal two socket server.
        let bios = firmware("Dell Inc.", "PowerEdge R440", "Dell Inc.", "2.19.1");
        assert_eq!(VmSystem::of(Some(&bios)), VmSystem::Physical);
    }

    /// The element holds the string the server stores, spelling and all. It is serialized as a
    /// field of `HARDWARE`, which is the only place it appears.
    #[test]
    fn it_serializes_a_vm_system_like_fusion_inventory() {
        #[derive(Serialize)]
        struct Section {
            #[serde(rename = "VMSYSTEM")]
            vm_system: VmSystem,
        }
        for (vm_system, expected) in [
            (VmSystem::Physical, "Physical"),
            (VmSystem::Qemu, "QEMU"),
            (VmSystem::HyperV, "Hyper-V"),
            (VmSystem::VMware, "VMware"),
            (VmSystem::VirtualBox, "VirtualBox"),
            (VmSystem::Xen, "Xen"),
            (VmSystem::VirtualMachine, "Virtual Machine"),
        ] {
            let mut out = String::new();
            let ser = Serializer::with_root(&mut out, Some("HARDWARE")).unwrap();
            Section { vm_system }.serialize(ser).unwrap();
            assert_eq!(
                out,
                format!("<HARDWARE><VMSYSTEM>{expected}</VMSYSTEM></HARDWARE>")
            );
        }
    }
}
