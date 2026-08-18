// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

//! The SMBIOS tables, which describe the machine, its firmware and its processors.
//!
//! They are what identifies the machine the node runs on, and the only source for a handful of
//! values the kernel does not expose: the identification bytes of a processor and the name of its
//! family, in particular.
//!
//! FusionInventory reads them by running `dmidecode`, and we read them ourselves, with
//! `smbios-lib`. It saves a command we are not sure to find installed — `dmidecode` is not in the
//! dependencies of the agent — and one that only exists on the platforms it was ported to, where
//! the library reads the tables of Linux, of Windows and of macOS through the interface each of
//! them exposes. The values are the ones the firmware wrote, so both agents report the same
//! machine identically, down to the family names, which come from `dmidecode`'s own table
//! ([`FAMILY_NAMES`]) rather than from the SMBIOS wording the library carries.
//!
//! The tables are read once for the whole inventory: three sections describe the same machine,
//! and re-reading them could describe two.
//!
//! Reading them takes root on every platform, as reading them with `dmidecode` did, and a
//! machine may have none of them at all: every value is therefore optional, and the sections
//! report what they have rather than failing.

use smbioslib::{
    CpuStatus, SMBiosBaseboardInformation, SMBiosData, SMBiosInformation,
    SMBiosProcessorInformation, SMBiosString, SMBiosSystemInformation, SystemUuidData,
    table_load_from_device,
};
use tracing::debug;

/// The SMBIOS tables of the machine.
pub struct Dmi {
    tables: SMBiosData,
}

impl Dmi {
    /// Nothing when the machine describes itself in no SMBIOS table, or when we may not read
    /// them, which is the case for anyone but root.
    pub fn read() -> Option<Self> {
        match table_load_from_device() {
            Ok(tables) => Some(Self { tables }),
            Err(e) => {
                // Expected when we do not run as root, which is why this is not a warning.
                debug!("Could not read the SMBIOS tables, reporting no firmware value: {e}");
                None
            }
        }
    }

    /// The release date of the BIOS, which the firmware writes as `MM/DD/YYYY`, the format the
    /// server expects.
    pub fn bios_date(&self) -> Option<String> {
        string(self.bios()?.release_date())
    }

    pub fn bios_vendor(&self) -> Option<String> {
        string(self.bios()?.vendor())
    }

    pub fn bios_version(&self) -> Option<String> {
        string(self.bios()?.version())
    }

    pub fn system_manufacturer(&self) -> Option<String> {
        string(self.system()?.manufacturer())
    }

    /// The model of the machine, which is what names it.
    pub fn system_model(&self) -> Option<String> {
        string(self.system()?.product_name())
    }

    pub fn system_serial_number(&self) -> Option<String> {
        string(self.system()?.serial_number())
    }

    /// The identifier of the machine, as the lowercase hyphenated form of RFC 4122, which is
    /// how the kernel and the server both write it.
    ///
    /// A firmware with nothing to identify the machine by says so in two ways, and neither is a
    /// value: the identifier is absent, or it is absent and the machine can be given one.
    pub fn system_uuid(&self) -> Option<String> {
        match self.system()?.uuid()? {
            SystemUuidData::Uuid(uuid) => Some(uuid.to_string()),
            SystemUuidData::IdNotPresent | SystemUuidData::IdNotPresentButSettable => None,
        }
    }

    /// The manufacturer of the motherboard, which the server falls back to when the system
    /// manufacturer is missing.
    pub fn board_manufacturer(&self) -> Option<String> {
        string(self.board()?.manufacturer())
    }

    /// The processors of the machine, in the order the firmware describes them, leaving out the
    /// sockets it holds none in.
    pub fn processors(&self) -> Vec<Processor> {
        self.tables
            .collect::<SMBiosProcessorInformation<'_>>()
            .iter()
            .filter(|processor| populated(processor))
            .map(Processor::of)
            .collect()
    }

    /// The BIOS itself, as the type 0 table.
    fn bios(&self) -> Option<SMBiosInformation<'_>> {
        self.tables.first::<SMBiosInformation<'_>>()
    }

    /// The machine, as the type 1 table.
    fn system(&self) -> Option<SMBiosSystemInformation<'_>> {
        self.tables.first::<SMBiosSystemInformation<'_>>()
    }

    /// The motherboard, as the type 2 table, which the virtual machines that describe no
    /// motherboard have none of.
    fn board(&self) -> Option<SMBiosBaseboardInformation<'_>> {
        self.tables.first::<SMBiosBaseboardInformation<'_>>()
    }
}

/// What one processor holds that neither `sysinfo` nor `/proc/cpuinfo` does.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct Processor {
    pub family_name: Option<String>,
    pub id: Option<String>,
}

