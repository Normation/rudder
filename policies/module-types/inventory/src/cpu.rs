// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

//! The processors of the machine, as the `CPUS` section.
//!
//! One entry per physical processor, as the server stores them and as FusionInventory reports
//! them: `/proc/cpuinfo` names the socket each logical CPU belongs to, and the first block of a
//! socket describes it.
//!
//! The name, vendor and frequency come from `sysinfo`, the counts, family number, model and
//! stepping from `/proc/cpuinfo`, and the identification bytes, family name and external clock
//! from `dmidecode`, as FusionInventory reads them.

use serde::Serialize;
use sysinfo::System;
use tracing::{debug, instrument, trace, warn};

use crate::{cmd, dmi_value, find_in_path};

/// Where the kernel describes the processors.
#[cfg(target_os = "linux")]
const PROC_CPUINFO: &str = "/proc/cpuinfo";

/// Fields are declared in the order FusionInventory serializes them, to keep both outputs
/// easy to compare.
///
/// Every element the server reads is produced.
#[derive(Debug, PartialEq, Serialize)]
#[serde(rename_all = "UPPERCASE")]
pub struct Cpu {
    #[serde(skip_serializing_if = "Option::is_none")]
    arch: Option<String>,
    /// Number of physical cores.
    #[serde(skip_serializing_if = "Option::is_none")]
    core: Option<usize>,
    /// The clock of the bus, in megahertz.
    #[serde(rename = "EXTERNAL_CLOCK", skip_serializing_if = "Option::is_none")]
    external_clock: Option<u16>,
    #[serde(rename = "FAMILYNAME", skip_serializing_if = "Option::is_none")]
    family_name: Option<String>,
    #[serde(rename = "FAMILYNUMBER", skip_serializing_if = "Option::is_none")]
    family: Option<u32>,
    /// The eight identification bytes of the processor.
    #[serde(rename = "ID", skip_serializing_if = "Option::is_none")]
    id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    manufacturer: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    model: Option<u32>,
    name: String,
    /// In megahertz.
    #[serde(skip_serializing_if = "Option::is_none")]
    speed: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    stepping: Option<u32>,
    /// Number of logical CPUs of the processor, which FusionInventory calls its siblings.
    #[serde(skip_serializing_if = "Option::is_none")]
    thread: Option<usize>,
}

impl Cpu {
    /// The processors of the machine, one entry per physical one.
    ///
    /// `sysinfo` knows nothing of the socket a logical CPU belongs to, so the topology comes
    /// from `/proc/cpuinfo`. A kernel that names no socket, as an ARM one does not, leaves us
    /// with one entry for the whole machine and its total counts.
    ///
    /// Returns nothing when we cannot name the processor, as the server drops such an entry.
    #[instrument(level = "debug", name = "cpu", skip(sys))]
    pub fn inventory(sys: &System) -> Vec<Self> {
        let Some(cpu) = sys.cpus().first() else {
            return vec![];
        };
        let arch = System::cpu_arch();
        let arch = (!arch.is_empty()).then_some(arch);
        let manufacturer = manufacturer(cpu.vendor_id());
        let Some(name) = name(cpu.brand(), manufacturer.as_deref(), arch.as_deref()) else {
            warn!("The processor has no name at all, reporting no CPU");
            return vec![];
        };
        if name != cpu.brand().trim() {
            warn!("The processor has no model name, calling it '{name}' instead");
        }
        let sockets = Socket::read();
        let dmi = Dmi::read();
        debug!(
            "{} socket(s) named by the kernel, {} processor(s) described by dmidecode",
            sockets.len(),
            dmi.len()
        );
        trace!("Sockets: {sockets:?}\nProcessors: {dmi:?}");

        // Everything `sysinfo` gives us describes the machine, not one of its processors, so
        // the processors of a machine only differ by what the other two sources say.
        let of_socket = |socket: Option<&Socket>, dmi: Option<&Dmi>, core, thread| Self {
            arch: arch.clone(),
            core,
            external_clock: dmi.and_then(|d| d.external_clock),
            family_name: dmi.and_then(|d| d.family_name.clone()),
            family: socket.and_then(|s| s.family),
            id: dmi.and_then(|d| d.id.clone()),
            manufacturer: manufacturer.clone(),
            model: socket.and_then(|s| s.model),
            name: name.clone(),
            speed: (cpu.frequency() > 0).then(|| cpu.frequency()),
            stepping: socket.and_then(|s| s.stepping),
            thread,
        };

        if sockets.is_empty() {
            debug!("The kernel names no socket, reporting the machine as one processor");
            return vec![of_socket(
                None,
                dmi.first(),
                System::physical_core_count(),
                Some(sys.cpus().len()),
            )];
        }
        sockets
            .iter()
            .enumerate()
            // The nth socket of the kernel is the nth processor `dmidecode` describes, as both
            // only ever list the sockets that hold one.
            .map(|(n, socket)| of_socket(Some(socket), dmi.get(n), socket.core, socket.thread))
            .collect()
    }
}

