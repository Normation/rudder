// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

//! Network interfaces, as the `NETWORKS` section.
//!
//! Interface names, hardware addresses and IP addresses come from `sysinfo`. The interface
//! type, its status, its speed and its gateway do not, and are read from `/sys/class/net` and
//! `/proc/net/route`, the same sources FusionInventory uses on Linux. Those are why this
//! section is only produced on Linux for now.
//!
//! Like FusionInventory, we produce one entry per address rather than per interface, so an
//! interface with both an IPv4 and an IPv6 address appears twice.

use std::{
    collections::HashMap,
    fs,
    net::{IpAddr, Ipv4Addr, Ipv6Addr},
    path::Path,
    str::FromStr,
};

use serde::Serialize;
use sysinfo::{IpNetwork, Networks};
use tracing::{debug, instrument, warn};

/// Where the kernel exposes the interface attributes `sysinfo` does not provide.
const SYS_CLASS_NET: &str = "/sys/class/net";

/// The IPv4 routing table, where we look for the gateway of each interface.
const PROC_NET_ROUTE: &str = "/proc/net/route";

/// `ARPHRD_LOOPBACK`, the value of `/sys/class/net/<interface>/type` for a loopback.
const ARPHRD_LOOPBACK: u32 = 772;

/// `IFF_UP`, the flag telling an interface was brought up, in `/sys/class/net/<if>/flags`.
const IFF_UP: u32 = 0x1;

/// Fields are declared in the order FusionInventory serializes them, to keep both outputs
/// easy to compare.
///
/// `VIRTUALDEV`, `DRIVER`, `PCISLOT` and the wifi details are not produced, as nothing on the
/// server reads them.
#[derive(Debug, PartialEq, Serialize)]
#[serde(rename_all = "UPPERCASE")]
pub struct Network {
    /// The interface name. The server identifies the entry by it, and drops an entry without
    /// one.
    description: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    ipaddress: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    ipaddress6: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    ipgateway: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    ipmask: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    ipmask6: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    ipsubnet: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    ipsubnet6: Option<String>,
    macaddr: String,
    /// In megabits per second.
    #[serde(skip_serializing_if = "Option::is_none")]
    speed: Option<u64>,
    status: &'static str,
    #[serde(rename = "TYPE", skip_serializing_if = "Option::is_none")]
    kind: Option<&'static str>,
}

/// The interfaces of the machine, one entry per address, in a stable order.
#[instrument(level = "debug", name = "networks")]
pub fn inventory() -> Vec<Network> {
    let gateways = gateways(Path::new(PROC_NET_ROUTE));
    let interfaces = Networks::new_with_refreshed_list();
    let mut res = vec![];
    for (name, data) in interfaces.list() {
        let attributes = Attributes::read(&Path::new(SYS_CLASS_NET).join(name));
        let mac = data.mac_address().to_string();
        for ip in data.ip_networks() {
            res.push(Network::new(
                name,
                &mac,
                &attributes,
                gateways.get(name),
                ip,
            ));
        }
    }
    // `sysinfo` hands us the interfaces in an arbitrary order, and we want two inventories of
    // an unchanged machine to be identical.
    res.sort_by(|a, b| {
        (&a.description, &a.ipaddress, &a.ipaddress6).cmp(&(
            &b.description,
            &b.ipaddress,
            &b.ipaddress6,
        ))
    });
    debug!("Found {} interface addresses", res.len());
    res
}

impl Network {
    fn new(
        name: &str,
        mac: &str,
        attributes: &Attributes,
        gateway: Option<&Ipv4Addr>,
        ip: &IpNetwork,
    ) -> Self {
        let is_v4 = ip.addr.is_ipv4();
        let address = Some(ip.addr.to_string());
        let mask = mask(ip.addr, ip.prefix).map(|m| m.to_string());
        let subnet = subnet(ip.addr, ip.prefix).map(|s| s.to_string());
        Self {
            description: name.to_string(),
            ipaddress: is_v4.then(|| address.clone()).flatten(),
            ipaddress6: (!is_v4).then_some(address).flatten(),
            // We only read the IPv4 routing table, so there is never an IPv6 gateway.
            ipgateway: if is_v4 {
                gateway.map(|g| g.to_string())
            } else {
                None
            },
            ipmask: if is_v4 { mask.clone() } else { None },
            ipmask6: if is_v4 { None } else { mask },
            ipsubnet: if is_v4 { subnet.clone() } else { None },
            ipsubnet6: if is_v4 { None } else { subnet },
            macaddr: mac.to_string(),
            speed: attributes.speed,
            status: attributes.status,
            kind: attributes.kind,
        }
    }
}

