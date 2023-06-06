// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

//! Generation of metadata.xml, shared by different backends
//!
//! Contains information about expected reports for the Rudder app
//!
//! WARNING: We do not implement the format exhaustively but only the parts we need to
//! generate our metadata file. There are parts that are could be made optional, and others are missing.

use std::path::Path;

use anyhow::{Error, Result};
use quick_xml::se::Serializer;
use rudder_commons::{Target, ALL_TARGETS};
use serde::Serialize;

use crate::{
    backends::{Backend, Windows},
    ir,
    ir::technique::{Id, ItemKind, LeafReportingMode},
    RESOURCES_DIR,
};

pub struct Metadata;

impl Backend for Metadata {
    fn generate(
        &self,
        technique: ir::Technique,
        resources: &Path,
        _standalone: bool,
    ) -> Result<String> {
        let technique: Technique = (technique, resources).try_into()?;
        Self::xml(technique)
    }
}

impl Metadata {
    fn xml(technique: Technique) -> Result<String> {
        let mut out = String::new();
        let mut ser = Serializer::with_root(&mut out, Some("TECHNIQUE")).unwrap();
        ser.indent(' ', 2);
        technique.serialize(ser)?;
        Ok(out)
    }
}

impl TryFrom<(ir::Technique, &Path)> for Technique {
    type Error = Error;

    fn try_from(data: (ir::Technique, &Path)) -> Result<Self> {
        let (src, resources) = data;
        let files = Metadata::list_resources(resources)?;

        let agent = ALL_TARGETS
            .iter()
            .map(|t| {
                Agent::from(
                    src.id.to_string(),
                    src.version.to_string(),
                    *t,
                    files.clone(),
                )
            })
            .collect();
        let multi_instance = !src.parameters.is_empty();
        let policy_generation = if src.parameters.is_empty() {
            // merged for compatibility?
            "separated"
        } else {
            "separated-with-parameters"
        };

        // First parse block et reports sections
        let mut sections: Vec<SectionType> = SectionType::from(src.items.clone());
        // Now let's add INPUT sections
        let input: Vec<Input> = src
            .parameters
            .into_iter()
            .map(|p| Input {
                name: p.id.to_string().to_uppercase(),
                description: Some(p.name),
                long_description: p.description,
                constraint: Constraint {
                    _type: "textarea".to_string(),
                    may_be_empty: p.may_be_empty,
                },
            })
            .collect();
        if !input.is_empty() {
            let section = SectionInput {
                name: "Technique parameters".to_string(),
                input,
            };
            sections.push(SectionType::SectionInput(section));
        }

        Ok(Technique {
            name: src.name,
            description: src.description,
            // false is for legacy techniques, we only use the modern reporting
            use_method_reporting: true,
            agent,
            multi_instance,
            policy_generation: policy_generation.to_string(),
            sections: Sections { section: sections },
        })
    }
}

#[derive(Debug, PartialEq, Serialize)]
#[serde(rename_all = "UPPERCASE")]
struct Technique {
    #[serde(rename = "@name")]
    name: String,
    description: Option<String>,
    #[serde(rename = "USEMETHODREPORTING")]
    use_method_reporting: bool,
    #[serde(rename = "MULTIINSTANCE")]
    multi_instance: bool,
    #[serde(rename = "POLICYGENERATION")]
    policy_generation: String,
    agent: Vec<Agent>,
    sections: Sections,
}

#[derive(Debug, PartialEq, Serialize)]
#[serde(rename_all = "UPPERCASE")]
struct Agent {
    #[serde(rename = "@type")]
    _type: String,
    bundles: Bundle,
    files: Files,
}

