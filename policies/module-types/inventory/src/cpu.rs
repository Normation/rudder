// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

//! The processors of the machine, as the `CPUS` section.
//!
//! One entry per physical processor, as the server stores them and as FusionInventory reports
//! them: `/proc/cpuinfo` names the socket each logical CPU belongs to, and the first block of a
//! socket describes it.
//!
//! The name and the vendor come from `sysinfo`, the counts, family number, model and stepping
//! from `/proc/cpuinfo`, and the identification bytes and the family name from the SMBIOS
//! tables, which is where FusionInventory reads them too.

use serde::Serialize;
use sysinfo::System;
use tracing::{debug, instrument, trace, warn};

use crate::{
    dmi::{Dmi, Processor},
    util::empty_to_none,
};

/// Where the kernel describes the processors.
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
    #[serde(skip_serializing_if = "Option::is_none")]
    stepping: Option<u32>,
    /// Number of logical CPUs of the processor, which FusionInventory calls its siblings.
    #[serde(skip_serializing_if = "Option::is_none")]
    thread: Option<usize>,
}

/// What `sysinfo` answers about the machine, which describes every processor it holds.
#[derive(Debug, Default, PartialEq)]
struct Machine {
    arch: Option<String>,
    manufacturer: Option<String>,
    /// Never empty: a machine we cannot name the processor of has none of these.
    name: String,
    /// Number of logical CPUs of the whole machine.
    logical: usize,
    /// Number of physical cores of the whole machine.
    physical_cores: Option<usize>,
}

impl Machine {
    /// Nothing when there is no processor to describe, or nothing at all to call it by, as the
    /// server drops an entry without a name.
    ///
    /// The `System` has to have had its CPUs refreshed.
    fn read(sys: &System) -> Option<Self> {
        let cpu = sys.cpus().first()?;
        let arch = empty_to_none(&System::cpu_arch());
        let manufacturer = manufacturer(cpu.vendor_id());
        let Some(name) = name(cpu.brand(), manufacturer.as_deref(), arch.as_deref()) else {
            warn!("The processor has no name at all, reporting no CPU");
            return None;
        };
        if name != cpu.brand().trim() {
            warn!("The processor has no model name, calling it '{name}' instead");
        }
        Some(Self {
            arch,
            manufacturer,
            name,
            logical: sys.cpus().len(),
            physical_cores: System::physical_core_count(),
        })
    }
}

/// The processors of the machine, one entry per physical one.
///
/// `sysinfo` knows nothing of the socket a logical CPU belongs to, so the topology comes from
/// `/proc/cpuinfo`, and the rest of what one processor holds from the SMBIOS tables, which are
/// read once for the whole inventory and handed over.
///
/// The `System` has to have had its CPUs refreshed.
#[instrument(level = "debug", name = "cpu", skip(sys, dmi))]
pub fn inventory(sys: &System, dmi: Option<&Dmi>) -> Vec<Cpu> {
    let Some(machine) = Machine::read(sys) else {
        return vec![];
    };
    let sockets = Socket::read();
    let processors = dmi.map(Dmi::processors).unwrap_or_default();
    debug!(
        "{} socket(s) named by the kernel, {} processor(s) described by the firmware",
        sockets.len(),
        processors.len()
    );
    trace!("Sockets: {sockets:?}\nProcessors: {processors:?}");
    assemble(&machine, &sockets, &processors)
}