/// The netmask of a prefix length, as an address of the same family as the one given.
fn mask(addr: IpAddr, prefix: u8) -> Option<IpAddr> {
    Some(match addr {
        IpAddr::V4(_) => IpAddr::V4(Ipv4Addr::from(leading_ones_32(prefix)?)),
        IpAddr::V6(_) => IpAddr::V6(Ipv6Addr::from(leading_ones_128(prefix)?)),
    })
}

/// The address of the network an address belongs to, its host part cleared.
fn subnet(addr: IpAddr, prefix: u8) -> Option<IpAddr> {
    Some(match addr {
        IpAddr::V4(a) => IpAddr::V4(Ipv4Addr::from(a.to_bits() & leading_ones_32(prefix)?)),
        IpAddr::V6(a) => IpAddr::V6(Ipv6Addr::from(a.to_bits() & leading_ones_128(prefix)?)),
    })
}

/// A 32 bit mask of `prefix` leading ones, or nothing if the prefix cannot be one.
fn leading_ones_32(prefix: u8) -> Option<u32> {
    if prefix > 32 {
        return None;
    }
    // A shift of the full width is undefined, and means every bit is a host bit.
    Some(u32::MAX.checked_shl(u32::from(32 - prefix)).unwrap_or(0))
}

/// A 128 bit mask of `prefix` leading ones, or nothing if the prefix cannot be one.
fn leading_ones_128(prefix: u8) -> Option<u128> {
    if prefix > 128 {
        return None;
    }
    Some(u128::MAX.checked_shl(u32::from(128 - prefix)).unwrap_or(0))
}

/// The interface attributes `sysinfo` does not expose.
#[derive(Debug, PartialEq)]
struct Attributes {
    kind: Option<&'static str>,
    speed: Option<u64>,
    status: &'static str,
}

impl Attributes {
    fn read(dir: &Path) -> Self {
        let status = Self::status(dir);
        Self {
            kind: Self::kind(dir),
            // The kernel only knows the speed of an interface that is up.
            speed: (status == "Up").then(|| Self::speed(dir)).flatten(),
            status,
        }
    }

    /// Whether the interface was brought up, as the `Up` or `Down` the server expects.
    ///
    /// This is the `IFF_UP` flag, which is what FusionInventory reads out of the flag list
    /// `ip link` prints. It says the interface was configured up, not that it can currently
    /// pass packets: a bridge with no port, or an unplugged cable, is still `Up` here. The
    /// kernel operational state `sysinfo` reports answers that other question, but it is
    /// unknown for interfaces with no carrier at all, the loopback in particular, so it
    /// cannot be used to tell one from the other.
    ///
    /// An interface whose flags we cannot read is reported down, as FusionInventory does.
    fn status(dir: &Path) -> &'static str {
        // The flags are a hexadecimal mask, written as "0x1003".
        let flags = read_value::<String>(&dir.join("flags"))
            .and_then(|f| u32::from_str_radix(f.trim_start_matches("0x"), 16).ok())
            .unwrap_or(0);
        if flags & IFF_UP == 0 { "Down" } else { "Up" }
    }

    /// The kind of interface, using the same checks and the same values as FusionInventory.
    fn kind(dir: &Path) -> Option<&'static str> {
        if dir.join("wireless").is_dir() {
            return Some("wifi");
        }
        if dir.join("brif").is_dir() {
            return Some("bridge");
        }
        if dir.join("bonding").is_dir() {
            return Some("aggregate");
        }
        if read_value::<u32>(&dir.join("type")) == Some(ARPHRD_LOOPBACK) {
            return Some("loopback");
        }
        // A device behind the interface means it is a real one, and we assume ethernet as
        // the wireless cases are handled above.
        dir.join("device").exists().then_some("ethernet")
    }

    /// In megabits per second. Drivers that do not know report `-1`, in which case, and for
    /// the interfaces that have no speed at all, we report nothing.
    fn speed(dir: &Path) -> Option<u64> {
        read_value::<i64>(&dir.join("speed"))
            .filter(|speed| *speed > 0)
            .map(|speed| speed.unsigned_abs())
    }
}