impl Agent {
    /// Types used in metadata.xml
    fn type_from_target(t: Target) -> &'static str {
        match t {
            Target::Unix => "cfengine-community",
            Target::Windows => "dsc",
        }
    }

    fn from(
        technique_name: String,
        technique_version: String,
        target: Target,
        attached_files: Vec<String>,
    ) -> Agent {
        let name = match target {
            Target::Unix => technique_name.clone(),
            Target::Windows => Windows::technique_name(&technique_name),
        };

        let mut files = vec![File {
            name: format!("technique.{}", target.extension()),
            included: true,
            out_path: None,
        }];
        for file in attached_files {
            let path = format!("{RESOURCES_DIR}/{file}");
            let outpath = format!("{technique_name}/{technique_version}/{path}");
            files.push(File {
                name: path.clone(),
                included: false,
                out_path: Some(outpath),
            })
        }

        Agent {
            _type: Self::type_from_target(target).to_string(),
            bundles: Bundle { name },
            files: Files { file: files },
        }
    }
}

#[derive(Debug, PartialEq, Serialize)]
#[serde(rename_all = "UPPERCASE")]
struct Files {
    file: Vec<File>,
}

#[derive(Debug, PartialEq, Serialize)]
#[serde(rename_all = "UPPERCASE")]
struct File {
    #[serde(rename = "@name")]
    name: String,
    included: bool,
    #[serde(rename = "OUTPATH")]
    #[serde(skip_serializing_if = "Option::is_none")]
    out_path: Option<String>,
}

#[derive(Debug, PartialEq, Serialize)]
#[serde(rename_all = "UPPERCASE")]
struct Bundle {
    name: String,
}

#[derive(Debug, PartialEq, Serialize)]
#[serde(rename_all = "UPPERCASE")]
struct Sections {
    section: Vec<SectionType>,
}

#[derive(Debug, PartialEq, Serialize)]
#[serde(untagged)]
enum SectionType {
    Section(Section),
    SectionBlock(SectionBlock),
    SectionInput(SectionInput),
}

#[derive(Debug, PartialEq, Serialize)]
#[serde(rename_all = "UPPERCASE")]
struct Section {
    #[serde(rename = "@name")]
    name: String,
    #[serde(rename = "@id")]
    id: Id,
    #[serde(rename = "@component")]
    component: bool,
    #[serde(rename = "@multivalued")]
    multivalued: bool,
    #[serde(rename = "REPORTKEYS")]
    report_keys: ReportKeys,
}

#[derive(Debug, PartialEq, Serialize)]
#[serde(rename_all = "UPPERCASE")]
struct SectionBlock {
    #[serde(rename = "@name")]
    name: String,
    #[serde(rename = "@reporting")]
    reporting: String,
    #[serde(rename = "@component")]
    component: bool,
    #[serde(rename = "@multivalued")]
    multivalued: bool,
    section: Vec<SectionType>,
}

#[derive(Debug, PartialEq, Serialize)]
#[serde(rename_all = "UPPERCASE")]
struct SectionInput {
    #[serde(rename = "@name")]
    name: String,
    input: Vec<Input>,
}

#[derive(Debug, PartialEq, Serialize)]
#[serde(rename_all = "UPPERCASE")]
struct Input {
    // actually, the id
    name: String,
    // actually, the name
    description: Option<String>,
    #[serde(rename = "LONGDESCRIPTION")]
    // actually, the description
    long_description: Option<String>,
    constraint: Constraint,
}

#[derive(Debug, PartialEq, Serialize)]
#[serde(rename_all = "UPPERCASE")]
struct Constraint {
    #[serde(rename = "TYPE")]
    _type: String,
    #[serde(rename = "MAYBEEMPTY")]
    may_be_empty: bool,
}

