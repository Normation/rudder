// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023 Normation SAS

use core::fmt;
use std::{cmp::Ordering, fmt::Display, fs, str::FromStr};

use anyhow::{bail, Error, Result};
use log::debug;
use regex::Regex;
use serde::{de, Deserialize, Deserializer, Serialize, Serializer};

#[derive(PartialEq, Eq, Debug, Clone)]
pub struct ArchiveVersion {
    pub rudder_version: RudderVersion,
    pub plugin_version: PluginVersion,
}

impl Display for ArchiveVersion {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}-{}", self.rudder_version, self.plugin_version)
    }
}

impl FromStr for ArchiveVersion {
    type Err = Error;
    fn from_str(s: &str) -> std::result::Result<Self, Self::Err> {
        let split = match s.split_once('-') {
            None => bail!("Unparsable rpkg version '{}'", s),
            Some(c) => c,
        };
        let rudder_version = RudderVersion::from_str(split.0)?;
        let plugin_version = PluginVersion::from_str(split.1)?;
        Ok(Self {
            rudder_version,
            plugin_version,
        })
    }
}

#[derive(PartialEq, Eq, Debug, Clone)]
pub enum RudderVersionMode {
    Alpha { version: u32 },
    Beta { version: u32 },
    Rc { version: u32 },
    Final,
}

impl Display for RudderVersionMode {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match *self {
            RudderVersionMode::Final => write!(f, ""),
            RudderVersionMode::Alpha { version } => write!(f, "~alpha{}", version),
            RudderVersionMode::Beta { version } => write!(f, "~beta{}", version),
            RudderVersionMode::Rc { version } => write!(f, "~rc{}", version),
        }
    }
}

impl Serialize for ArchiveVersion {
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        let to_str = format!("{}-{}", self.rudder_version, self.plugin_version);
        serializer.collect_str(&to_str)
    }
}

impl<'de> Deserialize<'de> for ArchiveVersion {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        let string = String::deserialize(deserializer)?;
        string.parse().map_err(de::Error::custom)
    }
}

impl FromStr for RudderVersionMode {
    type Err = Error;
    fn from_str(s: &str) -> std::result::Result<Self, Self::Err> {
        // If the mode is empty, it is a "plain" release
        let alpha_regex = Regex::new(r"^~alpha(?<version>\d+).*")?;
        let beta_regex = Regex::new(r"^~beta(?<version>\d+).*")?;
        let rc_regex = Regex::new(r"^~rc(?<version>\d+).*")?;
        let git_regex = Regex::new(r"^~git(?<version>\d+)")?;
        if s.is_empty() {
            return Ok(RudderVersionMode::Final);
        }
        // Test if alpha
        match alpha_regex.captures(s) {
            None => (),
            Some(c) => {
                let version = c["version"].to_string().parse().unwrap();
                return Ok(RudderVersionMode::Alpha { version });
            }
        };
        // Test if beta
        match beta_regex.captures(s) {
            None => (),
            Some(c) => {
                let version = c["version"].to_string().parse().unwrap();
                return Ok(RudderVersionMode::Beta { version });
            }
        };
        // Test if rc
        match rc_regex.captures(s) {
            None => (),
            Some(c) => {
                let version = c["version"].to_string().parse().unwrap();
                return Ok(RudderVersionMode::Rc { version });
            }
        };
        // Test if git
        match git_regex.captures(s) {
            None => (),
            Some(_) => return Ok(RudderVersionMode::Final),
        };
        bail!("Unparsable Rudder version mode '{}'", s)
    }
}

// Checking if a rudder version is a nightly or not is not important for plugin compatibility
// So it is not implemented
#[derive(PartialEq, Eq, Debug, Clone)]
pub struct RudderVersion {
    pub major: u32,
    pub minor: u32,
    pub patch: u32,
    pub mode: RudderVersionMode,
}

impl RudderVersion {
    pub fn is_compatible(&self, webapp_version: &RudderVersion) -> bool {
        self == webapp_version
    }

    pub fn from_path(path: &str) -> Result<Self, Error> {
        let content = fs::read_to_string(path)?;
        let re = Regex::new(r"rudder_version=(?<raw_rudder_version>.*)")?;
        let caps = match re.captures(&content) {
            None => bail!(
                "'{}' does not look like a well formed Rudder version file.",
                path
            ),
            Some(c) => c,
        };
        debug!(
            "Rudder version read from '{}' file: '{}'.",
            path, &caps["raw_rudder_version"]
        );
        RudderVersion::from_str(&caps["raw_rudder_version"])
    }
}

