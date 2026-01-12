// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2025 Normation SAS

//! Checks for IP ranges.
//!
//! Support both IPv4 and IPv6 ranges.

use anyhow::{Result, bail};
use ipnet::{Ipv4Net, Ipv6Net};
use iprange::{IpRange, ToNetwork};
use std::{fmt::Display, net::IpAddr};

pub struct IpRangeChecker {
    ipv4: IpRange<Ipv4Net>,
    ipv6: IpRange<Ipv6Net>,
}

impl TryFrom<&Vec<String>> for IpRangeChecker {
    type Error = anyhow::Error;

    fn try_from(ranges: &Vec<String>) -> Result<Self> {
        let mut checker = IpRangeChecker::new();
        for range in ranges {
            checker.add_range(range)?;
        }
        Ok(checker)
    }
}

impl IpRangeChecker {
    pub fn new() -> Self {
        Self {
            ipv4: IpRange::new(),
            ipv6: IpRange::new(),
        }
    }

    pub(crate) fn is_ipv6(range: &str) -> bool {
        range.contains(':')
    }

    fn is_range(range: &str) -> bool {
        range.contains('/')
    }

    pub fn add_range(&mut self, range: &str) -> Result<()> {
        if Self::is_ipv6(range) {
            self.add_ipv6(range)
        } else {
            self.add_ipv4(range)
        }
    }

    fn add_ipv4(&mut self, range: &str) -> Result<()> {
        let range = range.parse()?;
        self.ipv4.add(range);
        Ok(())
    }

    fn add_ipv6(&mut self, range: &str) -> Result<()> {
        let range: Ipv6Net = range.parse()?;
        self.ipv6.add(range);
        Ok(())
    }

    fn check_ipv4(&self, range: impl ToNetwork<Ipv4Net>) -> Result<bool> {
        Ok(self.ipv4.contains(&range.to_network()))
    }

    fn check_ipv6(&self, range: impl ToNetwork<Ipv6Net>) -> Result<bool> {
        Ok(self.ipv6.contains(&range.to_network()))
    }

    /// Check if the given IP or IP range is in the configured ranges.
    pub fn is_in_range(&self, ip: &str) -> Result<bool> {
        if Self::is_range(ip) {
            if Self::is_ipv6(ip) {
                let ip: Ipv6Net = ip.parse()?;
                self.check_ipv6(ip)
            } else {
                let ip: Ipv4Net = ip.parse()?;
                self.check_ipv4(ip)
            }
        } else {
            let ip: IpAddr = ip.parse()?;
            match ip {
                IpAddr::V4(ip) => self.check_ipv4(ip),
                IpAddr::V6(ip) => self.check_ipv6(ip),
            }
        }
    }

    /// Same as `is_in_range` but returns an error if the IP is not in the range.
    pub fn check_range(&self, ip: &str) -> Result<()> {
        if self.is_in_range(ip)? {
            Ok(())
        } else {
            bail!("IP '{ip}' is not in the configured ranges ({self})")
        }
    }
}

impl Display for IpRangeChecker {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let ranges = self
            .ipv4
            .iter()
            .map(|r| r.to_string())
            .chain(self.ipv6.iter().map(|r| r.to_string()))
            .collect::<Vec<String>>();
        // the order is stable (BFS of the tree)
        write!(f, "{}", ranges.join(", "),)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::IpAddr;

    fn it_converts_from_vec() {
        let ranges = vec![
            "192.168.1.1/24".to_string(),
            "192.168.42.1/24".to_string(),
            "3fff::/20".to_string(),
        ];
        let checker = IpRangeChecker::try_from(&ranges).unwrap();
        assert_eq!(
            checker.to_string(),
            "192.168.1.0/24, 192.168.42.0/24, 3fff::/20"
        );
    }

    #[test]
    fn it_parses_ipv4_ranges() {
        let range = "192.168.10.0/24";
        let range: Ipv4Net = range.parse().unwrap();
        assert_eq!(range.addr(), "192.168.10.0".parse::<IpAddr>().unwrap());
        assert_eq!(range.netmask(), "255.255.255.0".parse::<IpAddr>().unwrap());
    }

    #[test]
    fn test_ipv4_range() {
        let mut checker = IpRangeChecker::new();
        checker.add_range("192.168.0.0/24").unwrap();
        assert!(checker.is_in_range("192.168.0.2/32").unwrap());
        assert!(checker.is_in_range("192.168.0.2/30").unwrap());
        assert!(checker.is_in_range("192.168.0.2").unwrap());
        assert!(checker.is_in_range("192.168.0.2/24").unwrap());

        assert!(!checker.is_in_range("192.168.0.2/16").unwrap());
        assert!(!checker.is_in_range("192.168.1.2/32").unwrap());

        assert!(checker.is_in_range("plop").is_err());
        assert!(checker.is_in_range("1:2").is_err());
        assert!(checker.is_in_range("1.2").is_err());
    }

    #[test]
    fn test_ipv6_range() {
        let mut checker = IpRangeChecker::new();
        checker.add_range("3fff::/20").unwrap();
        assert!(checker.is_in_range("3fff::42/128").unwrap());
        assert!(checker.is_in_range("3fff:42::/48").unwrap());
        assert!(checker.is_in_range("3fff::42").unwrap());
        assert!(checker.is_in_range("3fff::/20").unwrap());

        assert!(!checker.is_in_range("3ffe::/20").unwrap());
        assert!(!checker.is_in_range("3fff::/19").unwrap());

        assert!(checker.is_in_range("plop").is_err());
        assert!(checker.is_in_range("1:2").is_err());
        assert!(checker.is_in_range("1.2").is_err());
    }

    #[test]
    fn test_ip_range_display() {
        let mut checker = IpRangeChecker::new();
        checker.add_range("192.168.1.1/24").unwrap();
        checker.add_range("192.168.42.1/24").unwrap();
        checker.add_range("3fff::/20").unwrap();

        assert_eq!(
            checker.to_string(),
            "192.168.1.0/24, 192.168.42.0/24, 3fff::/20"
        );
    }
}
