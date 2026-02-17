//! Type for parsing the `/etc/os-release` file.
//!
//! For a broad list of sample `/etc/os-release` files, see
//! <https://github.com/chef/os_release>.
//!
//! For the specs, see:
//! <https://www.freedesktop.org/software/systemd/man/latest/os-release.html>

use std::{
    fs::File,
    io::{self, BufRead, BufReader},
    iter::FromIterator,
    path::Path,
};

// Adapted from https://github.com/pop-os/os-release/tree/master
// By System76, under MIT license

const OS_RELEASE_ETC: &str = "/etc/os-release";
const OS_RELEASE_USR: &str = "/usr/lib/os-release";

macro_rules! map_keys {
    ($item:expr, { $($pat:expr => $field:expr),+ }) => {{
        $(
            if $item.starts_with($pat) {
                $field = parse_line($item, $pat.len()).into();
                continue;
            }
        )+
    }};
}

fn is_enclosed_with(line: &str, pattern: char) -> bool {
    line.starts_with(pattern) && line.ends_with(pattern)
}

fn parse_line(line: &str, skip: usize) -> &str {
    let line = line[skip..].trim();
    if is_enclosed_with(line, '"') || is_enclosed_with(line, '\'') {
        &line[1..line.len() - 1]
    } else {
        line
    }
}

/// Contents of the `/etc/os-release` file, as a data structure.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct OsRelease {
    /// A space-separated list of operating system identifiers in the same syntax as the ID= setting. It should list identifiers of operating systems that are closely related to the local operating system in regards to packaging and programming interfaces, for example listing one or more OS identifiers the local OS is a derivative from. An OS should generally only list other OS identifiers it itself is a derivative of, and not any OSes that are derived from it, though symmetric relationships are possible. Build scripts and similar should check this variable if they need to identify the local operating system and the value of ID= is not recognized. Operating systems should be listed in order of how closely the local operating system relates to the listed ones, starting with the closest. This field is optional.
    ///
    /// Examples: for an operating system with "ID=centos", an assignment of "ID_LIKE="rhel fedora"" would be appropriate. For an operating system with "ID=ubuntu", an assignment of "ID_LIKE=debian" is appropriate.
    pub id_like: String,
    /// A lower-case string (no spaces or other characters outside of 0–9, a–z, ".", "_" and "-") identifying the operating system, excluding any version information and suitable for processing by scripts or usage in generated filenames. If not set, a default of "ID=linux" may be used. Note that even though this string may not include characters that require shell quoting, quoting may nevertheless be used.
    ///
    /// Examples: "ID=fedora", "ID=debian".
    pub id: String,
    /// A string identifying the operating system, without a version component, and suitable for presentation to the user. If not set, a default of "NAME=Linux" may be used.
    ///
    /// Examples: "NAME=Fedora", "NAME="Debian GNU/Linux"".
    pub name: String,
    /// A pretty operating system name in a format suitable for presentation to the user. May or may not contain a release code name or OS version of some kind, as suitable. If not set, a default of "PRETTY_NAME="Linux"" may be used
    ///
    /// Example: "PRETTY_NAME="Fedora 17 (Beefy Miracle)"".
    pub pretty_name: String,
    /// A lower-case string (no spaces or other characters outside of 0–9, a–z, ".", "_" and "-") identifying the operating system release code name, excluding any OS name information or release version, and suitable for processing by scripts or usage in generated filenames. This field is optional and may not be implemented on all systems.
    ///
    /// Examples: "VERSION_CODENAME=buster", "VERSION_CODENAME=xenial".
    pub version_codename: String,
    /// A lower-case string (mostly numeric, no spaces or other characters outside of 0–9, a–z, ".", "_" and "-") identifying the operating system version, excluding any OS name information or release code name, and suitable for processing by scripts or usage in generated filenames. This field is optional.
    ///
    /// Examples: "VERSION_ID=17", "VERSION_ID=11.04".
    pub version_id: String,
    /// A string identifying the operating system version, excluding any OS name information, possibly including a release code name, and suitable for presentation to the user. This field is optional.
    ///
    /// Examples: "VERSION=17", "VERSION="17 (Beefy Miracle)"".
    pub version: String,
    /// A string identifying a specific variant or edition of the operating system suitable for presentation to the user. This field may be used to inform the user that the configuration of this system is subject to a specific divergent set of rules or default configuration settings. This field is optional and may not be implemented on all systems.
    ///
    /// Examples: "VARIANT="Server Edition"", "VARIANT="Smart Refrigerator Edition"".
    ///
    /// Note: this field is for display purposes only. The VARIANT_ID field should be used for making programmatic decisions.
    pub variant: String,
    /// A lower-case string (no spaces or other characters outside of 0–9, a–z, ".", "_" and "-"), identifying a specific variant or edition of the operating system. This may be interpreted by other packages in order to determine a divergent default configuration. This field is optional and may not be implemented on all systems.
    ///
    /// Examples: "VARIANT_ID=server", "VARIANT_ID=embedded".
    pub variant_id: String,
    /// A CPE name for the operating system, in URI binding syntax, following the Common Platform Enumeration Specification as proposed by the NIST. This field is optional.
    ///
    /// Example: "CPE_NAME="cpe:/o:fedoraproject:fedora:17""
    pub cpe_name: String,
    /// A string uniquely identifying the system image originally used as the installation base. In most cases, VERSION_ID or IMAGE_ID+IMAGE_VERSION are updated when the entire system image is replaced during an update. BUILD_ID may be used in distributions where the original installation image version is important: VERSION_ID would change during incremental system updates, but BUILD_ID would not. This field is optional.
    ///
    /// Examples: "BUILD_ID="2013-03-20.3"", "BUILD_ID=201303203".
    pub build_id: String,
    /// A lower-case string (no spaces or other characters outside of 0–9, a–z, ".", "_" and "-"), identifying a specific image of the operating system. This is supposed to be used for environments where OS images are prepared, built, shipped and updated as comprehensive, consistent OS images. This field is optional and may not be implemented on all systems, in particularly not on those that are not managed via images but put together and updated from individual packages and on the local system.
    ///
    /// Examples: "IMAGE_ID=vendorx-cashier-system", "IMAGE_ID=netbook-image".
    pub image_id: String,
    /// A lower-case string (mostly numeric, no spaces or other characters outside of 0–9, a–z, ".", "_" and "-") identifying the OS image version. This is supposed to be used together with IMAGE_ID described above, to discern different versions of the same image.
    ///
    /// Examples: "IMAGE_VERSION=33", "IMAGE_VERSION=47.1rc1".
    pub image_version: String,
}

