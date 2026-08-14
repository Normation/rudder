// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

//! Mounted filesystems, as the `DRIVES` section.
//!
//! Everything comes from `sysinfo`, which leaves out most of the pseudo filesystems we do not
//! want to report, so this section works the same way on every platform. The ones it lets
//! through are dropped on their size, as `df` drops them.
//!
//! We do not report `SERIAL`, the filesystem identifier, as nothing on the server reads it.
//! That is what spares us the `blkid`, `dumpe2fs`, `xfs_db` and `dosfslabel` calls
//! FusionInventory needs to build it.

use std::time::Duration;

use serde::Serialize;
use sysinfo::{Disk, Disks};
use tracing::{debug, instrument, warn};

use crate::util::{empty_to_none, with_timeout};

/// How long we give the kernel to report the filesystems.
///
/// FusionInventory wraps its `df` call in a `timeout 10` for the same reason, so we use the
/// same budget.
const TIMEOUT: Duration = Duration::from_secs(10);

/// Fields are declared in the order FusionInventory serializes them, to keep both outputs
/// easy to compare.
#[derive(Debug, PartialEq, Serialize)]
#[serde(rename_all = "UPPERCASE")]
pub struct Drive {
    #[serde(skip_serializing_if = "Option::is_none")]
    filesystem: Option<String>,
    /// In megabytes, the unit the server assumes.
    free: u64,
    /// In megabytes.
    total: u64,
    /// The mount point. The server identifies the entry by it, and drops an entry without
    /// one.
    #[serde(rename = "TYPE")]
    mount_point: String,
    /// The device the filesystem lives on.
    #[serde(skip_serializing_if = "Option::is_none")]
    volumn: Option<String>,
}

/// The mounted filesystems, in mount point order.
///
/// Reports none of them, rather than holding the whole inventory up, when the kernel takes
/// too long to answer. `sysinfo` gives us no way to ask for the filesystems one at a time, so
/// a single unresponsive mount costs us the whole section.
#[instrument(level = "debug", name = "drives")]
pub fn inventory() -> Vec<Drive> {
    with_timeout(TIMEOUT, collect).unwrap_or_else(|| {
        warn!(
            "Giving up on the filesystem inventory, which took more than {}s. No filesystem \
             will be reported at all.",
            TIMEOUT.as_secs()
        );
        vec![]
    })
}

fn collect() -> Vec<Drive> {
    let disks = Disks::new_with_refreshed_list();
    let mut res: Vec<Drive> = disks.list().iter().filter_map(Drive::new).collect();
    // `sysinfo` hands us the filesystems in mount order, and we want two inventories of an
    // unchanged machine to be identical.
    res.sort_by(|a, b| a.mount_point.cmp(&b.mount_point));
    debug!("Found {} filesystems", res.len());
    res
}

impl Drive {
    /// Returns nothing for a filesystem we cannot name a mount point for, as the server drops
    /// such an entry, and nothing for one that holds no space at all.
    ///
    /// A filesystem of no size is a pseudo filesystem, and dropping it is what `df` does.
    fn new(disk: &Disk) -> Option<Self> {
        let mount_point = disk.mount_point().to_string_lossy().to_string();
        if mount_point.is_empty() || disk.total_space() == 0 {
            return None;
        }
        Some(Self {
            filesystem: empty_to_none(&disk.file_system().to_string_lossy()),
            free: megabytes(disk.available_space()),
            total: megabytes(disk.total_space()),
            mount_point,
            volumn: empty_to_none(&disk.name().to_string_lossy()),
        })
    }
}

