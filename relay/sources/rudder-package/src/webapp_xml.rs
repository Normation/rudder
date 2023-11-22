// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023 Normation SAS

use std::{collections::HashMap, fs::File, io::BufReader, process::Command};

use anyhow::{bail, Result};
use log::debug;
use xmltree::Element;

use crate::cmd::CmdOutput;

/// Synchronous restart of the web application
pub fn restart_webapp() -> Result<()> {
    debug!("Restarting the Web application to apply plugin changes");
    let mut systemctl = Command::new("/usr/bin/systemctl");
    systemctl
        .arg("--no-ask-password")
        .arg("restart")
        .arg("rudder-jetty");
    match CmdOutput::new(&mut systemctl) {
        Ok(_) => Ok(()),
        Err(e) => {
            bail!("Could not restart the Rudder application:\n{}", e)
        }
    }
}

pub struct WebappXml {
    pub path: String,
    emitter_config: xmltree::EmitterConfig,
}

impl WebappXml {
    pub fn new(path: String) -> Self {
        Self {
            path: path.clone(),
            emitter_config: xmltree::EmitterConfig::new().perform_indent(true),
        }
    }

    fn get_xml_tree(&self) -> Result<xmltree::Element> {
        let file = File::open(self.path.clone()).unwrap();
        let file = BufReader::new(file);
        Ok(Element::parse(file)?)
    }

    pub fn get_enabled_plugins(&self) -> Result<Vec<String>> {
        let elements = self.get_xml_tree()?;
        let mut plugins: Vec<String> = Vec::new();
        for node in elements.children {
            if let Some(e) = node.as_element() {
                if e.name == "Set" {
                    match e.attributes.get("name").as_ref().map(|s| &s[..]) {
                        Some("extraClasspath") => {
                            if let Some(c_node) = e.children.get(0) {
                                if let Some(jar) = c_node.as_text() {
                                    plugins.push(jar.to_string())
                                }
                            }
                        }
                        Some(_) => (),
                        None => (),
                    }
                }
            }
        }
        Ok(plugins)
    }

    pub fn enable_jar(&self, jar_name: String) -> Result<()> {
        let mut elements = self.get_xml_tree()?;
        let enabled = self.get_enabled_plugins()?;
        if enabled.contains(&jar_name) {
            return Ok(());
        }
        let plugin_element = xmltree::Element {
            prefix: None,
            namespace: None,
            namespaces: None,
            name: String::from("Set"),
            attributes: HashMap::from([(String::from("name"), String::from("extraClasspath"))]),
            children: vec![xmltree::XMLNode::Text(jar_name)],
        };
        elements
            .children
            .push(xmltree::XMLNode::Element(plugin_element));
        let _ = elements.write_with_config(
            File::create(self.path.clone())?,
            self.emitter_config.clone(),
        );
        Ok(())
    }

    pub fn disable_jar(&self, jar_path: String) -> Result<()> {
        let mut elements = self.get_xml_tree()?;
        elements.children.retain(|x| -> bool {
            match x.as_element() {
                None => true,
                Some(e) => {
                    if e.name != "Set" {
                        true
                    } else {
                        match e.attributes.get("name").as_ref().map(|s| &s[..]) {
                            Some("extraClasspath") => match e.children.get(0) {
                                None => true,
                                Some(node) => match node.as_text() {
                                    None => true,
                                    Some(text) => text != jar_path,
                                },
                            },
                            _ => true,
                        }
                    }
                }
            }
        });
        let _ = elements.write_with_config(
            File::create(self.path.clone())?,
            self.emitter_config.clone(),
        );
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

    #[rstest]
    #[case("enable_test1.xml", "extra_jar.jar")]
    #[case("enable_test_with_existing_one.xml", "extra_jar.jar")]
    fn test_enable_jar(#[case] origin: &str, #[case] jar_name: &str) {
        let temp_dir = TempDir::new().unwrap();
        let sample = path::Path::new("./tests/webapp_xml").join(origin);
        let expected = path::Path::new(&sample).with_extension("xml.expected");
        let target = temp_dir.path().join(origin);
        fs::copy(sample, target.clone()).unwrap();
        let x = WebappXml::new(String::from(target.as_path().to_str().unwrap()));
        let _ = x.enable_jar(String::from(jar_name));
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
        let x = WebappXml::new(String::from(target.as_path().to_str().unwrap()));
        let _ = x.disable_jar(String::from(jar_name));
        assert_eq!(
            fs::read_to_string(target).unwrap(),
            fs::read_to_string(expected).unwrap()
        );
    }
}
