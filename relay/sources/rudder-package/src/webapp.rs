// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023 Normation SAS

use anyhow::{Context, Result};
use quick_xml::{
    Writer,
    events::{BytesEnd, BytesStart, BytesText, Event},
    reader::Reader,
};
use spinners::{Spinner, Spinners};
use std::io::IsTerminal;
use std::{
    collections::HashSet,
    env, fs,
    io::{Cursor, Write},
    path::PathBuf,
    process::Command,
};
use tracing::{Level, debug, enabled, info};

use crate::{DONT_RESTART_ENV_VAR, cmd::CmdOutput, versions::RudderVersion};

enum SpinnerOption {
    Show(Spinner),
    Hidden,
}

/// A spinner that is shown only if the output is a terminal and logging is enabled with `INFO` level or higher.
/// This is useful to avoid showing spinners in non-interactive environments.
impl SpinnerOption {
    fn new(message: String) -> Self {
        if std::io::stdout().is_terminal() && enabled!(Level::INFO) {
            SpinnerOption::Show(Spinner::new(Spinners::Dots, message))
        } else {
            info!(message);
            SpinnerOption::Hidden
        }
    }

    fn stop_with_success(self) {
        match self {
            SpinnerOption::Show(mut spinner) => spinner.stop_with_symbol("🗸"),
            SpinnerOption::Hidden => {}
        }
    }
}

/// We want to write the file after each plugin to avoid half-installs
pub struct Webapp {
    pub path: PathBuf,
    pending_changes: bool,
    pub version: RudderVersion,
}

impl Webapp {
    pub fn new(path: PathBuf, version: RudderVersion) -> Self {
        Self {
            path,
            pending_changes: false,
            version,
        }
    }

    /// Return currently loaded jars
    pub fn jars(&self) -> Result<Vec<String>> {
        let mut reader = Reader::from_file(&self.path).with_context(|| {
            format!(
                "Failed to read from webapp config file '{}'",
                &self.path.to_string_lossy()
            )
        })?;
        let mut buf = Vec::new();
        let mut in_extra_classpath = false;

        loop {
            match reader.read_event_into(&mut buf)? {
                Event::Eof => break,
                Event::Start(e) if e.name().as_ref() == b"Set" => {
                    for a in e.attributes() {
                        let a = a?;
                        if a.key.as_ref() == b"name" && a.value.as_ref() == b"extraClasspath" {
                            in_extra_classpath = true;
                        }
                    }
                }
                Event::Text(e) => {
                    if in_extra_classpath {
                        return Ok(e.unescape()?.split(',').map(|s| s.to_string()).collect());
                    }
                }
                _ => (),
            }
            buf.clear();
        }
        Ok(vec![])
    }

    /// Update the loaded jars
    fn modify_jars(&mut self, present: &[String], absent: &[String]) -> Result<()> {
        let mut reader = Reader::from_file(&self.path)?;
        let mut buf = Vec::new();
        let mut writer = Writer::new(Cursor::new(Vec::new()));
        let mut in_extra_classpath = false;
        let mut extra_classpath_found = false;

        loop {
            match reader.read_event_into(&mut buf)? {
                Event::Eof => break,
                Event::Start(e) if e.name().as_ref() == b"Set" => {
                    for a in e.attributes() {
                        let a = a?;
                        if a.key.as_ref() == b"name" && a.value.as_ref() == b"extraClasspath" {
                            in_extra_classpath = true;
                            extra_classpath_found = true;
                        }
                    }
                    writer.write_event(Event::Start(e))?;
                }
                Event::Text(e) => {
                    // there are existing jars
                    if in_extra_classpath {
                        in_extra_classpath = false;
                        let jars_t = e.unescape()?;
                        let mut jars: HashSet<&str> = HashSet::from_iter(jars_t.split(','));
                        // Trigger a restart if something changes
                        for p in present {
                            let changed = jars.insert(p);
                            if changed {
                                self.pending_changes = true;
                            }
                        }
                        for a in absent {
                            let changed = jars.remove(a.as_str());
                            if changed {
                                self.pending_changes = true;
                            }
                        }
                        let jar_value: Vec<&str> = jars.into_iter().collect();
                        writer.write_event(Event::Text(BytesText::new(&jar_value.join(","))))?;
                    } else {
                        writer.write_event(Event::Text(e))?;
                    }
                }
                Event::End(e) if e.name().as_ref() == b"Set" => {
                    // there are no existing jars, but the section exists
                    if in_extra_classpath {
                        in_extra_classpath = false;
                        let mut jars: HashSet<&str> = HashSet::new();
                        for p in present {
                            jars.insert(p);
                            self.pending_changes = true;
                        }
                        let jar_value: Vec<&str> = jars.into_iter().collect();
                        writer.write_event(Event::Text(BytesText::new(&jar_value.join(","))))?;
                    }
                    writer.write_event(Event::End(e))?;
                }
                Event::End(e) if e.name().as_ref() == b"Configure" => {
                    // there is not entry at all
                    if !extra_classpath_found && !present.is_empty() {
                        // Create the element if needed
                        let mut start = BytesStart::new("Set");
                        start.push_attribute(("name", "extraClasspath"));
                        writer.write_event(Event::Start(start))?;

                        writer.write_event(Event::Text(BytesText::new(&present.join(","))))?;

                        let end = BytesEnd::new("Set");
                        writer.write_event(Event::End(end))?;
                        self.pending_changes = true;
                    }
                    writer.write_event(Event::End(e))?;
                }
                Event::End(e) => {
                    if in_extra_classpath {
                        in_extra_classpath = false;
                    }
                    writer.write_event(Event::End(e))?;
                }
                e => writer.write_event(e)?,
            }
            buf.clear();
        }

        if self.pending_changes {
            let mut file = fs::OpenOptions::new()
                .write(true)
                .truncate(true)
                .open(&self.path)?;
            file.write_all(writer.into_inner().into_inner().as_slice())?;
            file.flush()?;
        }

        Ok(())
    }