/// The command that describes the processors of the machine.
const DMIDECODE: &str = "dmidecode";

/// The processor values neither `sysinfo` nor `/proc/cpuinfo` hold.
///
/// They live in the SMBIOS table, which only `dmidecode` reads for us: the identification bytes
/// and the external clock are in there as they are, and the name of the family comes from a table
/// of more than two hundred entries that the command carries. FusionInventory reads all three
/// the same way.
#[derive(Debug, Default, PartialEq)]
struct Dmi {
    external_clock: Option<u16>,
    family_name: Option<String>,
    id: Option<String>,
}

impl Dmi {
    /// Nothing at all when the command is not installed, or when we may not run it, which is
    /// the case for anyone but root.
    fn read() -> Vec<Self> {
        if find_in_path(DMIDECODE).is_none() {
            debug!("No '{DMIDECODE}', reporting no processor detail from it");
            return vec![];
        }
        match cmd(DMIDECODE, &["-t", "4"], None) {
            Ok(output) => Self::parse(&output),
            Err(e) => {
                debug!("Could not run '{DMIDECODE}': {e:#}");
                vec![]
            }
        }
    }

    /// The processors of the output, in the order it prints them.
    ///
    /// `dmidecode` prints one block per socket, separated by an empty line, and a machine
    /// describes the sockets it has nothing in as well. Like FusionInventory we leave those out,
    /// which is also what makes this list line up with the sockets the kernel names.
    fn parse(output: &str) -> Vec<Self> {
        let mut res = vec![];
        for block in output.split("\n\n") {
            if !block.contains("Processor Information") {
                continue;
            }
            let field = |label: &str| {
                block
                    .lines()
                    .filter_map(|line| line.trim().strip_prefix(label))
                    .find_map(dmi_value)
            };
            if field("Status:")
                .is_some_and(|status| status.contains("Unpopulated") || status.contains("Disabled"))
            {
                continue;
            }
            res.push(Self {
                external_clock: field("External Clock:").as_deref().and_then(megahertz),
                family_name: field("Family:"),
                id: field("ID:"),
            });
        }
        res
    }
}

/// A speed `dmidecode` prints as `100 MHz`, or nothing when it says it does not know it.
fn megahertz(value: &str) -> Option<u16> {
    value.strip_suffix("MHz")?.trim().parse().ok()
}

/// What to call the processor.
///
/// `sysinfo` reads the name from `model name`, and on ARM decodes `CPU implementer` and
/// `CPU part` when the file has no such line. A machine that gives neither leaves us with the
/// manufacturer, then the architecture: they name a processor poorly, but the server drops an
/// entry with no name at all, and with it the counts and the architecture of the machine.
/// FusionInventory falls back to the number of the processor.
fn name(brand: &str, manufacturer: Option<&str>, arch: Option<&str>) -> Option<String> {
    let brand = brand.trim();
    if !brand.is_empty() {
        return Some(brand.to_string());
    }
    manufacturer.or(arch).map(str::to_string)
}

/// The canonical name of the manufacturer of a processor.
///
/// The kernel reports the vendor identifier, `AuthenticAMD` for instance, where the server is
/// shown the name of a manufacturer. Only the two vendors worth naming are mapped; any other
/// identifier is passed through as it is, which is also what FusionInventory ends up doing for
/// the ones it does not know.
///
/// On ARM the file names no vendor, but `sysinfo` decodes the `CPU implementer` into one, so
/// there usually is something to report. We report nothing rather than an empty element when
/// there is not.
fn manufacturer(vendor_id: &str) -> Option<String> {
    Some(
        match vendor_id.trim() {
            "" => return None,
            "GenuineIntel" => "Intel",
            "AuthenticAMD" => "AMD",
            other => other,
        }
        .to_string(),
    )
}

/// One physical processor, as `/proc/cpuinfo` describes it.
#[derive(Debug, Default, PartialEq)]
struct Socket {
    /// Number of physical cores of this processor.
    core: Option<usize>,
    /// Number of logical CPUs of this processor.
    thread: Option<usize>,
    family: Option<u32>,
    model: Option<u32>,
    stepping: Option<u32>,
}