/// Reads a single value out of one of the one-line files of `/sys`.
fn read_value<T: FromStr>(path: &Path) -> Option<T> {
    fs::read_to_string(path).ok()?.trim().parse().ok()
}

/// The IPv4 gateway of each interface that has one.
fn gateways(path: &Path) -> HashMap<String, Ipv4Addr> {
    match fs::read_to_string(path) {
        Ok(table) => parse_gateways(&table),
        Err(e) => {
            warn!("Could not read the routing table '{}': {e}", path.display());
            HashMap::new()
        }
    }
}

/// Reads the gateways out of the kernel IPv4 routing table.
///
/// Like FusionInventory, we take the gateway of an interface to be the one of its default
/// route. Addresses in this table are hexadecimal, in the byte order of the host.
fn parse_gateways(table: &str) -> HashMap<String, Ipv4Addr> {
    let mut res = HashMap::new();
    // The first line names the columns.
    for line in table.lines().skip(1) {
        let mut fields = line.split_whitespace();
        let (Some(interface), Some(destination), Some(gateway)) =
            (fields.next(), fields.next(), fields.next())
        else {
            continue;
        };
        // Only the default route tells us the gateway of the interface.
        if u32::from_str_radix(destination, 16) != Ok(0) {
            continue;
        }
        let Ok(gateway) = u32::from_str_radix(gateway, 16) else {
            continue;
        };
        if gateway == 0 {
            continue;
        }
        res.insert(
            interface.to_string(),
            Ipv4Addr::from(if cfg!(target_endian = "little") {
                gateway.swap_bytes()
            } else {
                gateway
            }),
        );
    }
    res
}

#[cfg(test)]
mod tests {
    use pretty_assertions::assert_eq;
    use tempfile::tempdir;

    use super::*;

    fn v4(s: &str) -> IpAddr {
        IpAddr::V4(s.parse().unwrap())
    }

    fn v6(s: &str) -> IpAddr {
        IpAddr::V6(s.parse().unwrap())
    }

    #[test]
    fn it_computes_ipv4_masks_and_subnets() {
        assert_eq!(mask(v4("192.168.217.26"), 25), Some(v4("255.255.255.128")));
        assert_eq!(subnet(v4("192.168.217.26"), 25), Some(v4("192.168.217.0")));
        assert_eq!(mask(v4("127.0.0.1"), 8), Some(v4("255.0.0.0")));
        assert_eq!(subnet(v4("127.0.0.1"), 8), Some(v4("127.0.0.0")));
        // A single host, and the whole space.
        assert_eq!(mask(v4("10.0.0.1"), 32), Some(v4("255.255.255.255")));
        assert_eq!(subnet(v4("10.0.0.1"), 32), Some(v4("10.0.0.1")));
        assert_eq!(mask(v4("10.0.0.1"), 0), Some(v4("0.0.0.0")));
        assert_eq!(subnet(v4("10.0.0.1"), 0), Some(v4("0.0.0.0")));
        // Not a prefix length.
        assert_eq!(mask(v4("10.0.0.1"), 33), None);
    }

