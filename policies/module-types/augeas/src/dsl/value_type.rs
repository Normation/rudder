// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use crate::dsl::ip::IpRangeChecker;
use anyhow::{anyhow, bail};
use bytesize::ByteSize;
use ipnet::{Ipv4Net, Ipv6Net};
use std::{
    fmt,
    fmt::Display,
    net::{IpAddr, Ipv4Addr, Ipv6Addr},
    str::FromStr,
};

#[derive(Debug, PartialEq, Clone)]
pub enum ValueType {
    /// A byte size, like 1MB or 1GB
    BytesSize,
    /// An IP address
    Ip,
    /// An IPv4 address
    Ipv4,
    /// An IPv6 address
    Ipv6,
    /// An IP range with a mask
    IpRange,
    /// An IPv4 range with a mask
    Ipv4Range,
    /// An IPv6 range with a mask
    Ipv6Range,
    /// An integer
    Int,
    /// A positive integer
    Uint,
    /// A floating point number
    Float,
    /// A boolean
    Bool,
}

impl FromStr for ValueType {
    type Err = anyhow::Error;

    fn from_str(s: &str) -> anyhow::Result<Self> {
        match s.to_lowercase().as_str() {
            "bytes" => Ok(Self::BytesSize),
            "ip" => Ok(Self::Ip),
            "ipv4" => Ok(Self::Ipv4),
            "ipv6" => Ok(Self::Ipv6),
            "ip_range" => Ok(Self::IpRange),
            "ipv4_range" => Ok(Self::Ipv4Range),
            "ipv6_range" => Ok(Self::Ipv6Range),
            "int" => Ok(Self::Int),
            "uint" => Ok(Self::Uint),
            "float" => Ok(Self::Float),
            "bool" => Ok(Self::Bool),
            _ => bail!("Invalid value type: {s}"),
        }
    }
}

impl Display for ValueType {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let s = match self {
            Self::BytesSize => "bytes",
            Self::Ip => "ip",
            Self::Ipv4 => "ipv4",
            Self::Ipv6 => "ipv6",
            Self::IpRange => "ip_range",
            Self::Ipv4Range => "ipv4_range",
            Self::Ipv6Range => "ipv6_range",
            Self::Int => "int",
            Self::Uint => "uint",
            Self::Float => "float",
            Self::Bool => "bool",
        };
        write!(f, "{s}")
    }
}

impl ValueType {
    /// Returns an error if the value is not valid.
    pub fn check(&self, value: &str) -> anyhow::Result<()> {
        match self {
            Self::BytesSize => {
                value
                    .parse::<ByteSize>()
                    .map_err(|e| anyhow!("Invalid byte size: {e}"))?;
            }
            Self::Ip => {
                value
                    .parse::<IpAddr>()
                    .map_err(|e| anyhow!("Invalid IP address: {e}"))?;
            }
            Self::Ipv4 => {
                value
                    .parse::<Ipv4Addr>()
                    .map_err(|e| anyhow!("Invalid IPv4 address: {e}"))?;
            }
            Self::Ipv6 => {
                value
                    .parse::<Ipv6Addr>()
                    .map_err(|e| anyhow!("Invalid IPv6 address: {e}"))?;
            }
            Self::IpRange => {
                if IpRangeChecker::is_ipv6(value) {
                    value
                        .parse::<Ipv6Net>()
                        .map_err(|e| anyhow!("Invalid IPv4 range: {e}"))?;
                } else {
                    value
                        .parse::<Ipv4Net>()
                        .map_err(|e| anyhow!("Invalid IPv6 range: {e}"))?;
                }
            }
            Self::Ipv4Range => {
                value
                    .parse::<Ipv4Net>()
                    .map_err(|e| anyhow!("Invalid IPv4 range: {e}"))?;
            }
            Self::Ipv6Range => {
                value
                    .parse::<Ipv6Net>()
                    .map_err(|e| anyhow!("Invalid IPv6 range: {e}"))?;
            }
            Self::Int => {
                value
                    .parse::<isize>()
                    .map_err(|_| anyhow!("Invalid integer: {value}"))?;
            }
            Self::Uint => {
                value
                    .parse::<usize>()
                    .map_err(|_| anyhow!("Invalid positive integer: {value}"))?;
            }
            Self::Float => {
                value
                    .parse::<f64>()
                    .map_err(|_| anyhow!("Invalid floating point number: {value}"))?;
            }
            Self::Bool => {
                value
                    .parse::<bool>()
                    .map_err(|_| anyhow!("Invalid boolean: {value}"))?;
            }
        }
        Ok(())
    }
}