/// One entry per socket the kernel names, out of the three sources.
///
/// A kernel that names no socket, as an ARM one does not, leaves us with one entry for the whole
/// machine and its total counts.
fn assemble(machine: &Machine, sockets: &[Socket], processors: &[Processor]) -> Vec<Cpu> {
    // Everything `sysinfo` gives us describes the machine, not one of its processors, so the
    // processors of a machine only differ by what the other two sources say.
    let of_socket = |socket: Option<&Socket>, processor: Option<&Processor>, core, thread| Cpu {
        arch: machine.arch.clone(),
        core,
        family_name: processor.and_then(|p| p.family_name.clone()),
        family: socket.and_then(|s| s.family),
        id: processor.and_then(|p| p.id.clone()),
        manufacturer: machine.manufacturer.clone(),
        model: socket.and_then(|s| s.model),
        name: machine.name.clone(),
        stepping: socket.and_then(|s| s.stepping),
        thread,
    };

    if sockets.is_empty() {
        debug!("The kernel names no socket, reporting the machine as one processor");
        return vec![of_socket(
            None,
            processors.first(),
            machine.physical_cores,
            Some(machine.logical),
        )];
    }
    sockets
        .iter()
        .enumerate()
        // The nth socket of the kernel is the nth processor the firmware describes, as both only
        // ever list the sockets that hold one. A socket the firmware described nothing for keeps
        // what the kernel says of it rather than borrowing another processor's values.
        .map(|(n, socket)| of_socket(Some(socket), processors.get(n), socket.core, socket.thread))
        .collect()
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
/// shown the name of a manufacturer. The mapping is the `%manufacturers` of
/// `getCanonicalManufacturer` in `Tools.pm`, so that both agents name a vendor the same way: a
/// machine inventoried by one and then by the other must not change manufacturer in the
/// interface. Any identifier neither of them knows is passed through as it is.
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
            "HygonGenuine" => "Hygon",
            "CentaurHauls" => "VIA",
            "CyrixInstead" => "Cyrix",
            "TMx86" | "TransmetaCPU" => "Transmeta",
            other => other,
        }
        .to_string(),
    )
}

