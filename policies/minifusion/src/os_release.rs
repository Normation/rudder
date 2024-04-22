//! Type for parsing the `/etc/os-release` file.
// Comes from https://github.com/pop-os/os-release/tree/master
// By System76, under MIT license

use std::{
    fs::File,
    io::{self, BufRead, BufReader},
    iter::FromIterator,
};

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
    /// Identifier of the original upstream OS that this release is a derivative of.
    ///
    /// **IE:** `debian`
    pub id_like: String,
    /// An identifier which describes this release, such as `ubuntu`.
    ///
    /// **IE:** `ubuntu`
    pub id: String,
    /// The name of this release, without the version string.
    ///
    /// **IE:** `Ubuntu`
    pub name: String,
    /// The name of this release, with the version string.
    ///
    /// **IE:** `Ubuntu 18.04 LTS`
    pub pretty_name: String,
    /// The codename of this version.
    ///
    /// **IE:** `bionic`
    pub version_codename: String,
    /// The version of this OS release, with additional details about the release.
    ///
    /// **IE:** `18.04 LTS (Bionic Beaver)`
    pub version_id: String,
    /// The version of this OS release.
    ///
    /// **IE:** `18.04`
    pub version: String,
}

impl OsRelease {
    /// Attempt to parse the contents of `/etc/os-release`.
    pub fn new() -> io::Result<OsRelease> {
        let file = BufReader::new(File::open("/etc/os-release")?);
        Ok(OsRelease::from_iter(file.lines().map_while(Result::ok)))
    }
}

impl FromIterator<String> for OsRelease {
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
                "VERSION_CODENAME=" => os_release.version_codename
            });
        }
        os_release
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const EXAMPLE: &str = r#"NAME="Pop!_OS"
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
        let os_release = OsRelease::from_iter(EXAMPLE.lines().map(|x| x.into()));

        assert_eq!(
            os_release,
            OsRelease {
                name: "Pop!_OS".into(),
                version: "18.04 LTS".into(),
                id: "ubuntu".into(),
                id_like: "debian".into(),
                pretty_name: "Pop!_OS 18.04 LTS".into(),
                version_id: "18.04".into(),
                version_codename: "bionic".into(),
            }
        )
    }
}