/// Converts a number of bytes to the megabytes the server expects.
///
/// Unlike the other sizes we report, zero is a value of its own here, and not a size the
/// platform failed to answer: a filesystem with no space left has no free megabyte.
fn megabytes(bytes: u64) -> u64 {
    crate::util::megabytes(bytes).unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use pretty_assertions::assert_eq;
    use quick_xml::se::Serializer;

    use super::*;

    #[test]
    fn it_converts_bytes_to_megabytes() {
        assert_eq!(megabytes(4_076_863_488), 3888);
        assert_eq!(megabytes(1024 * 1024), 1);
        // A full filesystem, which is a value and not a missing one.
        assert_eq!(megabytes(0), 0);
        assert_eq!(megabytes(1), 0);
    }

    /// The serialized shape has to stay the one FusionInventory produces, field for field.
    #[test]
    fn it_serializes_a_drive_like_fusion_inventory() {
        let drive = Drive {
            filesystem: Some("ext4".to_string()),
            free: 241,
            total: 3888,
            mount_point: "/".to_string(),
            volumn: Some("/dev/vda1".to_string()),
        };
        let mut out = String::new();
        let mut ser = Serializer::with_root(&mut out, Some("DRIVES")).unwrap();
        ser.indent(' ', 2);
        drive.serialize(ser).unwrap();
        assert_eq!(
            out,
            concat!(
                "<DRIVES>\n",
                "  <FILESYSTEM>ext4</FILESYSTEM>\n",
                "  <FREE>241</FREE>\n",
                "  <TOTAL>3888</TOTAL>\n",
                "  <TYPE>/</TYPE>\n",
                "  <VOLUMN>/dev/vda1</VOLUMN>\n",
                "</DRIVES>",
            )
        );
    }

    #[test]
    fn it_inventories_the_mounted_filesystems() {
        let drives = inventory();
        assert!(!drives.is_empty(), "no filesystem found");
        for drive in &drives {
            assert!(!drive.mount_point.is_empty());
            // A filesystem cannot have more space left than it has.
            assert!(
                drive.free <= drive.total,
                "{} has {}MB free of {}MB",
                drive.mount_point,
                drive.free,
                drive.total
            );
        }
        // The root filesystem is always mounted, and pseudo filesystems are left out.
        assert!(drives.iter().any(|d| d.mount_point == "/"));
        // `proc` is one `sysinfo` knows, `nsfs` one it does not: a namespace mounted outside
        // `/sys`, `/proc` and `/run` reaches us, and is left out on its size instead.
        for pseudo in ["proc", "nsfs"] {
            assert!(
                !drives
                    .iter()
                    .any(|d| d.filesystem.as_deref() == Some(pseudo)),
                "the pseudo filesystem '{pseudo}' was reported"
            );
        }
    }

    /// Every device we report is one `df` reports, which is what makes both agents agree on
    /// which filesystems are real. Two directions do not hold, and neither is asserted:
    ///
    /// * `df` also lists the `tmpfs` mounts that both agents then drop by name.
    /// * `df` lists a device **once**, whatever number of mount points it has, where we list
    ///   each mount point. A container binds `/etc/hosts`, `/etc/hostname` and
    ///   `/etc/resolv.conf` from one device, and `df` shows only the first of the three.
    ///
    /// So the devices are compared and not the mount points. It still catches what this is here
    /// for, a pseudo filesystem of no size: `df` leaves those out altogether, device and all.
    #[test]
    #[cfg(target_os = "linux")]
    fn it_reports_no_filesystem_df_leaves_out() {
        let Ok(df) = crate::util::cmd("df", &["-P", "-T", "-k"]) else {
            // A machine without `df`, which we do not need but cannot compare against.
            return;
        };
        // The device is the first column, and the columns are padded, so they are split on
        // runs of whitespace.
        let devices: Vec<&str> = df
            .lines()
            .skip(1)
            .filter_map(|l| l.split_whitespace().next())
            .collect();
        assert!(!devices.is_empty(), "no filesystem parsed out of df:\n{df}");
        for drive in inventory() {
            let Some(volume) = &drive.volumn else {
                continue;
            };
            assert!(
                devices.contains(&volume.as_str()),
                "'{volume}', mounted on '{}', is not a device df reports:\n{df}",
                drive.mount_point
            );
        }
    }
}
