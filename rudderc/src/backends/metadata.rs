// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

//! Generation of metadata.xml, shared by different backends
//!
//! Contains information about expected reports for the Rudder app

use anyhow::Result;
use quick_xml::{se::Serializer, Writer};
use serde::Serialize;

/*
<TECHNIQUE name="Audit config values">
  <DESCRIPTION></DESCRIPTION>
  <USEMETHODREPORTING>true</USEMETHODREPORTING>
  <AGENT type="cfengine-community,cfengine-nova">
    <BUNDLES>
      <NAME>Audit_config_values</NAME>
    </BUNDLES>
    <FILES>
      <FILE name="RUDDER_CONFIGURATION_REPOSITORY/techniques/ncf_techniques/Audit_config_values/1.0/technique.cf">
        <INCLUDED>true</INCLUDED>
      </FILE>
    </FILES>
  </AGENT>
  <AGENT type="dsc">
    <BUNDLES>
      <NAME>Audit-Config-Values</NAME>
    </BUNDLES>
    <FILES>
      <FILE name="RUDDER_CONFIGURATION_REPOSITORY/techniques/ncf_techniques/Audit_config_values/1.0/technique.ps1">
        <INCLUDED>true</INCLUDED>
      </FILE>
    </FILES>
  </AGENT>
  <SECTIONS>
    <SECTION component="true" multivalued="true" id="dcf1f0d5-80e5-42f6-8844-280822a9af17" name="Variable string from Augeas">
      <REPORTKEYS>
        <VALUE id="dcf1f0d5-80e5-42f6-8844-280822a9af17">key</VALUE>
      </REPORTKEYS>
    </SECTION>
    <SECTION component="true" multivalued="true" id="ff4ca248-2e26-445c-9cfc-f49d9687c0c6" name="Variable string match">
      <REPORTKEYS>
        <VALUE id="ff4ca248-2e26-445c-9cfc-f49d9687c0c6">audit_ini.key</VALUE>
      </REPORTKEYS>
    </SECTION>
  </SECTIONS>
</TECHNIQUE>
 */

// metadata(policy: Policy)
pub fn metadata() -> Result<String> {
    let mut buffer = Vec::new();
    let writer = Writer::new_with_indent(&mut buffer, b' ', 2);
    let mut ser = Serializer::with_root(writer, Some("TECHNIQUE"));
    Technique {
        name: "Configure NTP".into(),
        description: Description {
            value: "This is a description".into(),
        },
    }
    .serialize(&mut ser)?;
    Ok(String::from_utf8(buffer.clone()).unwrap())
}

#[derive(Debug, PartialEq, Serialize)]
struct Technique {
    name: String,
    description: Description,
}

#[derive(Debug, PartialEq, Serialize)]
struct Description {
    value: String,
}

#[cfg(test)]
mod tests {
    use pretty_assertions::assert_eq;

    use super::*;

    #[test]
    fn it_computes_metadata_xml() {
        assert_eq!(
            metadata().unwrap(),
            r#"<TECHNIQUE name="Configure NTP"><description value="This is a description"/>
</TECHNIQUE>"#
        );
    }
}