/// One physical processor, as `/proc/cpuinfo` describes it.
#[derive(Clone, Debug, Default, PartialEq)]
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
    fn read() -> Vec<Self> {
        match std::fs::read_to_string(PROC_CPUINFO) {
            Ok(content) => Self::parse(&content),
            Err(e) => {
                warn!("Could not read '{PROC_CPUINFO}': {e}");
                vec![]
            }
        }
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

    /// The `/proc/cpuinfo` of a two socket server, cut after the third logical CPU. The kernel
    /// alternates between the sockets rather than describing one and then the other, so the
    /// blocks name `physical id` 0, then 1, then 0 again: a socket is recognised wherever its
    /// blocks appear. Twenty cores in all, each running two threads.
    ///
    /// The `flags`, `vmx flags` and `bugs` lists are cut to their first few entries, nothing
    /// reads them. The rest is verbatim.
    const CPUINFO: &str = "processor\t: 0
vendor_id\t: GenuineIntel
cpu family\t: 6
model\t\t: 85
model name\t: Intel(R) Xeon(R) Silver 4114 CPU @ 2.20GHz
stepping\t: 4
microcode\t: 0x2007006
cpu MHz\t\t: 2499.996
cache size\t: 14080 KB
physical id\t: 0
siblings\t: 20
core id\t\t: 0
cpu cores\t: 10
apicid\t\t: 0
initial apicid\t: 0
fpu\t\t: yes
fpu_exception\t: yes
cpuid level\t: 22
wp\t\t: yes
flags\t\t: fpu vme de pse tsc msr pae mce cx8 apic sep mtrr pge mca cmov pat pse36 clflush dts acpi mmx fxsr sse sse2 ss ht tm pbe syscall nx pdpe1gb rdtscp lm constant_tsc art arch_perfmon
vmx flags\t: vnmi preemption_timer posted_intr invvpid ept_x_only ept_ad ept_1gb flexpriority apicv
bugs\t\t: cpu_meltdown spectre_v1 spectre_v2 spec_store_bypass l1tf mds swapgs taa itlb_multihit
bogomips\t: 4400.00
clflush size\t: 64
cache_alignment\t: 64
address sizes\t: 46 bits physical, 48 bits virtual
power management:

processor\t: 1
vendor_id\t: GenuineIntel
cpu family\t: 6
model\t\t: 85
model name\t: Intel(R) Xeon(R) Silver 4114 CPU @ 2.20GHz
stepping\t: 4
microcode\t: 0x2007006
cpu MHz\t\t: 2499.998
cache size\t: 14080 KB
physical id\t: 1
siblings\t: 20
core id\t\t: 0
cpu cores\t: 10
apicid\t\t: 32
initial apicid\t: 32
fpu\t\t: yes
fpu_exception\t: yes
cpuid level\t: 22
wp\t\t: yes
flags\t\t: fpu vme de pse tsc msr pae mce cx8 apic sep mtrr pge mca cmov pat pse36 clflush dts acpi mmx fxsr sse sse2 ss ht tm pbe syscall nx pdpe1gb rdtscp lm constant_tsc art arch_perfmon
vmx flags\t: vnmi preemption_timer posted_intr invvpid ept_x_only ept_ad ept_1gb flexpriority apicv
bugs\t\t: cpu_meltdown spectre_v1 spectre_v2 spec_store_bypass l1tf mds swapgs taa itlb_multihit
bogomips\t: 4400.00
clflush size\t: 64
cache_alignment\t: 64
address sizes\t: 46 bits physical, 48 bits virtual
power management:

processor\t: 2
vendor_id\t: GenuineIntel
cpu family\t: 6
model\t\t: 85
model name\t: Intel(R) Xeon(R) Silver 4114 CPU @ 2.20GHz
stepping\t: 4
microcode\t: 0x2007006
cpu MHz\t\t: 1794.584
cache size\t: 14080 KB
physical id\t: 0
siblings\t: 20
core id\t\t: 4
cpu cores\t: 10
apicid\t\t: 8
initial apicid\t: 8
fpu\t\t: yes
fpu_exception\t: yes
cpuid level\t: 22
wp\t\t: yes
flags\t\t: fpu vme de pse tsc msr pae mce cx8 apic sep mtrr pge mca cmov pat pse36 clflush dts acpi mmx fxsr sse sse2 ss ht tm pbe syscall nx pdpe1gb rdtscp lm constant_tsc art arch_perfmon
vmx flags\t: vnmi preemption_timer posted_intr invvpid ept_x_only ept_ad ept_1gb flexpriority apicv
bugs\t\t: cpu_meltdown spectre_v1 spectre_v2 spec_store_bypass l1tf mds swapgs taa itlb_multihit
bogomips\t: 4400.00
clflush size\t: 64
cache_alignment\t: 64
address sizes\t: 46 bits physical, 48 bits virtual
power management:
";

    #[test]
    fn it_reports_one_processor_per_socket() {
        let sockets = Socket::parse(CPUINFO);
        let xeon = Socket {
            core: Some(10),
            thread: Some(20),
            family: Some(6),
            model: Some(85),
            stepping: Some(4),
        };
        assert_eq!(
            sockets,
            vec![xeon.clone(), xeon],
            "the two sockets of the machine are not reported as two processors"
        );
    }

    /// The counts are those of one processor, not of the machine: reporting the twenty cores and
    /// forty threads of the machine on each of its two processors would double them.
    #[test]
    fn it_reports_the_counts_of_one_socket() {
        let socket = &Socket::parse(CPUINFO)[0];
        assert_eq!(socket.core, Some(10));
        assert_eq!(socket.thread, Some(20));
    }

    /// The two socket server end to end, the only shape where the pairing can go wrong.
    #[test]
    fn it_reports_a_two_socket_server_as_two_processors() {
        let machine = Machine {
            arch: Some("x86_64".to_string()),
            manufacturer: manufacturer("GenuineIntel"),
            name: "Intel(R) Xeon(R) Silver 4114 CPU @ 2.20GHz".to_string(),
            logical: 40,
            physical_cores: Some(20),
        };
        // Both sockets hold the same processor, so the firmware says the same of each.
        let xeon = Processor {
            family_name: Some("Xeon".to_string()),
            id: Some("54 06 05 00 FF FB EB BF".to_string()),
        };
        let cpus = assemble(&machine, &Socket::parse(CPUINFO), &[xeon.clone(), xeon]);
        assert_eq!(cpus.len(), 2, "the two sockets are not two entries");
        for cpu in &cpus {
            assert_eq!(cpu.manufacturer.as_deref(), Some("Intel"));
            // Of one processor, so both entries add up to the machine.
            assert_eq!(cpu.core, Some(10));
            assert_eq!(cpu.thread, Some(20));
            assert_eq!(cpu.family, Some(6));
            assert_eq!(cpu.model, Some(85));
            assert_eq!(cpu.stepping, Some(4));
            assert_eq!(cpu.family_name.as_deref(), Some("Xeon"));
            assert_eq!(cpu.id.as_deref(), Some("54 06 05 00 FF FB EB BF"));
        }
        let threads: usize = cpus.iter().filter_map(|c| c.thread).sum();
        assert_eq!(threads, machine.logical, "the threads do not add up");
        let cores: usize = cpus.iter().filter_map(|c| c.core).sum();
        assert_eq!(cores, 20, "the cores do not add up");
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

    /// The `/proc/cpuinfo` of a 16 logical CPU aarch64 machine, cut after the second one. The
    /// blocks are all identical but for the processor number: the kernel names **no socket**, no
    /// vendor and no model, and describes the processor as an implementer, a part and a variant.
    ///
    /// `0x41` is ARM and `0xd0c` a Neoverse-N1, which `sysinfo` decodes into the manufacturer and
    /// the name we report.
    const ARM_CPUINFO: &str = "processor\t: 0
BogoMIPS\t: 50.00
Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp
CPU implementer\t: 0x41
CPU architecture: 8
CPU variant\t: 0x3
CPU part\t: 0xd0c
CPU revision\t: 1

processor\t: 1
BogoMIPS\t: 50.00
Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp
CPU implementer\t: 0x41
CPU architecture: 8
CPU variant\t: 0x3
CPU part\t: 0xd0c
CPU revision\t: 1
";

    #[test]
    fn it_reports_no_socket_from_an_arm_cpuinfo() {
        assert!(
            Socket::parse(ARM_CPUINFO).is_empty(),
            "an ARM machine was reported as sockets"
        );
    }

    /// What we make of that machine: `sysinfo` decodes `CPU implementer` into the vendor and
    /// `CPU part` into the brand, and the entry is then the one processor the whole machine is,
    /// holding its total counts.
    #[test]
    fn it_reports_an_arm_machine_as_one_processor() {
        let manufacturer = manufacturer("ARM");
        assert_eq!(manufacturer, Some("ARM".to_string()));
        let name = name("Neoverse-N1", manufacturer.as_deref(), Some("aarch64"));
        assert_eq!(name, Some("Neoverse-N1".to_string()));

        let machine = Machine {
            arch: Some("aarch64".to_string()),
            manufacturer,
            name: name.unwrap(),
            logical: 16,
            physical_cores: Some(16),
        };
        // The firmware describes the whole machine as one processor, where an x86 one describes
        // one per socket, and knows so little of it that the identification bytes are all zeroes.
        let processors = [Processor {
            family_name: Some("Other".to_string()),
            id: Some("00 00 00 00 00 00 00 00".to_string()),
        }];
        let cpus = assemble(&machine, &Socket::parse(ARM_CPUINFO), &processors);
        assert_eq!(cpus.len(), 1, "one entry for the machine");
        assert_eq!(cpus[0].name, "Neoverse-N1");
        assert_eq!(cpus[0].manufacturer.as_deref(), Some("ARM"));
        assert_eq!(cpus[0].arch.as_deref(), Some("aarch64"));
        assert_eq!(cpus[0].thread, Some(16));
        assert_eq!(cpus[0].core, Some(16), "no simultaneous multithreading");
        // The x86 notions the kernel does not fill in.
        assert_eq!(cpus[0].family, None);
        assert_eq!(cpus[0].model, None);
        assert_eq!(cpus[0].stepping, None);
        // What the firmware says of it, which is next to nothing: `Other` is a family name both
        // agents report, and the identification bytes are all zeroes.
        assert_eq!(cpus[0].family_name.as_deref(), Some("Other"));
        assert_eq!(cpus[0].id.as_deref(), Some("00 00 00 00 00 00 00 00"));
    }

    #[test]
    fn it_reports_no_socket_from_a_broken_cpuinfo() {
        assert!(Socket::parse("").is_empty());
        assert!(Socket::parse("no colon here\n").is_empty());
        // A socket whose values are not numbers is still a socket.
        let socket = &Socket::parse("physical id\t: 0\ncpu cores\t: many\n")[0];
        assert_eq!(socket.core, None);
    }

    /// The `/proc/cpuinfo` of a bare metal laptop, cut after the second logical CPU: one socket
    /// of eight cores and sixteen threads, which is what a machine with simultaneous
    /// multithreading looks like. Every block names the same `physical id`, and `core id` repeats
    /// once per core.
    ///
    /// The `flags` and `bugs` lists are cut to their first few entries, nothing reads them. The
    /// rest is verbatim.
    const BARE_METAL_CPUINFO: &str = "processor\t: 0
vendor_id\t: AuthenticAMD
cpu family\t: 25
model\t\t: 117
model name\t: AMD Ryzen 7 PRO 8840U w/ Radeon 780M Graphics
stepping\t: 2
microcode\t: 0xa70520a
cpu MHz\t\t: 4989.525
cache size\t: 1024 KB
physical id\t: 0
siblings\t: 16
core id\t\t: 0
cpu cores\t: 8
apicid\t\t: 0
initial apicid\t: 0
fpu\t\t: yes
fpu_exception\t: yes
cpuid level\t: 16
wp\t\t: yes
flags\t\t: fpu vme de pse tsc msr pae mce cx8 apic sep mtrr pge mca cmov pat pse36 clflush mmx fxsr sse sse2 ht syscall nx mmxext fxsr_opt pdpe1gb rdtscp lm constant_tsc
bugs\t\t: sysret_ss_attrs spectre_v1 spectre_v2 spec_store_bypass srso spectre_v2_user
bogomips\t: 6587.20
TLB size\t: 3584 4K pages
clflush size\t: 64
cache_alignment\t: 64
address sizes\t: 48 bits physical, 48 bits virtual
power management: ts ttp tm hwpstate cpb eff_freq_ro [13] [14] [15]

processor\t: 1
vendor_id\t: AuthenticAMD
cpu family\t: 25
model\t\t: 117
model name\t: AMD Ryzen 7 PRO 8840U w/ Radeon 780M Graphics
stepping\t: 2
microcode\t: 0xa70520a
cpu MHz\t\t: 3876.891
cache size\t: 1024 KB
physical id\t: 0
siblings\t: 16
core id\t\t: 0
cpu cores\t: 8
apicid\t\t: 1
initial apicid\t: 1
fpu\t\t: yes
fpu_exception\t: yes
cpuid level\t: 16
wp\t\t: yes
flags\t\t: fpu vme de pse tsc msr pae mce cx8 apic sep mtrr pge mca cmov pat pse36 clflush mmx fxsr sse sse2 ht syscall nx mmxext fxsr_opt pdpe1gb rdtscp lm constant_tsc
bugs\t\t: sysret_ss_attrs spectre_v1 spectre_v2 spec_store_bypass srso spectre_v2_user
bogomips\t: 6587.20
TLB size\t: 3584 4K pages
clflush size\t: 64
cache_alignment\t: 64
address sizes\t: 48 bits physical, 48 bits virtual
power management: ts ttp tm hwpstate cpb eff_freq_ro [13] [14] [15]
";

    /// The same machine end to end: one socket, its own counts, and what the firmware adds.
    #[test]
    fn it_reports_a_bare_metal_machine_as_one_processor() {
        let machine = Machine {
            arch: Some("x86_64".to_string()),
            manufacturer: manufacturer("AuthenticAMD"),
            name: "AMD Ryzen 7 PRO 8840U w/ Radeon 780M Graphics".to_string(),
            logical: 16,
            physical_cores: Some(8),
        };
        let sockets = Socket::parse(BARE_METAL_CPUINFO);
        assert_eq!(
            sockets.len(),
            1,
            "one socket, whatever the number of blocks"
        );
        let processors = [Processor {
            family_name: Some("Zen".to_string()),
            id: Some("52 0F A7 00 FF FB 8B 17".to_string()),
        }];
        let cpus = assemble(&machine, &sockets, &processors);
        assert_eq!(cpus.len(), 1);
        assert_eq!(cpus[0].manufacturer.as_deref(), Some("AMD"));
        // The counts of the socket, not of the machine: eight cores running sixteen threads.
        assert_eq!(cpus[0].core, Some(8));
        assert_eq!(cpus[0].thread, Some(16));
        // What the kernel says of the processor, `model` never taken from `model name`.
        assert_eq!(cpus[0].family, Some(25));
        assert_eq!(cpus[0].model, Some(117));
        assert_eq!(cpus[0].stepping, Some(2));
        // And what only the firmware knows.
        assert_eq!(cpus[0].family_name.as_deref(), Some("Zen"));
        assert_eq!(cpus[0].id.as_deref(), Some("52 0F A7 00 FF FB 8B 17"));
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

    /// The seven identifiers are the `%manufacturers` of `getCanonicalManufacturer` in
    /// `Tools.pm`, so that a machine inventoried by either agent keeps the same manufacturer.
    #[test]
    fn it_maps_a_vendor_identifier_as_fusion_inventory_does() {
        for (vendor_id, name) in [
            ("GenuineIntel", "Intel"),
            ("AuthenticAMD", "AMD"),
            ("HygonGenuine", "Hygon"),
            ("CentaurHauls", "VIA"),
            ("CyrixInstead", "Cyrix"),
            ("TMx86", "Transmeta"),
            ("TransmetaCPU", "Transmeta"),
        ] {
            assert_eq!(
                manufacturer(vendor_id),
                Some(name.to_string()),
                "{vendor_id}"
            );
        }
        // An identifier neither agent knows is reported as the kernel names it.
        assert_eq!(
            manufacturer("SomeNewVendor"),
            Some("SomeNewVendor".to_string())
        );
        // What an ARM kernel reports, where an empty element would be worse than none.
        assert_eq!(manufacturer(""), None);
        assert_eq!(manufacturer("  "), None);
    }

    #[test]
    fn it_reports_the_processors_of_this_machine() {
        let mut sys = System::new();
        sys.refresh_cpu_all();
        // The tables are only readable by root, and the entries are the sockets of the kernel
        // whether we could read them or not.
        let cpus = inventory(&sys, Dmi::read().as_ref());
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
            family_name: Some("Other".to_string()),
            family: Some(25),
            id: Some("52 0F A7 00 FF FB 8B 07".to_string()),
            manufacturer: Some("AMD".to_string()),
            model: Some(117),
            name: "AMD Ryzen 7 PRO 8840U".to_string(),
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
                "  <FAMILYNAME>Other</FAMILYNAME>\n",
                "  <FAMILYNUMBER>25</FAMILYNUMBER>\n",
                "  <ID>52 0F A7 00 FF FB 8B 07</ID>\n",
                "  <MANUFACTURER>AMD</MANUFACTURER>\n",
                "  <MODEL>117</MODEL>\n",
                "  <NAME>AMD Ryzen 7 PRO 8840U</NAME>\n",
                "  <STEPPING>2</STEPPING>\n",
                "  <THREAD>10</THREAD>\n",
                "</CPUS>",
            )
        );
    }

    /// What `sysinfo` answers on a two socket Xeon machine.
    fn machine() -> Machine {
        Machine {
            arch: Some("x86_64".to_string()),
            manufacturer: Some("Intel".to_string()),
            name: "Intel(R) Xeon(R) CPU E5-2623 v4 @ 2.60GHz".to_string(),
            logical: 16,
            physical_cores: Some(8),
        }
    }

    fn socket(family: u32) -> Socket {
        Socket {
            core: Some(4),
            thread: Some(8),
            family: Some(family),
            model: Some(79),
            stepping: Some(1),
        }
    }

    #[test]
    fn it_pairs_each_socket_with_the_processor_of_the_same_rank() {
        let sockets = [socket(6), socket(15)];
        let processors = [
            Processor {
                family_name: Some("Xeon".to_string()),
                id: Some("F1 06 04 00 FF FB EB BF".to_string()),
            },
            Processor {
                family_name: Some("Core i7".to_string()),
                id: Some("52 0F A7 00 FF FB 8B 07".to_string()),
            },
        ];
        let cpus = assemble(&machine(), &sockets, &processors);
        assert_eq!(cpus.len(), 2);
        assert_eq!(cpus[0].family, Some(6));
        assert_eq!(cpus[0].id, processors[0].id);
        assert_eq!(cpus[0].family_name, processors[0].family_name);
        assert_eq!(cpus[1].family, Some(15));
        assert_eq!(cpus[1].id, processors[1].id);
        assert_eq!(cpus[1].family_name, processors[1].family_name);
        // The counts are the socket's, and what describes the machine is on every entry.
        for cpu in &cpus {
            assert_eq!(cpu.core, Some(4));
            assert_eq!(cpu.thread, Some(8));
            assert_eq!(cpu.arch.as_deref(), Some("x86_64"));
            assert_eq!(cpu.name, machine().name);
        }
    }

    /// The tables may not be readable, or may describe fewer processors than the kernel names
    /// sockets. A socket the firmware says nothing about must report nothing, never the
    /// identification bytes of another processor.
    #[test]
    fn it_lends_no_processor_values_to_a_socket_of_its_own() {
        let sockets = [socket(6), socket(6)];
        let processors = [Processor {
            family_name: Some("Xeon".to_string()),
            id: Some("F1 06 04 00 FF FB EB BF".to_string()),
        }];
        let cpus = assemble(&machine(), &sockets, &processors);
        assert_eq!(cpus.len(), 2, "a socket was dropped with its counts");
        assert_eq!(cpus[0].id, processors[0].id);
        assert_eq!(cpus[1].id, None, "the second socket borrowed a CPUID");
        assert_eq!(cpus[1].family_name, None);
        // What the kernel says of it is reported all the same.
        assert_eq!(cpus[1].family, Some(6));
        assert_eq!(cpus[1].core, Some(4));

        // Nothing at all from the firmware, which is every run as anyone but root.
        let cpus = assemble(&machine(), &sockets, &[]);
        assert_eq!(cpus.len(), 2);
        assert!(
            cpus.iter()
                .all(|c| c.id.is_none() && c.family_name.is_none())
        );
    }

    /// An ARM kernel names no socket, and the machine is then reported as one processor holding
    /// its total counts, rather than as no processor at all.
    #[test]
    fn it_reports_the_machine_as_one_processor_without_a_socket() {
        let processors = [Processor {
            family_name: Some("Other".to_string()),
            id: Some("52 0F A7 00 FF FB 8B 07".to_string()),
        }];
        let cpus = assemble(&machine(), &[], &processors);
        assert_eq!(cpus.len(), 1);
        assert_eq!(
            cpus[0].thread,
            Some(16),
            "not the logical CPUs of the machine"
        );
        assert_eq!(
            cpus[0].core,
            Some(8),
            "not the physical cores of the machine"
        );
        // The one processor the firmware describes is the one entry we have.
        assert_eq!(cpus[0].id, processors[0].id);
        // The kernel named no socket, so it says nothing of the processor either.
        assert_eq!(cpus[0].family, None);
        assert_eq!(cpus[0].model, None);
        assert_eq!(cpus[0].stepping, None);
    }

    /// The server drops an entry without a name, and with it the counts and the architecture,
    /// so we report no processor rather than one it will throw away.
    #[test]
    fn it_reads_no_machine_without_a_processor() {
        // A `System` whose CPUs were never refreshed, which knows of no processor.
        assert_eq!(Machine::read(&System::new()), None);
        assert!(inventory(&System::new(), None).is_empty());
    }

    /// Every element but the name is optional, and an element we have no value for is left out
    /// rather than serialized empty.
    #[test]
    fn it_serializes_a_cpu_it_knows_nothing_else_about() {
        let cpu = Cpu {
            arch: None,
            core: None,
            family_name: None,
            family: None,
            id: None,
            manufacturer: None,
            model: None,
            name: "AMD Ryzen 7 PRO 8840U".to_string(),
            stepping: None,
            thread: None,
        };
        let mut out = String::new();
        let mut ser = Serializer::with_root(&mut out, Some("CPUS")).unwrap();
        ser.indent(' ', 2);
        cpu.serialize(ser).unwrap();
        assert_eq!(
            out,
            concat!(
                "<CPUS>\n",
                "  <NAME>AMD Ryzen 7 PRO 8840U</NAME>\n",
                "</CPUS>",
            )
        );
    }
}