impl Socket {
    #[cfg(target_os = "linux")]
    fn read() -> Vec<Self> {
        match std::fs::read_to_string(PROC_CPUINFO) {
            Ok(content) => Self::parse(&content),
            Err(e) => {
                warn!("Could not read '{PROC_CPUINFO}': {e}");
                vec![]
            }
        }
    }

    #[cfg(not(target_os = "linux"))]
    fn read() -> Vec<Self> {
        vec![]
    }

    /// The physical processors of a `/proc/cpuinfo`, in the order they first appear.
    ///
    /// The file holds one block per logical CPU, separated by an empty line, and each names the
    /// socket it belongs to. Keeping the first block of every socket leaves one entry per
    /// physical processor.
    ///
    /// Returns nothing when the kernel names no socket at all. An ARM one does not, and
    /// describes its processors with an implementer, a part and a variant, which have no place
    /// in what the server stores.
    fn parse(content: &str) -> Vec<Self> {
        let mut sockets = vec![];
        let mut seen = vec![];
        for block in content.split("\n\n") {
            // The keys are compared whole, so that "model" does not also match "model name".
            let value = |key: &str| {
                block
                    .lines()
                    .filter_map(|line| line.split_once(':'))
                    .find(|(k, _)| k.trim() == key)
                    .map(|(_, v)| v.trim())
            };
            // Without a socket we cannot tell one processor from another.
            let Some(id) = value("physical id") else {
                continue;
            };
            if seen.contains(&id) {
                continue;
            }
            seen.push(id);
            sockets.push(Self {
                core: value("cpu cores").and_then(|v| v.parse().ok()),
                thread: value("siblings").and_then(|v| v.parse().ok()),
                family: value("cpu family").and_then(|v| v.parse().ok()),
                model: value("model").and_then(|v| v.parse().ok()),
                stepping: value("stepping").and_then(|v| v.parse().ok()),
            });
        }
        sockets
    }
}

#[cfg(test)]
mod tests {
    use pretty_assertions::assert_eq;
    use quick_xml::se::Serializer;

    use super::*;

    /// A real `/proc/cpuinfo` of a machine with two sockets, four cores each and two threads
    /// per core, cut down to the keys we read.
    const CPUINFO: &str = "processor\t: 0
vendor_id\t: GenuineIntel
cpu family\t: 6
model\t\t: 79
model name\t: Intel(R) Xeon(R) CPU E5-2623 v4 @ 2.60GHz
stepping\t: 1
physical id\t: 0
siblings\t: 8
cpu cores\t: 4

processor\t: 1
cpu family\t: 6
model\t\t: 79
stepping\t: 1
physical id\t: 0
siblings\t: 8
cpu cores\t: 4

processor\t: 8
cpu family\t: 6
model\t\t: 79
stepping\t: 1
physical id\t: 1
siblings\t: 8
cpu cores\t: 4
";

    #[test]
    fn it_reports_one_processor_per_socket() {
        let sockets = Socket::parse(CPUINFO);
        assert_eq!(
            sockets,
            vec![
                Socket {
                    core: Some(4),
                    thread: Some(8),
                    family: Some(6),
                    model: Some(79),
                    stepping: Some(1),
                },
                Socket {
                    core: Some(4),
                    thread: Some(8),
                    family: Some(6),
                    model: Some(79),
                    stepping: Some(1),
                },
            ],
            "the two sockets of the machine are not reported as two processors"
        );
    }

    /// The counts are those of one processor, not of the machine: reporting the eight cores and
    /// sixteen threads of the machine on each of its two processors would double them.
    #[test]
    fn it_reports_the_counts_of_one_socket() {
        let socket = &Socket::parse(CPUINFO)[0];
        assert_eq!(socket.core, Some(4));
        assert_eq!(socket.thread, Some(8));
    }

    /// Reads the sockets of the machine we run on. `/proc/cpuinfo` is readable by anyone, so
    /// this covers the read and parse path.
    #[cfg(target_os = "linux")]
    #[test]
    fn it_reads_the_sockets_of_this_machine() {
        let sockets = Socket::read();
        let logical = System::new_with_specifics(
            sysinfo::RefreshKind::nothing().with_cpu(sysinfo::CpuRefreshKind::nothing()),
        )
        .cpus()
        .len();
        if sockets.is_empty() {
            // A kernel that names no socket, which we report as one processor.
            return;
        }
        assert!(sockets.len() <= logical, "more sockets than logical CPUs");
        // The logical CPUs of every socket add up to those of the machine.
        let threads: usize = sockets.iter().filter_map(|s| s.thread).sum();
        assert_eq!(threads, logical);
        for socket in &sockets {
            assert!(socket.core.is_some(), "a socket with no core count");
        }
    }