impl FromStr for RudderVersion {
    type Err = Error;

    fn from_str(raw: &str) -> Result<Self, Self::Err> {
        let re = Regex::new(r"^(?<major>\d+)\.(?<minor>\d+)\.(?<patch>\d+)(?<mode>.*)$")?;
        let caps = match re.captures(raw) {
            None => bail!("Unparsable Rudder version '{}'", raw),
            Some(c) => c,
        };
        let major: u32 = caps["major"].parse()?;
        let minor: u32 = caps["minor"].parse()?;
        let patch: u32 = caps["patch"].parse()?;
        let mode: RudderVersionMode = RudderVersionMode::from_str(&caps["mode"])?;

        Ok(RudderVersion {
            major,
            minor,
            patch,
            mode,
        })
    }
}

impl Display for RudderVersion {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let s = format!("{}.{}.{}{}", self.major, self.minor, self.patch, self.mode);
        write!(f, "{}", s)
    }
}

#[derive(PartialEq, Eq, Debug, Clone)]
pub struct PluginVersion {
    pub major: u32,
    pub minor: u32,
    pub nightly: bool,
}

impl FromStr for PluginVersion {
    type Err = Error;

    fn from_str(raw: &str) -> Result<Self, Self::Err> {
        let nightly = Regex::new(r".*-nightly$")?.is_match(raw);
        let re = Regex::new(r"^(?<major>\d+)\.(?<minor>\d+)(-nightly)?$")?;
        let caps = match re.captures(raw) {
            None => bail!("Unparsable plugin version '{}'", raw),
            Some(c) => c,
        };
        let major: u32 = caps["major"].parse()?;
        let minor: u32 = caps["minor"].parse()?;

        Ok(PluginVersion {
            major,
            minor,
            nightly,
        })
    }
}
impl PartialOrd for PluginVersion {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        if self.major < other.major {
            Some(Ordering::Less)
        } else if self.major > other.major {
            Some(Ordering::Greater)
        } else if self.minor < other.minor {
            Some(Ordering::Less)
        } else if self.minor > other.minor {
            Some(Ordering::Greater)
        } else if self.nightly && !other.nightly {
            Some(Ordering::Less)
        } else if !self.nightly && other.nightly {
            Some(Ordering::Greater)
        } else {
            Some(Ordering::Equal)
        }
    }
}

impl Display for PluginVersion {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let nightly = if self.nightly { "-nightly" } else { "" };
        let s = format!("{}.{}{}", self.major, self.minor, nightly);
        write!(f, "{}", s)
    }
}

#[cfg(test)]
mod tests {
    use pretty_assertions::assert_eq;
    use rstest::rstest;

    use super::*;

    #[test]
    fn test_rudder_version_from_path() {
        let a = RudderVersion::from_path("./tests/versions/rudder-server-version").unwrap();
        assert_eq!(a, RudderVersion::from_str("8.0.4~git202311160211").unwrap());
    }

    #[rstest]
    #[case("7.0.0~alpha2", 7, 0, 0, "~alpha2")]
    #[case("7.0.0", 7, 0, 0, "")]
    #[case("8.0.1~rc1", 8, 0, 1, "~rc1")]
    fn test_rudder_version_parsing(
        #[case] raw: &str,
        #[case] e_major: u32,
        #[case] e_minor: u32,
        #[case] e_patch: u32,
        #[case] e_mode: &str,
    ) {
        let v = RudderVersion::from_str(raw).unwrap();
        assert_eq!(v.major, e_major);
        assert_eq!(v.minor, e_minor);
        assert_eq!(v.patch, e_patch);
        assert_eq!(v.mode, RudderVersionMode::from_str(e_mode).unwrap());
        assert_eq!(v.to_string(), raw);
    }

    #[rstest]
    #[should_panic]
    #[case("7.0.0-alpha2")]
    #[should_panic]
    #[case("7.0.0.0~alpha2")]
    #[should_panic]
    #[case("7.0.0alpha2")]
    fn test_rudder_version_parsing_errors(#[case] raw: &str) {
        let _ = RudderVersion::from_str(raw).unwrap();
    }