    pub fn enable_jars(&mut self, jars: &[String]) -> Result<()> {
        self.modify_jars(jars, &[])
    }

    pub fn disable_jars(&mut self, jars: &[String]) -> Result<()> {
        self.modify_jars(&[], jars)
    }

    /// Used to ensure the webapp gets restarted even if the jars list has not changed.
    /// To be used when the jar contents have changed on disk (i.e. plugin upgrade).
    pub fn trigger_restart(&mut self) {
        self.pending_changes = true;
    }

    /// Synchronous restart of the web application
    pub fn apply_changes(&mut self) -> Result<()> {
        if self.pending_changes {
            if env::var(DONT_RESTART_ENV_VAR).is_ok() {
                debug!(
                    "Skipping webapp restart as the {} environment variable is set",
                    DONT_RESTART_ENV_VAR
                );
                return Ok(());
            }

            let spinner =
                SpinnerOption::new("Restarting the Web application to apply changes".into());
            let mut systemctl = Command::new("systemctl");
            systemctl
                .arg("--no-ask-password")
                .arg("restart")
                .arg("rudder-jetty");
            let _ = CmdOutput::new(&mut systemctl)
                .context("Restarting the rudder-jetty service with systemctl")?;
            spinner.stop_with_success();
            self.pending_changes = false;
        } else {
            debug!("No need to restart the Web application");
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use std::{fs, path};

    use pretty_assertions::assert_eq;
    use rstest::rstest;
    use tempfile::TempDir;

    use super::*;

    #[test]
    fn it_reads_jars() {
        let w = Webapp::new(
            PathBuf::from("tests/webapp_xml/example.xml"),
            RudderVersion::from_path("./tests/versions/rudder-server-version").unwrap(),
        );
        let jars = w.jars().unwrap();
        assert_eq!(
            jars,
            vec![
                "/opt/rudder/share/plugins/dsc/dsc.jar",
                "/opt/rudder/share/plugins/api-authorizations/api-authorizations.jar"
            ]
        );
    }

    #[rstest]
    #[case("enable_test1.xml", "extra_jar.jar")]
    #[case("enable_test_with_existing_one.xml", "extra_jar.jar")]
    fn test_enable_jar(#[case] origin: &str, #[case] jar_name: &str) {
        let temp_dir = TempDir::new().unwrap();
        let sample = path::Path::new("./tests/webapp_xml").join(origin);
        let expected = path::Path::new(&sample).with_extension("xml.expected");
        let target = temp_dir.path().join(origin);
        fs::copy(sample, target.clone()).unwrap();
        let mut x = Webapp::new(
            target.clone(),
            RudderVersion::from_path("./tests/versions/rudder-server-version").unwrap(),
        );
        let _ = x.enable_jars(&[String::from(jar_name)]);
        assert_eq!(
            fs::read_to_string(target).unwrap(),
            fs::read_to_string(expected).unwrap()
        );
    }

    #[rstest]
    #[case("disable_test1.xml", "extra_jar.jar")]
    #[case("disable_with_multiple_instances.xml", "extra_jar.jar")]
    #[case("disable_when_absent.xml", "extra_jar.jar")]
    fn test_disable_jar(#[case] origin: &str, #[case] jar_name: &str) {
        let temp_dir = TempDir::new().unwrap();
        let sample = path::Path::new("./tests/webapp_xml").join(origin);
        let expected = path::Path::new(&sample).with_extension("xml.expected");
        let target = temp_dir.path().join(origin);
        fs::copy(sample, target.clone()).unwrap();
        let mut x = Webapp::new(
            target.clone(),
            RudderVersion::from_path("./tests/versions/rudder-server-version").unwrap(),
        );
        let _ = x.disable_jars(&[String::from(jar_name)]);
        assert_eq!(
            fs::read_to_string(target).unwrap(),
            fs::read_to_string(expected).unwrap()
        );
    }
}