    #[test]
    fn it_computes_ipv6_masks_and_subnets() {
        assert_eq!(
            mask(v6("fe80::5054:ff:fe50:366d"), 64),
            Some(v6("ffff:ffff:ffff:ffff::"))
        );
        assert_eq!(
            subnet(v6("fe80::5054:ff:fe50:366d"), 64),
            Some(v6("fe80::"))
        );
        // FusionInventory gets this one wrong, reporting a mask of 'fff0::' and a subnet
        // of '::' for the loopback.
        assert_eq!(
            mask(v6("::1"), 128),
            Some(v6("ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff"))
        );
        assert_eq!(subnet(v6("::1"), 128), Some(v6("::1")));
        assert_eq!(mask(v6("::1"), 0), Some(v6("::")));
        assert_eq!(mask(v6("::1"), 129), None);
    }

    #[test]
    fn it_parses_the_routing_table() {
        // Real content, whose trailing spaces we keep.
        let table = "Iface\tDestination\tGateway \tFlags\tRefCnt\tUse\tMetric\tMask\t\tMTU\tWindow\tIRTT                   \n\
             enp2s0\t00000000\t017AA8C0\t0003\t0\t0\t100\t00000000\t0\t0\t0                     \n\
             docker0\t000011AC\t00000000\t0001\t0\t0\t0\t0000FFFF\t0\t0\t0                      \n\
             enp2s0\t007AA8C0\t00000000\t0001\t0\t0\t100\t00FFFFFF\t0\t0\t0                     \n";
        let gateways = parse_gateways(table);
        assert_eq!(gateways.len(), 1);
        assert_eq!(
            gateways.get("enp2s0"),
            Some(&Ipv4Addr::new(192, 168, 122, 1))
        );
        // Only has a route to its own network, without a gateway.
        assert_eq!(gateways.get("docker0"), None);
    }

    /// Reads the routing table of the machine we run on, which anyone may read.
    #[test]
    fn it_reads_the_routing_table_of_this_machine() {
        let gateways = gateways(Path::new(PROC_NET_ROUTE));
        let interfaces: Vec<String> = inventory().into_iter().map(|n| n.description).collect();
        for (interface, gateway) in &gateways {
            assert!(
                interfaces.contains(interface),
                "'{interface}' has a gateway but no address"
            );
            assert!(!gateway.is_unspecified(), "a gateway of 0.0.0.0");
        }
    }

    #[test]
    fn it_parses_no_gateway_from_a_broken_routing_table() {
        assert_eq!(parse_gateways(""), HashMap::new());
        assert_eq!(parse_gateways("Iface\tDestination\n"), HashMap::new());
        assert_eq!(parse_gateways("Iface\nenp2s0\tzz\tzz\n"), HashMap::new());
    }

    #[test]
    fn it_reads_the_interface_kind() {
        let dir = tempdir().unwrap();
        // Nothing tells us what this interface is.
        assert_eq!(Attributes::kind(dir.path()), None);

        fs::write(dir.path().join("type"), "772\n").unwrap();
        assert_eq!(Attributes::kind(dir.path()), Some("loopback"));

        fs::write(dir.path().join("type"), "1\n").unwrap();
        assert_eq!(Attributes::kind(dir.path()), None);
        fs::create_dir(dir.path().join("device")).unwrap();
        assert_eq!(Attributes::kind(dir.path()), Some("ethernet"));

        // A more specific answer wins over the device.
        fs::create_dir(dir.path().join("bonding")).unwrap();
        assert_eq!(Attributes::kind(dir.path()), Some("aggregate"));
        fs::create_dir(dir.path().join("wireless")).unwrap();
        assert_eq!(Attributes::kind(dir.path()), Some("wifi"));
    }

    #[test]
    fn it_reads_the_interface_speed_only_when_it_is_up() {
        let dir = tempdir().unwrap();
        fs::write(dir.path().join("speed"), "1000\n").unwrap();
        fs::write(dir.path().join("flags"), "0x1003\n").unwrap();
        assert_eq!(Attributes::read(dir.path()).speed, Some(1000));
        fs::write(dir.path().join("flags"), "0x1002\n").unwrap();
        assert_eq!(Attributes::read(dir.path()).speed, None);
        // Drivers that do not know, virtio in particular, report -1.
        fs::write(dir.path().join("flags"), "0x1003\n").unwrap();
        fs::write(dir.path().join("speed"), "-1\n").unwrap();
        assert_eq!(Attributes::read(dir.path()).speed, None);
    }

