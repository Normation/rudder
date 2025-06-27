// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2022 Normation SAS

use std::fmt;

use crate::backends::unix::cfengine::bundle::Bundle;

#[derive(Debug, PartialEq, Eq)]
pub struct Technique {
    bundles: Vec<Bundle>,
    name: Option<String>,
    description: Option<String>,
    //parameters: Option<String>,
    version: Option<String>,
}

impl Default for Technique {
    fn default() -> Self {
        Self::new()
    }
}

impl Technique {
    pub fn new() -> Self {
        Self {
            name: None,
            version: None,
            description: None,
            //parameters: None,
            bundles: Vec::new(),
        }
    }

    pub fn name<T: Into<String>>(self, name: T) -> Self {
        Self {
            name: Some(name.into()),
            ..self
        }
    }

    pub fn version<T: Into<String>>(self, version: T) -> Self {
        Self {
            version: Some(version.into()),
            ..self
        }
    }

    pub fn description<T: Into<String>>(self, description: T) -> Self {
        Self {
            description: Some(description.into()),
            ..self
        }
    }

    pub fn bundle(mut self, bundle: Bundle) -> Self {
        self.bundles.push(bundle);
        self
    }

    pub fn bundles(mut self, bundles: Vec<Bundle>) -> Self {
        self.bundles.extend(bundles);
        self
    }
}

impl fmt::Display for Technique {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        if self.name.is_some() {
            writeln!(f, "# @name {}", self.name.as_ref().unwrap())?;
        }
        if self.version.is_some() {
            writeln!(f, "# @version {}", self.version.as_ref().unwrap())?;
        }
        if self.description.is_some() {
            writeln!(f, "# @description {}", self.description.as_ref().unwrap())?;
        }
        for bundle in &self.bundles {
            write!(f, "\n{bundle}")?;
        }
        writeln!(f)?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use std::collections::HashMap;

    use pretty_assertions::assert_eq;

    use super::*;

    #[test]
    fn format_technique() {
        let mut meta = HashMap::new();
        meta.insert("extra".to_string(), "plop".to_string());

        assert_eq!(
            Technique::new()
                .name("test")
                .version("1.0")
                .bundle(Bundle::agent("test"))
                .to_string(),
            r#"# @name test
# @version 1.0

bundle agent test {

  vars:
    "report_data.index" int => int(eval("${report_data.index}+1", "math", "infix")),
                           unless => "rudder_increment_guard";
    "local_index"       int => ${report_data.index},
                           unless => "rudder_increment_guard";

  classes:
    "rudder_increment_guard" expression => "any";

}
"#
        );
    }
}