    #[test]
    fn it_reports_no_socket_from_an_arm_cpuinfo() {
        // An ARM kernel names no socket, and describes its processors in its own terms.
        let arm = "processor\t: 0
BogoMIPS\t: 50.00
Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32
CPU implementer\t: 0x41
CPU architecture: 8
CPU variant\t: 0x0
CPU part\t: 0xd0c
CPU revision\t: 1
";
        assert!(Socket::parse(arm).is_empty());
    }

    #[test]
    fn it_reports_no_socket_from_a_broken_cpuinfo() {
        assert!(Socket::parse("").is_empty());
        assert!(Socket::parse("no colon here\n").is_empty());
        // A socket whose values are not numbers is still a socket.
        let socket = &Socket::parse("physical id\t: 0\ncpu cores\t: many\n")[0];
        assert_eq!(socket.core, None);
    }

    /// The output of this machine, which describes one socket per logical CPU.
    const DMIDECODE_OUTPUT: &str = "# dmidecode 3.6
Getting SMBIOS data from sysfs.
SMBIOS 2.8 present.

Handle 0x0400, DMI type 4, 42 bytes
Processor Information
\tSocket Designation: CPU 0
\tType: Central Processor
\tFamily: Other
\tManufacturer: QEMU
\tID: 52 0F A7 00 FF FB 8B 07
\tVersion: pc-q35-11.0
\tVoltage: Unknown
\tExternal Clock: Unknown
\tMax Speed: 2000 MHz
\tStatus: Populated, Enabled

Handle 0x0401, DMI type 4, 42 bytes
Processor Information
\tSocket Designation: CPU 1
\tFamily: Other
\tID: 52 0F A7 00 FF FB 8B 07
\tStatus: Populated, Enabled
";

    /// One entry per socket the command describes, in its order.
    #[test]
    fn it_reads_every_processor_dmidecode_describes() {
        assert_eq!(
            Dmi::parse(DMIDECODE_OUTPUT),
            vec![
                Dmi {
                    // The firmware does not know it, and says so.
                    external_clock: None,
                    family_name: Some("Other".to_string()),
                    id: Some("52 0F A7 00 FF FB 8B 07".to_string()),
                },
                Dmi {
                    external_clock: None,
                    family_name: Some("Other".to_string()),
                    id: Some("52 0F A7 00 FF FB 8B 07".to_string()),
                },
            ]
        );
    }

    /// What real hardware reports, where the clock is a value and the family has a name.
    #[test]
    fn it_reads_a_named_family_and_a_known_clock() {
        let output = "Handle 0x0400, DMI type 4, 48 bytes
Processor Information
\tFamily: Xeon
\tID: F1 06 04 00 FF FB EB BF
\tExternal Clock: 100 MHz
\tStatus: Populated, Enabled
";
        assert_eq!(
            Dmi::parse(output),
            vec![Dmi {
                external_clock: Some(100),
                family_name: Some("Xeon".to_string()),
                id: Some("F1 06 04 00 FF FB EB BF".to_string()),
            }]
        );
    }

    /// A machine describes the sockets it has nothing in, and leaving those out is what keeps
    /// this list aligned with the sockets the kernel names.
    #[test]
    fn it_skips_the_sockets_without_a_processor() {
        let output = "Handle 0x0400, DMI type 4, 48 bytes
Processor Information
\tSocket Designation: CPU 1
\tFamily: Unknown
\tID: 00 00 00 00 00 00 00 00
\tStatus: Unpopulated

Handle 0x0401, DMI type 4, 48 bytes
Processor Information
\tSocket Designation: CPU 2
\tFamily: Xeon
\tID: F1 06 04 00 FF FB EB BF
\tStatus: Populated, Enabled
";
        let dmi = Dmi::parse(output);
        assert_eq!(dmi.len(), 1, "an empty socket was reported");
        assert_eq!(dmi[0].family_name, Some("Xeon".to_string()));
    }

    #[test]
    fn it_reads_nothing_from_an_output_without_a_processor() {
        assert!(Dmi::parse("").is_empty());
        let empty = "# dmidecode 3.6\nGetting SMBIOS data from sysfs.\nSMBIOS 2.8 present.\n";
        assert!(Dmi::parse(empty).is_empty());
    }