    #[test]
    fn it_reads_the_status_from_the_interface_flags() {
        let dir = tempdir().unwrap();
        // Nothing to read: down, as FusionInventory defaults to.
        assert_eq!(Attributes::status(dir.path()), "Down");

        // A running interface.
        fs::write(dir.path().join("flags"), "0x1003\n").unwrap();
        assert_eq!(Attributes::status(dir.path()), "Up");
        // The loopback, whose operational state the kernel reports as unknown.
        fs::write(dir.path().join("flags"), "0x9\n").unwrap();
        assert_eq!(Attributes::status(dir.path()), "Up");
        // Same flags as a running interface, minus IFF_UP.
        fs::write(dir.path().join("flags"), "0x1002\n").unwrap();
        assert_eq!(Attributes::status(dir.path()), "Down");
        // Not a mask.
        fs::write(dir.path().join("flags"), "not hexadecimal\n").unwrap();
        assert_eq!(Attributes::status(dir.path()), "Down");
    }

    #[test]
    fn it_builds_an_ipv4_entry() {
        let attributes = Attributes {
            kind: Some("ethernet"),
            speed: Some(1000),
            status: "Up",
        };
        let ip = IpNetwork {
            addr: v4("192.168.122.10"),
            prefix: 24,
        };
        let gateway = Ipv4Addr::new(192, 168, 122, 1);
        assert_eq!(
            Network::new(
                "enp2s0",
                "52:54:00:aa:bb:cc",
                &attributes,
                Some(&gateway),
                &ip
            ),
            Network {
                description: "enp2s0".to_string(),
                ipaddress: Some("192.168.122.10".to_string()),
                ipaddress6: None,
                ipgateway: Some("192.168.122.1".to_string()),
                ipmask: Some("255.255.255.0".to_string()),
                ipmask6: None,
                ipsubnet: Some("192.168.122.0".to_string()),
                ipsubnet6: None,
                macaddr: "52:54:00:aa:bb:cc".to_string(),
                speed: Some(1000),
                status: "Up",
                kind: Some("ethernet"),
            }
        );
    }

    #[test]
    fn it_builds_an_ipv6_entry_without_a_gateway() {
        let attributes = Attributes {
            kind: Some("ethernet"),
            speed: None,
            status: "Up",
        };
        let ip = IpNetwork {
            addr: v6("fe80::5054:ff:feaa:bbcc"),
            prefix: 64,
        };
        let gateway = Ipv4Addr::new(192, 168, 122, 1);
        // The IPv4 gateway must not leak into the IPv6 entry.
        assert_eq!(
            Network::new(
                "enp2s0",
                "52:54:00:aa:bb:cc",
                &attributes,
                Some(&gateway),
                &ip
            ),
            Network {
                description: "enp2s0".to_string(),
                ipaddress: None,
                ipaddress6: Some("fe80::5054:ff:feaa:bbcc".to_string()),
                ipgateway: None,
                ipmask: None,
                ipmask6: Some("ffff:ffff:ffff:ffff::".to_string()),
                ipsubnet: None,
                ipsubnet6: Some("fe80::".to_string()),
                macaddr: "52:54:00:aa:bb:cc".to_string(),
                speed: None,
                status: "Up",
                kind: Some("ethernet"),
            }
        );
    }

    #[test]
    fn it_inventories_the_local_interfaces() {
        let interfaces = inventory();
        assert!(!interfaces.is_empty(), "no interface found");
        for interface in &interfaces {
            assert!(!interface.description.is_empty());
            // Exactly one address family per entry.
            assert!(interface.ipaddress.is_some() != interface.ipaddress6.is_some());
            assert_eq!(interface.ipmask.is_some(), interface.ipaddress.is_some());
            assert_eq!(interface.ipmask6.is_some(), interface.ipaddress6.is_some());
            assert!(interface.ipgateway.is_none() || interface.ipaddress.is_some());
        }
    }
}