impl SectionType {
    fn from(modules: Vec<ItemKind>) -> Vec<Self> {
        // recursive function to handle blocks. Block recursion levels shouldn't be a
        // stack overflow threat.
        modules
            .into_iter()
            .filter_map(|r| match r {
                ItemKind::Block(b) => Some(SectionType::SectionBlock(SectionBlock {
                    name: b.name,
                    component: true,
                    multivalued: true,
                    section: SectionType::from(b.items),
                    reporting: b.reporting.to_string(),
                })),
                ItemKind::Method(m) => {
                    if m.reporting.mode == LeafReportingMode::Disabled || m.method.starts_with('_')
                    {
                        None
                    } else {
                        Some(SectionType::Section(Section {
                            name: m.name,
                            id: m.id.clone(),
                            component: true,
                            multivalued: true,
                            report_keys: ReportKeys {
                                value: ReportKey {
                                    // the class_parameter value
                                    value: m
                                        .params
                                        .get(&m.info.unwrap().class_parameter)
                                        .unwrap()
                                        .clone(),
                                    id: m.id,
                                },
                            },
                        }))
                    }
                }
                ItemKind::Module(_) => todo!(),
            })
            .collect()
    }
}

#[derive(Debug, PartialEq, Serialize)]
#[serde(rename_all = "UPPERCASE")]
struct ReportKeys {
    value: ReportKey,
}

#[derive(Debug, PartialEq, Serialize)]
#[serde(rename_all = "UPPERCASE")]
struct ReportKey {
    #[serde(rename = "$value")]
    value: String,
    #[serde(rename = "@id")]
    id: Id,
}

#[cfg(test)]
mod tests {
    use std::{fs::read_to_string, str::FromStr};

    use pretty_assertions::assert_eq;

    use super::*;

    #[test]
    fn it_computes_metadata_xml() {
        let sections = Sections {
            section: vec![SectionType::SectionBlock(SectionBlock {
                name: "Variable string match".to_string(),
                component: true,
                multivalued: true,
                reporting: "weighted".to_string(),
                section: vec![
                    SectionType::Section(Section {
                        name: "Variable dict".to_string(),
                        id: Id::from_str("f6810347-f367-4465-96be-3a50860f4cb1").unwrap(),
                        component: true,
                        multivalued: true,
                        report_keys: ReportKeys {
                            value: ReportKey {
                                value: "key".to_string(),
                                id: Id::from_str("5dbfb761-f15f-40b5-9f75-b3d88b81483e").unwrap(),
                            },
                        },
                    }),
                    SectionType::SectionInput(SectionInput {
                        name: "Technique parameters".to_string(),
                        input: vec![Input {
                            name: "server".to_string(),
                            description: Some("My parameter".to_string()),
                            long_description: Some("My interesting parameter".to_string()),
                            constraint: Constraint {
                                _type: "string".to_string(),
                                may_be_empty: false,
                            },
                        }],
                    }),
                ],
            })],
        };
        let agent = vec![
            Agent {
                _type: "dsc".to_string(),
                bundles: Bundle {
                    name: "package_present".to_string(),
                },
                files: Files {
                    file: vec![
                        File {
                            name: "RUDDER_CONFIGURATION_REPOSITORY/techniques/ncf_techniques/Audit_config_values/1.0/technique.ps1".to_string(),
                            included: true,
                            out_path: None,
                        }
                    ],
                },
            },
            Agent {
                _type: "cfengine-community,cfengine-nova".to_string(),
                bundles: Bundle {
                    name: "Package-Present".to_string(),
                },
                files: Files {
                    file: vec![
                        File {
                            name: "RUDDER_CONFIGURATION_REPOSITORY/techniques/ncf_techniques/Audit_config_values/1.0/technique.cf".to_string(),
                            included: true,
                            out_path: Some("CIS_5_OS_Services/1.0/modules/rudder-square.png".to_string()
                            ),
                        }
                    ],
                },
            },
        ];

        let t = Technique {
            name: "Configure NTP".into(),
            description: Some("This is a description".into()),
            use_method_reporting: true,
            multi_instance: true,
            policy_generation: "separated-with-parameters".to_string(),
            agent,
            sections,
        };
        assert_eq!(
            read_to_string("tests/metadata/serialized.xml").unwrap(),
            Metadata::xml(t).unwrap()
        );
    }
}