    #[test]
    fn it_reads_a_speed_in_megahertz() {
        assert_eq!(megahertz("100 MHz"), Some(100));
        assert_eq!(megahertz("2000 MHz"), Some(2000));
        // What the firmware says when it does not know.
        assert_eq!(megahertz("Unknown"), None);
        assert_eq!(megahertz(""), None);
        assert_eq!(megahertz("100"), None);
    }

    #[test]
    fn it_names_a_processor_the_kernel_does_not() {
        // What every x86 machine gives us.
        assert_eq!(
            name("AMD Ryzen 7 PRO 8840U", Some("AMD"), Some("x86_64")),
            Some("AMD Ryzen 7 PRO 8840U".to_string())
        );
        // A board with no model name and no decodable part: the manufacturer names it, which
        // keeps the counts and the architecture of the entry.
        assert_eq!(
            name("", Some("ARM"), Some("aarch64")),
            Some("ARM".to_string())
        );
        // Not even a manufacturer.
        assert_eq!(
            name("  ", None, Some("aarch64")),
            Some("aarch64".to_string())
        );
        // Nothing at all, where the server would drop the entry anyway.
        assert_eq!(name("", None, None), None);
    }

    #[test]
    fn it_maps_a_vendor_identifier_to_a_manufacturer_name() {
        assert_eq!(manufacturer("GenuineIntel"), Some("Intel".to_string()));
        assert_eq!(manufacturer("AuthenticAMD"), Some("AMD".to_string()));
        // Any other vendor is reported as the kernel names it.
        for vendor_id in ["HygonGenuine", "CentaurHauls", "SomeNewVendor"] {
            assert_eq!(manufacturer(vendor_id), Some(vendor_id.to_string()));
        }
        // What an ARM kernel reports, where an empty element would be worse than none.
        assert_eq!(manufacturer(""), None);
        assert_eq!(manufacturer("  "), None);
    }

    #[test]
    fn it_reports_the_processors_of_this_machine() {
        let _guard = crate::no_concurrent_fork();
        let mut sys = System::new();
        sys.refresh_cpu_all();
        let cpus = Cpu::inventory(&sys);
        assert!(!cpus.is_empty(), "no processor reported");
        // One entry per socket, or one for the machine when the kernel names no socket.
        #[cfg(target_os = "linux")]
        {
            let sockets = Socket::read().len().max(1);
            assert_eq!(cpus.len(), sockets);
        }
        let total: usize = cpus.iter().filter_map(|c| c.thread).sum();
        assert_eq!(
            total,
            sys.cpus().len(),
            "the threads of every processor do not add up to the logical CPUs of the machine"
        );
        for cpu in &cpus {
            assert!(!cpu.name.is_empty());
            // A processor can never have more cores than it has logical CPUs.
            assert!(match (cpu.core, cpu.thread) {
                (Some(core), Some(thread)) => core <= thread,
                _ => true,
            });
        }
    }

    /// The serialized shape has to stay the one FusionInventory produces, field for field.
    #[test]
    fn it_serializes_a_cpu_like_fusion_inventory() {
        let cpu = Cpu {
            arch: Some("x86_64".to_string()),
            core: Some(10),
            external_clock: Some(100),
            family_name: Some("Other".to_string()),
            family: Some(25),
            id: Some("52 0F A7 00 FF FB 8B 07".to_string()),
            manufacturer: Some("AMD".to_string()),
            model: Some(117),
            name: "AMD Ryzen 7 PRO 8840U".to_string(),
            speed: Some(3293),
            stepping: Some(2),
            thread: Some(10),
        };
        let mut out = String::new();
        let mut ser = Serializer::with_root(&mut out, Some("CPUS")).unwrap();
        ser.indent(' ', 2);
        cpu.serialize(ser).unwrap();
        assert_eq!(
            out,
            concat!(
                "<CPUS>\n",
                "  <ARCH>x86_64</ARCH>\n",
                "  <CORE>10</CORE>\n",
                "  <EXTERNAL_CLOCK>100</EXTERNAL_CLOCK>\n",
                "  <FAMILYNAME>Other</FAMILYNAME>\n",
                "  <FAMILYNUMBER>25</FAMILYNUMBER>\n",
                "  <ID>52 0F A7 00 FF FB 8B 07</ID>\n",
                "  <MANUFACTURER>AMD</MANUFACTURER>\n",
                "  <MODEL>117</MODEL>\n",
                "  <NAME>AMD Ryzen 7 PRO 8840U</NAME>\n",
                "  <SPEED>3293</SPEED>\n",
                "  <STEPPING>2</STEPPING>\n",
                "  <THREAD>10</THREAD>\n",
                "</CPUS>",
            )
        );
    }
}
