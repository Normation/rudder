// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

//! Generation of metadata.xml, shared by different backends
//!
//! Contains information about expected reports for the Rudder app
//!
//! WARNING: We do not implement the format exhaustively but only the parts we need to
//! generate our metadata file. There are parts that are probably optional, and others missing.

use anyhow::Result;
use quick_xml::{se::Serializer, Writer};
use rudder_commons::{Target, ALL_TARGETS};
use serde::Serialize;

use crate::{
    backends::Backend,
    ir,
    ir::resource::{Id, ResourceKind},
};

pub struct Metadata;

impl Backend for Metadata {
    fn generate(&self, technique: ir::Technique) -> Result<String> {
        let technique: Technique = technique.into();
        Self::xml(technique)
    }
}

impl Metadata {
    fn xml(technique: Technique) -> Result<String> {
        let mut buffer = Vec::new();
        // FIXME indent does not seem to work
        let writer = Writer::new_with_indent(&mut buffer, b' ', 2);
        let mut ser = Serializer::with_root(writer, Some("TECHNIQUE"));
        technique.serialize(&mut ser)?;
        Ok(String::from_utf8(buffer.clone())?)
    }
}

impl From<ir::Technique> for Technique {
    fn from(src: ir::Technique) -> Self {
        let sections: Vec<Section> = Section::from(src.resources.clone());
        let agents = ALL_TARGETS
            .iter()
            .map(|t| Agent::from(src.resources.clone(), *t))
            .collect();

        Technique {
            name: src.name,
            description: src.description.map(|d| Description { value: d }),
            // false is for legacy techniques, we only use the modern reporting
            use_method_reporting: UseMethodReporting { value: true },
            agent: agents,
            sections: Sections { section: sections },
        }
    }
}

// For examples of serde serialization in quick-xml:
// https://github.com/tafia/quick-xml/blob/master/tests/serde-se.rs

#[derive(Debug, PartialEq, Serialize)]
struct Technique {
    name: String,
    description: Option<Description>,
    #[serde(rename = "usemethodreporting")]
    use_method_reporting: UseMethodReporting,
    agent: Vec<Agent>,
    sections: Sections,
}

#[derive(Debug, PartialEq, Serialize)]
struct Description {
    #[serde(rename = "$value")]
    value: String,
}

#[derive(Debug, PartialEq, Serialize)]
struct UseMethodReporting {
    #[serde(rename = "$value")]
    value: bool,
}

#[derive(Debug, PartialEq, Serialize)]
struct Agent {
    #[serde(rename = "type")]
    _type: String,
    bundles: Bundles,
    files: Files,
}

impl Agent {
    /// Types used in metadata.xml
    fn type_from_target(t: Target) -> &'static str {
        match t {
            Target::Unix => "cfengine-community,cfengine-nova",
            Target::Windows => "dsc",
            Target::Docs | Target::Metadata => unreachable!(),
        }
    }

    fn from(_resources: Vec<ResourceKind>, target: Target) -> Agent {
        Agent {
            _type: Self::type_from_target(target).to_string(),
            // FIXME we need bundle names from methods metadata
            bundles: Bundles {
                name: vec![Name {
                    value: "package_present".to_string(),
                }],
            },
            files: Files { file: vec![] },
        }
    }
}

#[derive(Debug, PartialEq, Serialize)]
struct Files {
    file: Vec<File>,
}

#[derive(Debug, PartialEq, Serialize)]
struct File {
    name: String,
    included: Included,
    #[serde(rename = "outpath")]
    out_path: Option<OutPath>,
}

#[derive(Debug, PartialEq, Serialize)]
struct Included {
    #[serde(rename = "$value")]
    value: bool,
}

#[derive(Debug, PartialEq, Serialize)]
struct OutPath {
    #[serde(rename = "$value")]
    value: String,
}

#[derive(Debug, PartialEq, Serialize)]
struct Bundles {
    name: Vec<Name>,
}

#[derive(Debug, PartialEq, Serialize)]
struct Name {
    #[serde(rename = "$value")]
    value: String,
}

#[derive(Debug, PartialEq, Serialize)]
struct Sections {
    section: Vec<Section>,
}

#[derive(Debug, PartialEq, Serialize)]
struct Section {
    name: String,
    id: Id,
    component: bool,
    multivalued: bool,
    value: Vec<ReportKey>,
    section: Vec<Section>,
}