impl OsRelease {
    /// Attempt to parse the contents of `/etc/os-release`, with a fallback to `/usr/lib/os-release`.
    ///
    /// The specs say:
    ///
    /// > The file `/etc/os-release` takes precedence over `/usr/lib/os-release`.
    /// > Applications should check for the former, and exclusively use its data if it exists,
    /// > and only fall back to `/usr/lib/os-release` if it is missing. Applications should not
    /// > read data from both files at the same time. `/usr/lib/os-release` is the recommended place
    /// > to store OS release information as part of vendor trees. `/etc/os-release` should be a
    /// > relative symlink to `/usr/lib/os-release`, to provide compatibility with applications only
    /// > looking at `/etc/`. A relative symlink instead of an absolute symlink is necessary to avoid
    /// > breaking the link in a chroot or initrd environment.
    pub fn new() -> io::Result<OsRelease> {
        let etc = Path::new(OS_RELEASE_ETC);
        let path = if etc.exists() {
            etc
        } else {
            Path::new(OS_RELEASE_USR)
        };
        let file = BufReader::new(File::open(path)?);
        Ok(OsRelease::from_iter(file.lines().map_while(Result::ok)))
    }

    #[allow(dead_code)]
    pub fn id_like(&self) -> Vec<&str> {
        self.id_like.split_whitespace().collect()
    }
}