impl Processor {
    fn of(processor: &SMBiosProcessorInformation<'_>) -> Self {
        Self {
            family_name: family_name(processor),
            id: id(processor),
        }
    }
}

/// Whether the machine holds a processor in that socket.
///
/// A machine describes the sockets it has nothing in as well, and like FusionInventory we leave
/// those out: it is also what makes the list of processors line up with the sockets the kernel
/// names. A firmware that does not say is taken at its word and its socket kept, as the field
/// only exists from SMBIOS 2.1 on.
fn populated(processor: &SMBiosProcessorInformation<'_>) -> bool {
    let Some(status) = processor.status() else {
        return true;
    };
    status.socket_populated()
        && !matches!(
            status.cpu_status(),
            CpuStatus::UserDisabled | CpuStatus::BiosDisabled
        )
}

/// The eight identification bytes of the processor, as `dmidecode` prints them and as
/// FusionInventory reports them: uppercase hexadecimal, one space between bytes.
///
/// A firmware that knows nothing of its processor writes zeroes, which is what it has to say
/// about it and is reported as such, as it is by both agents.
fn id(processor: &SMBiosProcessorInformation<'_>) -> Option<String> {
    let bytes = processor.processor_id()?;
    let id: Vec<String> = bytes.iter().map(|byte| format!("{byte:02X}")).collect();
    value(&id.join(" "))
}

/// The value of the family field that means the family did not fit in it, and that the firmware
/// wrote it in the wider field instead.
const FAMILY_IN_FAMILY_2: u8 = 0xFE;

/// The family the specification never assigned, and which two vendors used anyway.
const AMBIGUOUS_FAMILY: u16 = 0xBE;

/// The name of the processor family.
///
/// The firmware only holds a number, and the name comes from [`FAMILY_NAMES`]. A number neither
/// the specification nor `dmidecode` knows is reported as no family rather than as the
/// `<OUT OF SPEC>` the command prints, which is a value for neither agent.
fn family_name(processor: &SMBiosProcessorInformation<'_>) -> Option<String> {
    let family = processor.processor_family()?;
    let code = if family.raw == FAMILY_IN_FAMILY_2 {
        processor.processor_family_2()?.raw
    } else {
        u16::from(family.raw)
    };
    if code == AMBIGUOUS_FAMILY {
        return Some(ambiguous_family(processor).to_string());
    }
    let name = FAMILY_NAMES
        .iter()
        .find(|(value, _)| *value == code)
        .map(|(_, name)| *name)?;
    value(name)
}