    #[rstest]
    #[case("11.0", "2.99")]
    #[case("1.20", "1.12")]
    #[case("2.3", "2.3-nightly")]
    fn test_plugin_version_greater_than(#[case] a: &str, #[case] b: &str) {
        let left = PluginVersion::from_str(a).unwrap();
        let right = PluginVersion::from_str(b).unwrap();
        assert!(left > right, "{:?} is not less than {:?}", left, right);
    }
    #[rstest]
    #[case("8.0.0-1.1")]
    #[case("8.0.0-1.1-nightly")]
    #[case("8.0.0-1.12")]
    #[case("8.0.0-1.12-nightly")]
    #[case("8.0.0-2.0-nightly")]
    #[case("8.0.0-2.1")]
    #[case("8.0.0-2.1-nightly")]
    #[case("8.0.0-2.2")]
    #[case("8.0.0-2.2-nightly")]
    #[case("8.0.0-2.3")]
    #[case("8.0.0-2.4")]
    #[case("8.0.0-2.4-nightly")]
    #[case("8.0.0-2.7")]
    #[case("8.0.0-2.9")]
    #[case("8.0.0-2.9-nightly")]
    #[case("8.0.0~alpha1-1.1")]
    #[case("8.0.0~alpha1-1.1-nightly")]
    #[case("8.0.0~alpha1-1.12")]
    #[case("8.0.0~alpha1-1.12-nightly")]
    #[case("8.0.0~alpha1-2.0-nightly")]
    #[case("8.0.0~alpha1-2.1")]
    #[case("8.0.0~alpha1-2.1-nightly")]
    #[case("8.0.0~alpha1-2.2")]
    #[case("8.0.0~alpha1-2.2-nightly")]
    #[case("8.0.0~alpha1-2.3")]
    #[case("8.0.0~alpha1-2.4")]
    #[case("8.0.0~alpha1-2.4-nightly")]
    #[case("8.0.0~alpha1-2.6")]
    #[case("8.0.0~alpha1-2.9")]
    #[case("8.0.0~alpha1-2.9-nightly")]
    #[case("8.0.0~alpha2-2.0-nightly")]
    #[case("8.0.0~alpha2-2.1-nightly")]
    #[case("8.0.0~alpha2-2.2-nightly")]
    #[case("8.0.0~beta1-1.1")]
    #[case("8.0.0~beta1-1.1-nightly")]
    #[case("8.0.0~beta1-1.12")]
    #[case("8.0.0~beta1-1.12-nightly")]
    #[case("8.0.0~beta1-2.1")]
    #[case("8.0.0~beta1-2.1-nightly")]
    #[case("8.0.0~beta1-2.2")]
    #[case("8.0.0~beta1-2.3")]
    #[case("8.0.0~beta1-2.4")]
    #[case("8.0.0~beta1-2.4-nightly")]
    #[case("8.0.0~beta1-2.7")]
    #[case("8.0.0~beta1-2.9")]
    #[case("8.0.0~beta1-2.9-nightly")]
    #[case("8.0.0~beta2-1.1")]
    #[case("8.0.0~beta2-1.1-nightly")]
    #[case("8.0.0~beta2-1.12")]
    #[case("8.0.0~beta2-1.12-nightly")]
    #[case("8.0.0~beta2-2.0-nightly")]
    #[case("8.0.0~beta2-2.1")]
    #[case("8.0.0~beta2-2.1-nightly")]
    #[case("8.0.0~beta2-2.2")]
    #[case("8.0.0~beta2-2.2-nightly")]
    #[case("8.0.0~beta2-2.3")]
    #[case("8.0.0~beta2-2.4")]
    #[case("8.0.0~beta2-2.4-nightly")]
    #[case("8.0.0~beta2-2.7")]
    #[case("8.0.0~beta2-2.9")]
    #[case("8.0.0~beta2-2.9-nightly")]
    #[case("8.0.0~beta3-1.1")]
    #[case("8.0.0~beta3-1.1-nightly")]
    #[case("8.0.0~beta3-1.12")]
    #[case("8.0.0~beta3-1.12-nightly")]
    #[case("8.0.0~beta3-2.1")]
    #[case("8.0.0~beta3-2.1-nightly")]
    #[case("8.0.0~beta3-2.2")]
    #[case("8.0.0~beta3-2.3")]
    #[case("8.0.0~beta3-2.4")]
    #[case("8.0.0~beta3-2.4-nightly")]
    #[case("8.0.0~beta3-2.7")]
    #[case("8.0.0~beta3-2.9")]
    #[case("8.0.0~beta3-2.9-nightly")]
    #[case("8.0.0~beta4-2.0-nightly")]
    #[case("8.0.0~beta4-2.1-nightly")]
    #[case("8.0.0~beta4-2.2-nightly")]
    #[case("8.0.0~rc1-1.1")]
    #[case("8.0.0~rc1-1.1-nightly")]
    #[case("8.0.0~rc1-1.12")]
    #[case("8.0.0~rc1-1.12-nightly")]
    #[case("8.0.0~rc1-2.0-nightly")]
    #[case("8.0.0~rc1-2.1")]
    #[case("8.0.0~rc1-2.1-nightly")]
    #[case("8.0.0~rc1-2.2")]
    #[case("8.0.0~rc1-2.2-nightly")]
    #[case("8.0.0~rc1-2.3")]
    #[case("8.0.0~rc1-2.4")]
    #[case("8.0.0~rc1-2.4-nightly")]
    #[case("8.0.0~rc1-2.7")]
    #[case("8.0.0~rc1-2.9")]
    #[case("8.0.0~rc1-2.9-nightly")]
    #[case("8.0.0~rc2-1.1")]
    #[case("8.0.0~rc2-1.1-nightly")]
    #[case("8.0.0~rc2-1.12")]
    #[case("8.0.0~rc2-1.12-nightly")]
    #[case("8.0.0~rc2-2.0-nightly")]
    #[case("8.0.0~rc2-2.1")]
    #[case("8.0.0~rc2-2.1-nightly")]
    #[case("8.0.0~rc2-2.2")]
    #[case("8.0.0~rc2-2.2-nightly")]
    #[case("8.0.0~rc2-2.3")]
    #[case("8.0.0~rc2-2.4")]
    #[case("8.0.0~rc2-2.4-nightly")]
    #[case("8.0.0~rc2-2.7")]
    #[case("8.0.0~rc2-2.9")]
    #[case("8.0.0~rc2-2.9-nightly")]
    #[case("8.0.0~rc3-2.0-nightly")]
    #[case("8.0.0~rc3-2.1-nightly")]
    #[case("8.0.0~rc3-2.2-nightly")]
    #[case("8.0.1-1.1")]
    #[case("8.0.1-1.1-nightly")]
    #[case("8.0.1-1.12")]
    #[case("8.0.1-1.12-nightly")]
    #[case("8.0.1-2.0-nightly")]
    #[case("8.0.1-2.1")]
    #[case("8.0.1-2.1-nightly")]
    #[case("8.0.1-2.2")]
    #[case("8.0.1-2.2-nightly")]
    #[case("8.0.1-2.3")]
    #[case("8.0.1-2.4")]
    #[case("8.0.1-2.4-nightly")]
    #[case("8.0.1-2.7")]
    #[case("8.0.1-2.9")]
    #[case("8.0.1-2.9-nightly")]
    #[case("8.0.2-2.0-nightly")]
    #[case("8.0.2-2.1-nightly")]
    #[case("8.0.2-2.2-nightly")]
    fn test_rpkg_version(#[case] a: &str) {
        let _ = ArchiveVersion::from_str(a).unwrap();
    }