impl FromIterator<String> for OsRelease {
    /// > os-release must not contain repeating keys. Nevertheless, readers should pick the entries
    /// > later in the file in case of repeats, similarly to how a shell sourcing the file would.
    /// > A reader may warn about repeating entries.
    fn from_iter<I: IntoIterator<Item = String>>(lines: I) -> Self {
        let mut os_release = Self::default();

        for line in lines {
            let line = line.trim();
            map_keys!(line, {
                "NAME=" => os_release.name,
                "VERSION=" => os_release.version,
                "ID=" => os_release.id,
                "ID_LIKE=" => os_release.id_like,
                "PRETTY_NAME=" => os_release.pretty_name,
                "VERSION_ID=" => os_release.version_id,
                "VERSION_CODENAME=" => os_release.version_codename,
                "VARIANT=" => os_release.variant,
                "VARIANT_ID=" => os_release.variant_id,
                "CPE_NAME=" => os_release.cpe_name,
                "BUILD_ID=" => os_release.build_id,
                "IMAGE_ID=" => os_release.image_id,
                "IMAGE_VERSION=" => os_release.image_version
            });
        }
        os_release
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const ROCKY: &str = r#"NAME="Rocky Linux"
VERSION="9.1 (Blue Onyx)"
ID="rocky"
ID_LIKE="rhel centos fedora"
VERSION_ID="9.1"
PLATFORM_ID="platform:el9"
PRETTY_NAME="Rocky Linux 9.1 (Blue Onyx)"
ANSI_COLOR="0;32"
LOGO="fedora-logo-icon"
CPE_NAME="cpe:/o:rocky:rocky:9::baseos"
HOME_URL="https://rockylinux.org/"
BUG_REPORT_URL="https://bugs.rockylinux.org/"
ROCKY_SUPPORT_PRODUCT="Rocky-Linux-9"
ROCKY_SUPPORT_PRODUCT_VERSION="9.1"
REDHAT_SUPPORT_PRODUCT="Rocky Linux"
REDHAT_SUPPORT_PRODUCT_VERSION="9.1"
"#;

    const POPOS: &str = r#"NAME="Pop!_OS"
VERSION="18.04 LTS"
ID=ubuntu
ID_LIKE=debian
PRETTY_NAME="Pop!_OS 18.04 LTS"
VERSION_ID="18.04"
HOME_URL="https://system76.com/pop"
SUPPORT_URL="http://support.system76.com"
BUG_REPORT_URL="https://github.com/pop-os/pop/issues"
PRIVACY_POLICY_URL="https://system76.com/privacy"
VERSION_CODENAME=bionic
EXTRA_KEY=thing
ANOTHER_KEY="#;

    #[test]
    fn os_release() {
        let rocky = OsRelease::from_iter(ROCKY.lines().map(|x| x.into()));
        assert_eq!(
            rocky,
            OsRelease {
                name: "Rocky Linux".into(),
                version: "9.1 (Blue Onyx)".into(),
                variant: "".to_string(),
                variant_id: "".to_string(),
                cpe_name: "cpe:/o:rocky:rocky:9::baseos".to_string(),
                build_id: "".to_string(),
                image_id: "".to_string(),
                id: "rocky".into(),
                id_like: "rhel centos fedora".into(),
                pretty_name: "Rocky Linux 9.1 (Blue Onyx)".into(),
                version_id: "9.1".into(),
                version_codename: "".into(),
                image_version: "".to_string(),
            }
        );
        assert_eq!(rocky.id_like(), vec!["rhel", "centos", "fedora"]);

        let ubuntu = OsRelease::from_iter(POPOS.lines().map(|x| x.into()));
        assert_eq!(
            ubuntu,
            OsRelease {
                name: "Pop!_OS".into(),
                version: "18.04 LTS".into(),
                variant: "".to_string(),
                variant_id: "".to_string(),
                cpe_name: "".to_string(),
                build_id: "".to_string(),
                image_id: "".to_string(),
                id: "ubuntu".into(),
                id_like: "debian".into(),
                pretty_name: "Pop!_OS 18.04 LTS".into(),
                version_id: "18.04".into(),
                version_codename: "bionic".into(),
                image_version: "".to_string(),
            }
        );
    }
}
