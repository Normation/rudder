// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

#[cfg(feature = "apt-compat")]
use rust_apt_compat as rust_apt;

use anyhow::{Result, bail};
use rudder_module_type::os_release::OsRelease;
use rust_apt::{PackageFile, Version};

/// Equivalent to the `Unattended-Upgrade::Origins-Pattern`/`Unattended-Upgrade::Allowed-Origins` settings.
#[derive(Default, Clone, Debug)]
pub(crate) struct PackageFileFilter {
    origin: Option<String>,
    codename: Option<String>,
    archive: Option<String>,
    label: Option<String>,
}

#[derive(Default, Clone, Debug)]
struct PackageVersionInfo<'a> {
    origin: Option<&'a str>,
    codename: Option<&'a str>,
    archive: Option<&'a str>,
    label: Option<&'a str>,
    component: Option<&'a str>,
    site: Option<&'a str>,
}

impl<'a> PackageVersionInfo<'a> {
    fn from_package_file(v: &'a PackageFile) -> Self {
        Self {
            origin: v.origin(),
            codename: v.codename(),
            archive: v.archive(),
            label: v.label(),
            component: v.component(),
            site: v.site(),
        }
    }

    fn is_local(&self) -> bool {
        match (self.component, self.archive, self.label, self.site) {
            (Some("now"), Some("now"), None, None) => true,
            _ => false,
        }
    }
}

impl PackageFileFilter {
    fn debian(origin: String, codename: String, label: String) -> Self {
        Self {
            origin: Some(origin),
            codename: Some(codename),
            label: Some(label),
            ..Default::default()
        }
    }

    fn ubuntu(origin: String, archive: String) -> Self {
        Self {
            origin: Some(origin),
            archive: Some(archive),
            ..Default::default()
        }
    }

    // All specified fields need to match
    fn matches(&self, package_file: &PackageVersionInfo) -> bool {
        fn matches_value(v: Option<&str>, reference: Option<&String>) -> bool {
            if let Some(v_ref) = reference {
                // If specified, must match
                if let Some(v_val) = v {
                    v_val == v_ref
                } else {
                    false
                }
            } else {
                true
            }
        }

        if !matches_value(package_file.origin, self.origin.as_ref()) {
            return false;
        }
        if !matches_value(package_file.archive, self.archive.as_ref()) {
            return false;
        }
        if !matches_value(package_file.label, self.label.as_ref()) {
            return false;
        }
        if !matches_value(package_file.codename, self.codename.as_ref()) {
            return false;
        }

        true
    }

    pub(crate) fn is_in_allowed_origins(
        ver: &Version,
        allowed_origins: &[PackageFileFilter],
    ) -> bool {
        for package_file in ver.package_files() {
            let info = PackageVersionInfo::from_package_file(&package_file);

            // local origin is allowed by default
            if info.is_local() {
                return true;
            }

            // We need to match any of the allowed origin
            for filter in allowed_origins {
                if filter.matches(&info) {
                    return true;
                }
            }
        }
        false
    }
}

/// For security-only patching, we need the distribution to
/// act more precisely over repository names.
#[derive(Debug, PartialEq)]
pub enum Distribution {
    /// Codename
    Ubuntu(String),
    /// Codename
    Debian(String),
    /// OS id
    Other(String),
}

impl Distribution {
    pub(crate) fn new(os_release: &OsRelease) -> Self {
        match (os_release.id.as_str(), os_release.version_codename.as_ref()) {
            ("debian", Some(c)) => Distribution::Debian(c.to_string()),
            ("ubuntu", Some(c)) => Distribution::Ubuntu(c.to_string()),
            _ => Distribution::Other(os_release.id.clone()),
        }
    }

    /// Adapted from unattended-upgrades' default configuration
    pub(crate) fn security_origins(&self) -> Result<Vec<PackageFileFilter>> {
        match self {
            Distribution::Ubuntu(distro_codename) => Ok(vec![
                // "${distro_id}:${distro_codename}"
                PackageFileFilter::ubuntu("Ubuntu".to_string(), distro_codename.clone()),
                // "${distro_id}:${distro_codename}-security"
                PackageFileFilter::ubuntu(
                    "Ubuntu".to_string(),
                    format!("{distro_codename}-security"),
                ),
                // "${distro_id}ESMApps:${distro_codename}-apps-security"
                PackageFileFilter::ubuntu(
                    "UbuntuESMApps".to_string(),
                    format!("{distro_codename}-apps-security"),
                ),
                // "${distro_id}ESM:${distro_codename}-infra-security"
                PackageFileFilter::ubuntu(
                    "UbuntuESM".to_string(),
                    format!("{distro_codename}-infra-security"),
                ),
            ]),
            Distribution::Debian(distro_codename) => Ok(vec![
                // "origin=Debian,codename=${distro_codename},label=Debian"
                PackageFileFilter::debian(
                    "Debian".to_string(),
                    distro_codename.clone(),
                    "Debian".to_string(),
                ),
                // "origin=Debian,codename=${distro_codename},label=Debian-Security"
                PackageFileFilter::debian(
                    "Debian".to_string(),
                    distro_codename.clone(),
                    "Debian-Security".to_string(),
                ),
                // "origin=Debian,codename=${distro_codename}-security,label=Debian-Security"
                PackageFileFilter::debian(
                    "Debian".to_string(),
                    format!("{distro_codename}-security"),
                    "Debian-Security".to_string(),
                ),
            ]),
            Distribution::Other(o) => bail!(
                "Unsupported distribution for security-only upgrade: '{}'",
                o
            ),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn filter_matches_empty_rules() {
        let filter = PackageFileFilter::default();
        let info = PackageVersionInfo {
            origin: Some("Debian"),
            codename: Some("bookworm"),
            archive: Some("archive"),
            label: Some("42"),
            component: None,
            site: None,
        };
        assert!(filter.matches(&info))
    }

    #[test]
    fn filter_matches_partial_rules() {
        let filter = PackageFileFilter {
            origin: Some("Ubuntu".to_string()),
            ..Default::default()
        };
        let debian_info = PackageVersionInfo {
            origin: Some("Debian"),
            ..Default::default()
        };
        let ubuntu_info = PackageVersionInfo {
            origin: Some("Ubuntu"),
            ..Default::default()
        };
        assert!(!filter.matches(&debian_info));
        assert!(filter.matches(&ubuntu_info));
    }

    #[test]
    fn matches_security_origins() {
        let distribution = Distribution::Debian("bookworm".to_string());
        let security_origins = distribution.security_origins().unwrap();

        // Checking: libblockdev-part2 ([<Origin component:'main' archive:'stable-security' origin:'Debian' label:'Debian-Security' site:'deb.debian.org' isTrusted:True>])
        let package_info = PackageVersionInfo {
            origin: Some("Debian"),
            codename: Some("bookworm"),
            archive: Some("stable-security"),
            label: Some("Debian-Security"),
            component: Some("main"),
            site: Some("deb.debian.org"),
        };
        assert!(security_origins.iter().any(|o| o.matches(&package_info)));
    }
}