impl Section {
    fn from(resources: Vec<ResourceKind>) -> Vec<Self> {
        // recursive function to handle blocks. Block recursion levels shouldn't be a
        // stack overflow threat.
        resources
            .into_iter()
            .map(|r| match r {
                ResourceKind::Block(b) => Section {
                    name: b.name,
                    id: b.id,
                    component: true,
                    multivalued: true,
                    value: vec![],
                    section: Section::from(b.resources),
                },
                ResourceKind::Method(m) => Section {
                    name: m.name,
                    id: m.id.clone(),
                    component: true,
                    multivalued: true,
                    // FIXME ReportKey values
                    value: vec![ReportKey {
                        value: "key".to_string(),
                        id: m.id,
                    }],
                    section: vec![],
                },
                ResourceKind::Resource(r) => Section {
                    name: r.name,
                    id: r.id.clone(),
                    component: true,
                    multivalued: true,
                    // FIXME ReportKey values
                    value: vec![ReportKey {
                        value: "key".to_string(),
                        id: r.id,
                    }],
                    section: vec![],
                },
            })
            .collect()
    }
}

#[derive(Debug, PartialEq, Serialize)]
struct ReportKey {
    #[serde(rename = "$value")]
    value: String,
    id: Id,
}

#[cfg(test)]
mod tests {
    use std::str::FromStr;

    use pretty_assertions::assert_eq;

    use super::*;

    #[test]
    fn it_computes_metadata_xml() {
        let t =   Technique {
            name: "Configure NTP".into(),
            description: Some(Description {
                value: "This is a description".into(),
            }),
            use_method_reporting: UseMethodReporting {
                value: true,
            },
            agent: vec![
                Agent {
                    _type: "dsc".to_string(),
                    bundles: Bundles {
                        name: vec![Name {
                            value: "package_present".to_string(),
                        }],
                    },
                    files: Files {
                        file: vec![
                            File { name: "RUDDER_CONFIGURATION_REPOSITORY/techniques/ncf_techniques/Audit_config_values/1.0/technique.ps1".to_string(),
                                included: Included {value: true},
                                out_path: None,}
                        ],
                    },
                },
                Agent {
                    _type: "cfengine-community,cfengine-nova".to_string(),
                    bundles: Bundles {
                        name: vec![Name {
                            value: "Package-Present".to_string(),
                        }],
                    },
                    files: Files {
                        file: vec![
                            File {
                                name: "RUDDER_CONFIGURATION_REPOSITORY/techniques/ncf_techniques/Audit_config_values/1.0/technique.cf".to_string(),
                                included: Included {value: true},
                                out_path: Some(
                                    OutPath {
                                        value: "CIS_5_OS_Services/1.0/resources/rudder-square.png".to_string()
                                    }
                                )
                            }
                        ],
                    },
                },
            ],
            sections: Sections {
                section: vec![Section {
                    name: "Variable string match".to_string(),
                    id: Id::from_str("1e65ca00-6c33-4455-94b1-3a3c9f6eb36f").unwrap(),
                    component: true,
                    multivalued: true,
                    value: vec![ReportKey {
                        value: "key".to_string(), id: Id::from_str("5dbfb761-f15f-40b5-9f75-b3d88b81483e").unwrap(),
                    }],
                    section: vec![Section {
                        name: "Variable dict".to_string(),
                        id: Id::from_str("f6810347-f367-4465-96be-3a50860f4cb1").unwrap(),
                        component: true,
                        multivalued: true,
                        value: vec![],
                        section: vec![],
                    }],
                }],
            },
        };
        assert_eq!(
            Metadata::xml(t).unwrap(),
            r#"<TECHNIQUE name="Configure NTP"><description>This is a description</description><usemethodreporting>true</usemethodreporting><agent type="dsc"><bundles><name>package_present</name></bundles><files><file name="RUDDER_CONFIGURATION_REPOSITORY/techniques/ncf_techniques/Audit_config_values/1.0/technique.ps1"><included>true</included></file></files></agent><agent type="cfengine-community,cfengine-nova"><bundles><name>Package-Present</name></bundles><files><file name="RUDDER_CONFIGURATION_REPOSITORY/techniques/ncf_techniques/Audit_config_values/1.0/technique.cf"><included>true</included><outpath>CIS_5_OS_Services/1.0/resources/rudder-square.png</outpath></file></files></agent><sections><section name="Variable string match" id="1e65ca00-6c33-4455-94b1-3a3c9f6eb36f" component="true" multivalued="true"><value id="5dbfb761-f15f-40b5-9f75-b3d88b81483e">key</value><section name="Variable dict" id="f6810347-f367-4465-96be-3a50860f4cb1" component="true" multivalued="true"/></section></sections>
</TECHNIQUE>"#
        );
    }
}