/// What to call the family the specification left ambiguous.
///
/// `0xBE` was used by Intel for the Core 2 and by AMD for the K7, and the manufacturer is all
/// there is to tell them apart, as it is for `dmidecode`. A manufacturer neither of them names
/// leaves us naming both, which is the guess the command makes as well.
fn ambiguous_family(processor: &SMBiosProcessorInformation<'_>) -> &'static str {
    let manufacturer = string(processor.processor_manufacturer()).unwrap_or_default();
    if names(&manufacturer, "Intel") {
        "Core 2"
    } else if names(&manufacturer, "AMD") {
        "K7"
    } else {
        "Core 2 or K7"
    }
}

/// Whether a manufacturer is the given vendor.
///
/// The two ways `dmidecode` recognizes one: the name holds it as it is written, or it starts on
/// it whatever the case. A vendor that spells itself out, `Advanced Micro Devices, Inc.`, is
/// therefore no vendor to either agent, and the family stays the ambiguous one.
fn names(manufacturer: &str, vendor: &str) -> bool {
    manufacturer.contains(vendor)
        || manufacturer
            .to_lowercase()
            .starts_with(&vendor.to_lowercase())
}

/// A string of a table, or nothing when the firmware wrote no usable value into it.
///
/// A field the firmware left out, one it wrote a placeholder into and one the table is too short
/// to hold are all the same thing here: no value. The string is never taken through its
/// `Display`, which prints the *error* of an unreadable field, and would report the message
/// itself as the value of the machine.
fn string(read: SMBiosString) -> Option<String> {
    value(&read.ok()?)
}

/// The placeholders the firmware writes into a table instead of leaving a value out.
///
/// This is FusionInventory's own list, taken from the regexp `getDmidecodeInfos` skips a value
/// on in `Tools/Generic.pm`, so that both agents stay silent about the same fields.
const PLACEHOLDERS: &[&str] = &[
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

/// A value of the firmware, or nothing when it only wrote a placeholder into it.
pub(crate) fn value(value: &str) -> Option<String> {
    let value = value.trim();
    let compared: String = value
        .chars()
        .filter(|c| !c.is_whitespace())
        .flat_map(char::to_lowercase)
        .collect();
    if compared.is_empty() || PLACEHOLDERS.contains(&compared.as_str()) {
        return None;
    }
    Some(value.to_string())
}

/// The name of every processor family, as `dmidecode` prints it.
///
/// This is the `family2` table of `dmi_processor_family` in `dmidecode.c`, so that a machine
/// inventoried by one agent and then by the other does not change processor family: the
/// specification words a family as "Intel® Xeon® processor", `smbios-lib` names its variant
/// after that wording, and both agents report `Xeon`.
///
/// The one thing left out is the `0x30` of SMBIOS 2.0, which the command reads as a Pentium Pro
/// on an Intel machine and as an Alpha everywhere else. Version 2.0 is from 1997, and a machine
/// that old is not one we run on.
const FAMILY_NAMES: &[(u16, &str)] = &[
    (0x01, "Other"),
    (0x02, "Unknown"),
    (0x03, "8086"),
    (0x04, "80286"),
    (0x05, "80386"),
    (0x06, "80486"),
    (0x07, "8087"),
    (0x08, "80287"),
    (0x09, "80387"),
    (0x0A, "80487"),
    (0x0B, "Pentium"),
    (0x0C, "Pentium Pro"),
    (0x0D, "Pentium II"),
    (0x0E, "Pentium MMX"),
    (0x0F, "Celeron"),
    (0x10, "Pentium II Xeon"),
    (0x11, "Pentium III"),
    (0x12, "M1"),
    (0x13, "M2"),
    (0x14, "Celeron M"),
    (0x15, "Pentium 4 HT"),
    (0x16, "Intel"),
    (0x18, "Duron"),
    (0x19, "K5"),
    (0x1A, "K6"),
    (0x1B, "K6-2"),
    (0x1C, "K6-3"),
    (0x1D, "Athlon"),
    (0x1E, "AMD29000"),
    (0x1F, "K6-2+"),
    (0x20, "Power PC"),
    (0x21, "Power PC 601"),
    (0x22, "Power PC 603"),
    (0x23, "Power PC 603+"),
    (0x24, "Power PC 604"),
    (0x25, "Power PC 620"),
    (0x26, "Power PC x704"),
    (0x27, "Power PC 750"),
    (0x28, "Core Duo"),
    (0x29, "Core Duo Mobile"),
    (0x2A, "Core Solo Mobile"),
    (0x2B, "Atom"),
    (0x2C, "Core M"),
    (0x2D, "Core m3"),
    (0x2E, "Core m5"),
    (0x2F, "Core m7"),
    (0x30, "Alpha"),
    (0x31, "Alpha 21064"),
    (0x32, "Alpha 21066"),
    (0x33, "Alpha 21164"),
    (0x34, "Alpha 21164PC"),
    (0x35, "Alpha 21164a"),
    (0x36, "Alpha 21264"),
    (0x37, "Alpha 21364"),
    (0x38, "Turion II Ultra Dual-Core Mobile M"),
    (0x39, "Turion II Dual-Core Mobile M"),
    (0x3A, "Athlon II Dual-Core M"),
    (0x3B, "Opteron 6100"),
    (0x3C, "Opteron 4100"),
    (0x3D, "Opteron 6200"),
    (0x3E, "Opteron 4200"),
    (0x3F, "FX"),
    (0x40, "MIPS"),
    (0x41, "MIPS R4000"),
    (0x42, "MIPS R4200"),
    (0x43, "MIPS R4400"),
    (0x44, "MIPS R4600"),
    (0x45, "MIPS R10000"),
    (0x46, "C-Series"),
    (0x47, "E-Series"),
    (0x48, "A-Series"),
    (0x49, "G-Series"),
    (0x4A, "Z-Series"),
    (0x4B, "R-Series"),
    (0x4C, "Opteron 4300"),
    (0x4D, "Opteron 6300"),
    (0x4E, "Opteron 3300"),
    (0x4F, "FirePro"),
    (0x50, "SPARC"),
    (0x51, "SuperSPARC"),
    (0x52, "MicroSPARC II"),
    (0x53, "MicroSPARC IIep"),
    (0x54, "UltraSPARC"),
    (0x55, "UltraSPARC II"),
    (0x56, "UltraSPARC IIi"),
    (0x57, "UltraSPARC III"),
    (0x58, "UltraSPARC IIIi"),
    (0x60, "68040"),
    (0x61, "68xxx"),
    (0x62, "68000"),
    (0x63, "68010"),
    (0x64, "68020"),
    (0x65, "68030"),
    (0x66, "Athlon X4"),
    (0x67, "Opteron X1000"),
    (0x68, "Opteron X2000"),
    (0x69, "Opteron A-Series"),
    (0x6A, "Opteron X3000"),
    (0x6B, "Zen"),
    (0x70, "Hobbit"),
    (0x78, "Crusoe TM5000"),
    (0x79, "Crusoe TM3000"),
    (0x7A, "Efficeon TM8000"),
    (0x80, "Weitek"),
    (0x82, "Itanium"),
    (0x83, "Athlon 64"),
    (0x84, "Opteron"),
    (0x85, "Sempron"),
    (0x86, "Turion 64"),
    (0x87, "Dual-Core Opteron"),
    (0x88, "Athlon 64 X2"),
    (0x89, "Turion 64 X2"),
    (0x8A, "Quad-Core Opteron"),
    (0x8B, "Third-Generation Opteron"),
    (0x8C, "Phenom FX"),
    (0x8D, "Phenom X4"),
    (0x8E, "Phenom X2"),
    (0x8F, "Athlon X2"),
    (0x90, "PA-RISC"),
    (0x91, "PA-RISC 8500"),
    (0x92, "PA-RISC 8000"),
    (0x93, "PA-RISC 7300LC"),
    (0x94, "PA-RISC 7200"),
    (0x95, "PA-RISC 7100LC"),
    (0x96, "PA-RISC 7100"),
    (0xA0, "V30"),
    (0xA1, "Quad-Core Xeon 3200"),
    (0xA2, "Dual-Core Xeon 3000"),
    (0xA3, "Quad-Core Xeon 5300"),
    (0xA4, "Dual-Core Xeon 5100"),
    (0xA5, "Dual-Core Xeon 5000"),
    (0xA6, "Dual-Core Xeon LV"),
    (0xA7, "Dual-Core Xeon ULV"),
    (0xA8, "Dual-Core Xeon 7100"),
    (0xA9, "Quad-Core Xeon 5400"),
    (0xAA, "Quad-Core Xeon"),
    (0xAB, "Dual-Core Xeon 5200"),
    (0xAC, "Dual-Core Xeon 7200"),
    (0xAD, "Quad-Core Xeon 7300"),
    (0xAE, "Quad-Core Xeon 7400"),
    (0xAF, "Multi-Core Xeon 7400"),
    (0xB0, "Pentium III Xeon"),
    (0xB1, "Pentium III Speedstep"),
    (0xB2, "Pentium 4"),
    (0xB3, "Xeon"),
    (0xB4, "AS400"),
    (0xB5, "Xeon MP"),
    (0xB6, "Athlon XP"),
    (0xB7, "Athlon MP"),
    (0xB8, "Itanium 2"),
    (0xB9, "Pentium M"),
    (0xBA, "Celeron D"),
    (0xBB, "Pentium D"),
    (0xBC, "Pentium EE"),
    (0xBD, "Core Solo"),
    (0xBF, "Core 2 Duo"),
    (0xC0, "Core 2 Solo"),
    (0xC1, "Core 2 Extreme"),
    (0xC2, "Core 2 Quad"),
    (0xC3, "Core 2 Extreme Mobile"),
    (0xC4, "Core 2 Duo Mobile"),
    (0xC5, "Core 2 Solo Mobile"),
    (0xC6, "Core i7"),
    (0xC7, "Dual-Core Celeron"),
    (0xC8, "IBM390"),
    (0xC9, "G4"),
    (0xCA, "G5"),
    (0xCB, "ESA/390 G6"),
    (0xCC, "z/Architecture"),
    (0xCD, "Core i5"),
    (0xCE, "Core i3"),
    (0xCF, "Core i9"),
    (0xD2, "C7-M"),
    (0xD3, "C7-D"),
    (0xD4, "C7"),
    (0xD5, "Eden"),
    (0xD6, "Multi-Core Xeon"),
    (0xD7, "Dual-Core Xeon 3xxx"),
    (0xD8, "Quad-Core Xeon 3xxx"),
    (0xD9, "Nano"),
    (0xDA, "Dual-Core Xeon 5xxx"),
    (0xDB, "Quad-Core Xeon 5xxx"),
    (0xDD, "Dual-Core Xeon 7xxx"),
    (0xDE, "Quad-Core Xeon 7xxx"),
    (0xDF, "Multi-Core Xeon 7xxx"),
    (0xE0, "Multi-Core Xeon 3400"),
    (0xE4, "Opteron 3000"),
    (0xE5, "Sempron II"),
    (0xE6, "Embedded Opteron Quad-Core"),
    (0xE7, "Phenom Triple-Core"),
    (0xE8, "Turion Ultra Dual-Core Mobile"),
    (0xE9, "Turion Dual-Core Mobile"),
    (0xEA, "Athlon Dual-Core"),
    (0xEB, "Sempron SI"),
    (0xEC, "Phenom II"),
    (0xED, "Athlon II"),
    (0xEE, "Six-Core Opteron"),
    (0xEF, "Sempron M"),
    (0xFA, "i860"),
    (0xFB, "i960"),
    (0x100, "ARMv7"),
    (0x101, "ARMv8"),
    (0x102, "ARMv9"),
    (0x103, "ARM"),
    (0x104, "SH-3"),
    (0x105, "SH-4"),
    (0x118, "ARM"),
    (0x119, "StrongARM"),
    (0x12C, "6x86"),
    (0x12D, "MediaGX"),
    (0x12E, "MII"),
    (0x140, "WinChip"),
    (0x15E, "DSP"),
    (0x1F4, "Video Processor"),
    (0x200, "RV32"),
    (0x201, "RV64"),
    (0x202, "RV128"),
    (0x258, "LoongArch"),
    (0x259, "Loongson 1"),
    (0x25A, "Loongson 2"),
    (0x25B, "Loongson 3"),
    (0x25C, "Loongson 2K"),
    (0x25D, "Loongson 3A"),
    (0x25E, "Loongson 3B"),
    (0x25F, "Loongson 3C"),
    (0x260, "Loongson 3D"),
    (0x261, "Loongson 3E"),
    (0x262, "Dual-Core Loongson 2K 2xxx"),
    (0x26C, "Quad-Core Loongson 3A 5xxx"),
    (0x26D, "Multi-Core Loongson 3A 5xxx"),
    (0x26E, "Quad-Core Loongson 3B 5xxx"),
    (0x26F, "Multi-Core Loongson 3B 5xxx"),
    (0x270, "Multi-Core Loongson 3C 5xxx"),
    (0x271, "Multi-Core Loongson 3D 5xxx"),
];

#[cfg(test)]
mod tests {
    use pretty_assertions::assert_eq;
    use smbioslib::SMBiosVersion;

    use super::*;

    /// The header every structure starts with: its type, its length, and its handle.
    const HEADER: usize = 4;

    /// A structure of a table: its header, the fields of its type, then the strings those fields
    /// point into, which are numbered from one.
    fn structure(kind: u8, fields: &[u8], strings: &[&str]) -> Vec<u8> {
        let length = u8::try_from(HEADER + fields.len()).expect("a structure of a test");
        let mut out = vec![kind, length, 0x00, 0x00];
        out.extend_from_slice(fields);
        for string in strings {
            out.extend_from_slice(string.as_bytes());
            out.push(0);
        }
        // The strings of a structure end on an empty one, so a structure holding none is two
        // zeroes.
        out.push(0);
        if strings.is_empty() {
            out.push(0);
        }
        out
    }

    /// The tables of a machine, out of the structures it describes itself with.
    fn dmi(structures: &[Vec<u8>]) -> Dmi {
        Dmi {
            tables: SMBiosData::from_vec_and_version(
                structures.concat(),
                Some(SMBiosVersion::new(3, 3, 0)),
            ),
        }
    }

    /// The type 0 structure of a BIOS, whose three values are strings.
    fn bios(vendor: &str, version: &str, date: &str) -> Vec<u8> {
        let fields = [
            1, // vendor
            2, // version
            0x00, 0xE8, // starting address segment
            3,    // release date
            0xFF, // ROM size
            // Characteristics, which nothing reads.
            0, 0, 0, 0, 0, 0, 0, 0,
        ];
        structure(0, &fields, &[vendor, version, date])
    }

    /// The type 1 structure of a machine. Its identifier is the sixteen bytes the firmware holds,
    /// which are not the order they are printed in.
    fn system(manufacturer: &str, model: &str, serial: &str, uuid: [u8; 16]) -> Vec<u8> {
        let mut fields = vec![
            1, // manufacturer
            2, // product name
            0, // version, which nothing reads
            3, // serial number
        ];
        fields.extend_from_slice(&uuid);
        fields.extend_from_slice(&[
            0x06, // wake-up type
            0,    // SKU number
            0,    // family
        ]);
        structure(1, &fields, &[manufacturer, model, serial])
    }

    /// The type 2 structure of a motherboard, of which only the manufacturer is read.
    fn board(manufacturer: &str) -> Vec<u8> {
        let fields = [
            1,    // manufacturer
            0,    // product
            0,    // version
            0,    // serial number
            0,    // asset tag
            0x09, // feature flags
            0,    // location in chassis
            0x00, 0x03, // chassis handle
            0x0A, // board type
            0,    // contained object handles
        ];
        structure(2, &fields, &[manufacturer])
    }

    /// The status of a socket holding an enabled processor.
    const ENABLED: u8 = 0x41;

    /// The type 4 structure of one processor, long enough to hold the wider family field.
    fn processor(
        family: u8,
        id: [u8; 8],
        status: u8,
        family_2: u16,
        manufacturer: &str,
    ) -> Vec<u8> {
        let mut fields = vec![
            1,    // socket designation
            0x03, // processor type, a central processor
            family, 2, // manufacturer
        ];
        fields.extend_from_slice(&id);
        fields.extend_from_slice(&[
            3,    // version
            0x8A, // voltage
            0x64, 0x00, // external clock
            0x0D, 0x0C, // max speed
            0x98, 0x08, // current speed
            status, 0x01, // upgrade
            0x00, 0x00, // L1 cache handle
            0x01, 0x00, // L2 cache handle
            0x02, 0x00, // L3 cache handle
            0,    // serial number
            0,    // asset tag
            0,    // part number
            0x08, // core count
            0x08, // cores enabled
            0x10, // thread count
            0x0C, 0x00, // characteristics
        ]);
        fields.extend_from_slice(&family_2.to_le_bytes());
        structure(4, &fields, &["FP8", manufacturer, "AMD Ryzen 7 PRO 8840U"])
    }

    /// A processor of a socket holding one, described by the family the firmware wrote in the
    /// byte field.
    fn of_family(family: u8) -> Vec<u8> {
        processor(family, [0; 8], ENABLED, 0, "Advanced Micro Devices, Inc.")
    }

    #[test]
    fn it_reads_the_bios_of_a_machine() {
        let dmi = dmi(&[bios("EDK II", "1.16.3-2", "02/02/2022")]);
        assert_eq!(dmi.bios_vendor(), Some("EDK II".to_string()));
        assert_eq!(dmi.bios_version(), Some("1.16.3-2".to_string()));
        // The date the server reads, in the `MM/DD/YYYY` the firmware writes.
        assert_eq!(dmi.bios_date(), Some("02/02/2022".to_string()));
    }

    /// The identifier is printed as the lowercase hyphenated form the kernel and the server both
    /// use, out of bytes whose first three fields are the other way around.
    #[test]
    fn it_reads_the_machine() {
        let uuid = [
            0x83, 0xC4, 0x92, 0x3D, 0x07, 0x44, 0x8A, 0x4D, 0xA8, 0x4E, 0x52, 0x83, 0xEA, 0x93,
            0xFD, 0x4E,
        ];
        let dmi = dmi(&[system("QEMU", "Standard PC (Q35 + ICH9, 2009)", "42", uuid)]);
        assert_eq!(dmi.system_manufacturer(), Some("QEMU".to_string()));
        assert_eq!(
            dmi.system_model(),
            Some("Standard PC (Q35 + ICH9, 2009)".to_string())
        );
        assert_eq!(dmi.system_serial_number(), Some("42".to_string()));
        assert_eq!(
            dmi.system_uuid(),
            Some("3d92c483-4407-4d8a-a84e-5283ea93fd4e".to_string())
        );
    }

    #[test]
    fn it_reads_the_motherboard() {
        assert_eq!(
            dmi(&[board("QEMU")]).board_manufacturer(),
            Some("QEMU".to_string())
        );
        // The virtual machines that describe no motherboard at all, where the server falls back
        // to the system manufacturer.
        assert_eq!(dmi(&[bios("EDK II", "1.0", "")]).board_manufacturer(), None);
    }

    /// A machine that describes itself in no table at all, which every value is optional for.
    #[test]
    fn it_reads_nothing_from_an_empty_table() {
        let dmi = dmi(&[]);
        assert_eq!(dmi.bios_vendor(), None);
        assert_eq!(dmi.system_model(), None);
        assert_eq!(dmi.system_uuid(), None);
        assert_eq!(dmi.board_manufacturer(), None);
        assert!(dmi.processors().is_empty());
    }

    /// The placeholders the firmware writes instead of leaving a value out are values for
    /// neither agent, wherever they appear.
    #[test]
    fn it_reports_no_placeholder_of_a_machine() {
        let dmi = dmi(&[
            bios("Not Specified", "unknown", "To Be Filled By O.E.M."),
            system("Default string", "System Product Name", "None", [0xAB; 16]),
        ]);
        assert_eq!(dmi.bios_vendor(), None);
        assert_eq!(dmi.bios_version(), None);
        assert_eq!(dmi.bios_date(), None);
        assert_eq!(dmi.system_serial_number(), None);
        // Placeholders FusionInventory reports, so we report them too.
        assert_eq!(
            dmi.system_manufacturer(),
            Some("Default string".to_string())
        );
        assert_eq!(dmi.system_model(), Some("System Product Name".to_string()));
    }

    /// The two ways a firmware says the machine has no identifier, neither of which is one.
    #[test]
    fn it_reports_no_identifier_the_firmware_does_not_hold() {
        assert_eq!(
            dmi(&[system("QEMU", "PC", "42", [0xFF; 16])]).system_uuid(),
            None
        );
        assert_eq!(
            dmi(&[system("QEMU", "PC", "42", [0x00; 16])]).system_uuid(),
            None
        );
    }

    /// A two socket server, whose sockets hold the same processor, so the firmware says the same
    /// of each.
    #[test]
    fn it_reads_every_processor_the_firmware_describes() {
        let xeon = processor(
            0xB3,
            [0x54, 0x06, 0x05, 0x00, 0xFF, 0xFB, 0xEB, 0xBF],
            ENABLED,
            0,
            "Intel",
        );
        assert_eq!(
            dmi(&[xeon.clone(), xeon]).processors(),
            vec![
                Processor {
                    family_name: Some("Xeon".to_string()),
                    id: Some("54 06 05 00 FF FB EB BF".to_string()),
                };
                2
            ]
        );
    }

    /// The name is `dmidecode`'s, not the SMBIOS wording `smbios-lib` names its variants after:
    /// this machine reports `Zen`, where the library calls the family
    /// `AMDZenProcessorFamily`, and a node inventoried by both agents must not change family.
    #[test]
    fn it_names_a_family_as_dmidecode_does() {
        let ryzen = processor(
            0x6B,
            [0x52, 0x0F, 0xA7, 0x00, 0xFF, 0xFB, 0x8B, 0x17],
            ENABLED,
            0,
            "Advanced Micro Devices, Inc.",
        );
        assert_eq!(
            dmi(&[ryzen]).processors(),
            vec![Processor {
                family_name: Some("Zen".to_string()),
                id: Some("52 0F A7 00 FF FB 8B 17".to_string()),
            }]
        );
    }

    /// A family whose number does not fit in the byte field is read from the wider one, which is
    /// how every ARM machine names its family.
    #[test]
    fn it_names_a_family_that_did_not_fit_in_its_field() {
        let arm = processor(FAMILY_IN_FAMILY_2, [0; 8], ENABLED, 0x0101, "QEMU");
        let processors = dmi(&[arm]).processors();
        assert_eq!(processors[0].family_name, Some("ARMv8".to_string()));
        // A firmware that knows nothing of its processor writes zeroes, which both agents report.
        assert_eq!(
            processors[0].id,
            Some("00 00 00 00 00 00 00 00".to_string())
        );
    }

    /// `0xBE` is the one number two vendors used for two different families, and the
    /// manufacturer is all there is to tell them apart.
    #[test]
    fn it_names_the_ambiguous_family_by_its_manufacturer() {
        for (manufacturer, family) in [
            ("Intel(R) Corporation", "Core 2"),
            // The case of a name that starts on the vendor does not matter.
            ("intel", "Core 2"),
            ("AMD", "K7"),
            ("amd", "K7"),
            // A vendor that spells itself out is no vendor to `dmidecode` either, and the
            // family stays the ambiguous one.
            ("Advanced Micro Devices, Inc.", "Core 2 or K7"),
            // Neither of the two, where naming both is the best there is.
            ("QEMU", "Core 2 or K7"),
        ] {
            let ambiguous = processor(0xBE, [0; 8], ENABLED, 0, manufacturer);
            assert_eq!(
                dmi(&[ambiguous]).processors()[0].family_name,
                Some(family.to_string()),
                "{manufacturer}"
            );
        }
    }

    #[test]
    fn it_reports_no_family_it_cannot_name() {
        // A number the specification does not assign, which `dmidecode` prints as out of spec.
        assert_eq!(dmi(&[of_family(0xF0)]).processors()[0].family_name, None);
        // The firmware saying it does not know, which is not a family name.
        assert_eq!(dmi(&[of_family(0x02)]).processors()[0].family_name, None);
        // `Other` is one both agents report, as it is what the firmware knows.
        assert_eq!(
            dmi(&[of_family(0x01)]).processors()[0].family_name,
            Some("Other".to_string())
        );
    }

    /// A machine describes the sockets it holds no processor in as well, and leaving those out is
    /// what keeps this list aligned with the sockets the kernel names.
    #[test]
    fn it_skips_the_sockets_without_a_processor() {
        // Unpopulated, populated but disabled by the user, and populated but disabled by the
        // firmware.
        for status in [0x00, 0x02, 0x03] {
            let empty = processor(0xB3, [0; 8], status, 0, "Intel");
            assert!(
                dmi(&[empty]).processors().is_empty(),
                "a socket of status {status:#04X} was reported"
            );
        }
        let populated = processor(0xB3, [0; 8], ENABLED, 0, "Intel");
        assert_eq!(dmi(&[populated]).processors().len(), 1);
    }

    /// A processor the firmware describes nothing usable of is still an entry, because the
    /// entries are what the sockets of the kernel are matched against: dropping one would shift
    /// every later socket onto another processor's values.
    #[test]
    fn it_keeps_a_processor_it_reads_nothing_from() {
        let unknown = processor(0x02, [0; 8], ENABLED, 0, "Not Specified");
        let xeon = processor(
            0xB3,
            [0xF1, 0x06, 0x04, 0x00, 0xFF, 0xFB, 0xEB, 0xBF],
            ENABLED,
            0,
            "Intel",
        );
        let processors = dmi(&[unknown, xeon]).processors();
        assert_eq!(
            processors.len(),
            2,
            "a processor was dropped, shifting the next ones"
        );
        assert_eq!(processors[0].family_name, None);
        assert_eq!(
            processors[1].id,
            Some("F1 06 04 00 FF FB EB BF".to_string())
        );
    }

    /// Reads the tables of the machine we run on, which takes root: a run without it reports no
    /// firmware value at all rather than failing.
    #[test]
    fn it_reads_the_dmi_of_this_machine() {
        let Some(dmi) = Dmi::read() else {
            // Not root, or a machine with no SMBIOS table.
            return;
        };
        // Every value we report has one: an empty element would be worse than none.
        for value in [
            dmi.bios_date(),
            dmi.bios_vendor(),
            dmi.bios_version(),
            dmi.system_manufacturer(),
            dmi.system_model(),
            dmi.system_serial_number(),
            dmi.system_uuid(),
            dmi.board_manufacturer(),
        ] {
            assert!(value.as_deref() != Some(""), "an empty value was reported");
        }
        for processor in dmi.processors() {
            assert!(processor.family_name.as_deref() != Some(""));
            // The eight bytes, as two characters and a space each.
            if let Some(id) = processor.id {
                assert_eq!(id.split(' ').count(), 8, "{id}");
            }
        }
    }

    /// The values are the ones `getDmidecodeInfos` skips in `Tools/Generic.pm`, written as
    /// `dmidecode` prints them, so that both agents stay silent about the same fields.
    #[test]
    fn it_drops_the_placeholders_the_firmware_writes() {
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
            assert_eq!(value(placeholder), None, "{placeholder}");
            // The case and the spacing of a placeholder vary, as they do for FusionInventory.
            assert_eq!(value(&placeholder.to_lowercase()), None, "{placeholder}");
            assert_eq!(value(&placeholder.to_uppercase()), None, "{placeholder}");
            assert_eq!(value(&placeholder.replace(' ', "")), None, "{placeholder}");
            assert_eq!(value(&format!("  {placeholder}\n")), None, "{placeholder}");
        }
        // Nothing at all is not a value either.
        assert_eq!(value(""), None);
        assert_eq!(value(" \n"), None);
    }

    /// Only a whole value is a placeholder, and only the ones FusionInventory knows: reporting
    /// what it reports matters more here than dropping every stand-in there is.
    #[test]
    fn it_keeps_the_values_that_only_look_like_placeholders() {
        for kept in [
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
            assert_eq!(value(kept), Some(kept.to_string()));
        }
        // The value is trimmed, as the firmware pads plenty of them.
        assert_eq!(value(" QEMU\n"), Some("QEMU".to_string()));
    }

    /// The table is the one the command carries, and a family is looked up in it by its number:
    /// a duplicate or a stray entry would name the wrong processor.
    #[test]
    fn it_holds_one_name_per_family_number() {
        let mut codes: Vec<u16> = FAMILY_NAMES.iter().map(|(code, _)| *code).collect();
        let read = codes.len();
        codes.sort_unstable();
        codes.dedup();
        assert_eq!(codes.len(), read, "a family number appears twice");
        // The families of the machines we run on, spot checked against what the command prints.
        for (code, name) in [
            (0x01, "Other"),
            (0x6B, "Zen"),
            (0xB3, "Xeon"),
            (0xC6, "Core i7"),
            (0x100, "ARMv7"),
            (0x101, "ARMv8"),
        ] {
            let found = FAMILY_NAMES.iter().find(|(value, _)| *value == code);
            assert_eq!(found.map(|(_, name)| *name), Some(name), "{code:#X}");
        }
        // The two numbers that are never looked up in it.
        for special in [u16::from(FAMILY_IN_FAMILY_2), AMBIGUOUS_FAMILY] {
            assert!(
                !FAMILY_NAMES.iter().any(|(code, _)| *code == special),
                "{special:#X} is in the table"
            );
        }
    }
}
