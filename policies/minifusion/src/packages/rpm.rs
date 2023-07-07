use crate::packages::{Package, PackageManager};
use anyhow::Result;
use std::process::{Command, Output};
use std::str;

pub struct Rpm;

impl PackageManager for Rpm {
    fn installed() -> Result<Vec<Package>> {
        let out = Self::list_installed()?;
        let stdout = str::from_utf8(&out.stdout)?;
        Self::parse_installed(stdout)
    }
}

impl Rpm {
    fn list_installed() -> Result<Output> {
        Ok(Command::new("/usr/bin/rpm")
            .arg("-qa")
            .arg("--queryformat")
            .arg("%{NAME}\t%{VERSION}-%{RELEASE}\t%{VENDOR}\t%{SUMMARY}\t%{EPOCH}\n")
            .output()?)
    }

    fn parse_installed(list: &str) -> Result<Vec<Package>> {
        let mut res = vec![];
        for line in list.lines() {
            let items: Vec<&str> = line.split("\t").collect();
            let version = if items[4] != "0" && items[4] != "(none)" {
                format!("{}:{}", items[4], items[1])
            } else {
                items[1].to_string()
            };
            let publisher = if items[2] != "(none)" {
                Some(items[2].to_string())
            } else {
                None
            };
            let p = Package {
                name: items[0].to_string(),
                version,
                comments: items[3].to_string(),
                publisher,
            };
            res.push(p)
        }
        Ok(res)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use pretty_assertions::assert_eq;

    #[test]
    fn it_parses_installed() {
        let list = r#"dbus-daemon	1.12.8-24.el8	AlmaLinux	D-BUS message bus	1
cracklib	2.9.6-15.el8	CloudLinux	A password-checking library	(none)
texlive-pst-coil	20180414-28.el8	AlmaLinux	A PSTricks package for coils, etc	7
grub2-tools-minimal	2.02-148.el8.alma	AlmaLinux	Support tools for GRUB.	1
gpg-pubkey	6f07d355-509cdb91	(none)	gpg(Rudder Project (RPM release key) <security@rudder-project.org>)	(none)
texlive-pst-tree	20180414-28.el8	AlmaLinux	Trees, using pstricks	7
unbound-libs	1.16.2-5.el8	AlmaLinux	Libraries used by the unbound server and client applications	(none)"#;
        assert_eq!(
            Rpm::parse_installed(list).unwrap(),
            vec![
                Package {
                    name: "dbus-daemon".to_string(),
                    version: "1:1.12.8-24.el8".to_string(),
                    comments: "D-BUS message bus".to_string(),
                    publisher: Some("AlmaLinux".to_string(),),
                },
                Package {
                    name: "cracklib".to_string(),
                    version: "2.9.6-15.el8".to_string(),
                    comments: "A password-checking library".to_string(),
                    publisher: Some("CloudLinux".to_string(),),
                },
                Package {
                    name: "texlive-pst-coil".to_string(),
                    version: "7:20180414-28.el8".to_string(),
                    comments: "A PSTricks package for coils, etc".to_string(),
                    publisher: Some("AlmaLinux".to_string(),),
                },
                Package {
                    name: "grub2-tools-minimal".to_string(),
                    version: "1:2.02-148.el8.alma".to_string(),
                    comments: "Support tools for GRUB.".to_string(),
                    publisher: Some("AlmaLinux".to_string(),),
                },
                Package {
                    name: "gpg-pubkey".to_string(),
                    version: "6f07d355-509cdb91".to_string(),
                    comments: "gpg(Rudder Project (RPM release key) <security@rudder-project.org>)"
                        .to_string(),
                    publisher: None,
                },
                Package {
                    name: "texlive-pst-tree".to_string(),
                    version: "7:20180414-28.el8".to_string(),
                    comments: "Trees, using pstricks".to_string(),
                    publisher: Some("AlmaLinux".to_string(),),
                },
                Package {
                    name: "unbound-libs".to_string(),
                    version: "1.16.2-5.el8".to_string(),
                    comments: "Libraries used by the unbound server and client applications"
                        .to_string(),
                    publisher: Some("AlmaLinux".to_string(),),
                },
            ]
        );
    }
}
