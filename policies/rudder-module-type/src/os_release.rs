//! Type for parsing the `/etc/os-release` file.
//! Only provide data useful in the Rudder context to identify an operating system.
//!
//! We don't provide enums and stay as agnostic as possible. Each module works at the most generic
//! level possible.
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

fn is_enclosed_with(line: &str, pattern: char) -> bool {
    line.starts_with(pattern) && line.ends_with(pattern)
}

fn unquote(line: &str) -> &str {
    if is_enclosed_with(line, '"') || is_enclosed_with(line, '\'') {
        &line[1..line.len() - 1]
    } else {
        line
    }
}

/// Contents of the `/etc/os-release` file, as a data structure.
#[derive(Clone, Debug, PartialEq)]
pub struct OsRelease {
    /// A space-separated list of operating system identifiers in the same syntax as the ID= setting. It should list identifiers of operating systems that are closely related to the local operating system in regards to packaging and programming interfaces, for example listing one or more OS identifiers the local OS is a derivative from. An OS should generally only list other OS identifiers it itself is a derivative of, and not any OSes that are derived from it, though symmetric relationships are possible. Build scripts and similar should check this variable if they need to identify the local operating system and the value of ID= is not recognized. Operating systems should be listed in order of how closely the local operating system relates to the listed ones, starting with the closest. This field is optional.
    ///
    /// Examples: for an operating system with "ID=centos", an assignment of "ID_LIKE="rhel fedora"" would be appropriate. For an operating system with "ID=ubuntu", an assignment of "ID_LIKE=debian" is appropriate.
    pub id_like: Vec<String>,
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
    pub version_codename: Option<String>,
    /// A lower-case string (mostly numeric, no spaces or other characters outside of 0–9, a–z, ".", "_" and "-") identifying the operating system version, excluding any OS name information or release code name, and suitable for processing by scripts or usage in generated filenames. This field is optional.
    ///
    /// Examples: "VERSION_ID=17", "VERSION_ID=11.04".
    pub version_id: Option<String>,
    /// A string identifying the operating system version, excluding any OS name information, possibly including a release code name, and suitable for presentation to the user. This field is optional.
    ///
    /// Examples: "VERSION=17", "VERSION="17 (Beefy Miracle)"".
    pub version: Option<String>,
}

impl Default for OsRelease {
    #[cfg(target_os = "linux")]
    fn default() -> Self {
        Self {
            id_like: vec![],
            id: "linux".to_string(),
            name: "Linux".to_string(),
            pretty_name: "Linux".to_string(),
            version_codename: None,
            version_id: None,
            version: None,
        }
    }

    #[cfg(not(target_os = "linux"))]
    fn default() -> Self {
        unimplemented!()
    }
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
        let etc = Path::new("/etc/os-release");
        let usr = Path::new("/usr/lib/os-release");
        let file = match (etc.exists(), usr.exists()) {
            (true, _) => Some(etc),
            (false, true) => Some(usr),
            _ => None,
        };
        if let Some(path) = file {
            let file = BufReader::new(File::open(path)?);
            Ok(OsRelease::from_iter(file.lines().map_while(Result::ok)))
        } else {
            Ok(OsRelease::default())
        }
    }

    pub fn from_string(s: &str) -> OsRelease {
        OsRelease::from_iter(s.lines().map(|s| s.to_string()))
    }
}

impl FromIterator<String> for OsRelease {
    /// > os-release must not contain repeating keys. Nevertheless, readers should pick the entries
    /// > later in the file in case of repeats, similarly to how a shell sourcing the file would.
    /// > A reader may warn about repeating entries.
    fn from_iter<I: IntoIterator<Item = String>>(lines: I) -> Self {
        let mut os_release = Self::default();

        for line in lines {
            // > The format of os-release is a newline-separated list of environment-like shell-compatible
            // > variable assignments. It is possible to source the configuration from Bourne shell scripts,
            // > however, beyond mere variable assignments, no shell features are supported (this means variable
            // > expansion is explicitly not supported), allowing applications to read the file without
            // > implementing a shell compatible execution engine. Variable assignment values must be enclosed
            // > in double or single quotes if they include spaces, semicolons or other special characters
            // > outside of A–Z, a–z, 0–9. (Assignments that do not include these special characters may be
            // > enclosed in quotes too, but this is optional.) Shell special characters
            // > ("$", quotes, backslash, backtick) must be escaped with backslashes, following shell style.
            // > All strings should be in UTF-8 encoding, and non-printable characters should not be used.
            // > Concatenation of multiple individually quoted strings is not supported. Lines beginning
            // > with "#" are treated as comments. Blank lines are permitted and ignored.
            if let Some((k, v)) = line.split_once('=') {
                let k = k.trim();
                // TODO: unescape special shell chars
                let v = unquote(v.trim());

                match k {
                    "NAME" => os_release.name = v.to_string(),
                    "VERSION" => os_release.version = Some(v.to_string()),
                    "ID" => os_release.id = v.to_string(),
                    "PRETTY_NAME" => os_release.pretty_name = v.to_string(),
                    "VERSION_ID" => os_release.version_id = Some(v.to_string()),
                    "VERSION_CODENAME" => os_release.version_codename = Some(v.to_string()),
                    "ID_LIKE" => {
                        os_release.id_like = v.split_whitespace().map(String::from).collect()
                    }
                    _ => continue,
                }
            }
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
    fn it_parses_os_release_on_current_system() {
        assert!(OsRelease::new().is_ok());
    }

    #[test]
    fn it_parses_empty_os_release() {
        let rocky = OsRelease::from_string("");
        assert_eq!(
            rocky,
            OsRelease {
                name: "Linux".into(),
                version: None,
                id: "linux".into(),
                id_like: vec![],
                pretty_name: "Linux".into(),
                version_id: None,
                version_codename: None,
            }
        );
    }

    #[test]
    fn it_parses_rocky_os_release() {
        let rocky = OsRelease::from_string(ROCKY);
        assert_eq!(
            rocky,
            OsRelease {
                name: "Rocky Linux".into(),
                version: Some("9.1 (Blue Onyx)".into()),
                id: "rocky".into(),
                id_like: vec![
                    "rhel".to_string(),
                    "centos".to_string(),
                    "fedora".to_string()
                ],
                pretty_name: "Rocky Linux 9.1 (Blue Onyx)".into(),
                version_id: Some("9.1".into()),
                version_codename: None,
            }
        );
    }

    #[test]
    fn it_parses_popos_os_release() {
        let ubuntu = OsRelease::from_string(POPOS);
        assert_eq!(
            ubuntu,
            OsRelease {
                name: "Pop!_OS".into(),
                version: Some("18.04 LTS".into()),
                id: "ubuntu".into(),
                id_like: vec!["debian".into()],
                pretty_name: "Pop!_OS 18.04 LTS".into(),
                version_id: Some("18.04".into()),
                version_codename: Some("bionic".into()),
            }
        );
    }

    #[test]
    fn it_overrides_values() {
        let overridden = "ID=ubuntu\nID=debian";
        let debian = OsRelease::from_string(overridden);
        assert_eq!(debian.id, "debian".to_string(),);
    }
}