    #[rstest]
    #[case("8.0.1-2.9-nightly", "8.0.1", true)]
    #[case("8.0.1-2.9-nightly", "8.0.0", false)]
    #[case("8.0.1~rc3-2.9", "8.0.1", false)]
    #[case("8.0.1~rc3-2.9", "8.0.1~rc3", true)]
    #[case("8.0.2~rc3-2.9", "8.0.1~rc3", false)]
    #[case("8.0.2~rc3-2.9", "8.0.2~rc2", false)]
    #[case("8.0.2~alpha1-2.9", "8.0.2~alpha1", true)]
    #[case("8.0.2~alpha1-2.9", "8.0.2~beta1", false)]
    #[case("8.0.2~beta1-2.9", "8.0.2~beta1", true)]
    #[case("8.0.2-2.9", "8.0.2~git12345", true)]
    #[case("8.0.2~alpha1.2-2.9", "8.0.2~git12345", false)]
    fn test_rpkg_compatibility(
        #[case] metadata_version: &str,
        #[case] webapp_version: &str,
        #[case] is_compatible: bool,
    ) {
        let m = ArchiveVersion::from_str(metadata_version).unwrap();
        assert_eq!(
            m.rudder_version
                .clone()
                .is_compatible(&RudderVersion::from_str(webapp_version).unwrap()),
            is_compatible,
            "Unexpected compatibility checkfor webapp version '{}' and metadata version {:?}'",
            webapp_version,
            m.rudder_version
        )
    }
}
