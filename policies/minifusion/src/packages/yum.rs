use crate::packages::{Update, UpdateManager};
use anyhow::Result;
use regex::Regex;
use std::process::{Command, Output};
use std::str;

pub struct Yum;

impl UpdateManager for Yum {
    fn updates() -> Result<Vec<Update>> {
        let updates = Self::list_updates()?;
        let updates_stdout = str::from_utf8(&updates.stdout)?;
        let info = Self::list_info()?;
        let info_stdout = str::from_utf8(&info.stdout)?;
        Self::parse_updates_info(updates_stdout, info_stdout)
    }
}

impl Yum {
    fn list_updates() -> Result<Output> {
        Ok(Command::new("/usr/bin/yum")
            .arg("--quiet")
            .arg("-y")
            .arg("check-update")
            .output()?)
    }

    fn list_info() -> Result<Output> {
        Ok(Command::new("/usr/bin/yum")
            .arg("updateinfo")
            .arg("--info")
            .output()?)
    }

    fn parse_updates_info(updates: &str, _info: &str) -> Result<Vec<Update>> {
        let mut res = vec![];
        for line in updates.lines() {
            let re = Regex::new(r"^(\S+)\.([^.\s]+)\s+(\S+)\s+(\S+)\s*$").unwrap();
            let Some(caps) = re.captures(line) else {
                continue
            };

            let _info_re =
                Regex::new(r"^[\S\s]*?=+\n  (\S+: )?$name [a-z ]+\n=+\n([\S\s]*?)\n=+\n").unwrap();

            let u = Update {
                name: caps[1].to_string(),
                version: caps[3].to_string(),
                arch: caps[2].to_string(),
                from: "yum".to_string(),
                kind: "".to_string(),
                source: caps[4].to_string(),
                description: "".to_string(),
                severity: "".to_string(),
                ids: "".to_string(),
            };
            res.push(u)
        }
        Ok(res)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use pretty_assertions::assert_eq;
}
