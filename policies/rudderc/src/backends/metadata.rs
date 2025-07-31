// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

//! Generation of metadata.xml, shared by different backends
//!
//! Contains information about expected reports for the Rudder app
//!
//! WARNING: We do not implement the format exhaustively but only the parts we need to
//! generate our metadata file. There are parts that could be made optional, and others are missing.

use std::path::Path;

use anyhow::{Error, Result};
use quick_xml::se::{QuoteLevel, Serializer};
use rudder_commons::{ALL_TARGETS, Target};
use serde::Serialize;

use crate::{
    RESOURCES_DIR,
    backends::{Backend, Windows},
    ir,
    ir::technique::{Id, ItemKind, LeafReportingMode, Parameter, ParameterType},
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
        ser.set_quote_level(QuoteLevel::Full);
        ser.indent(' ', 2);
        technique.serialize(ser)?;
        Ok(out)
    }
}

fn parameter_type_to_metadata(p: ParameterType) -> &'static str {
    match p {
        ParameterType::Boolean => "boolean",
        ParameterType::String => "string",
        ParameterType::MultilineString => "textarea",
        ParameterType::Json => "textarea",
        ParameterType::Yaml => "textarea",
        ParameterType::Mail => "mail",
        ParameterType::Ip => "ip",
        ParameterType::Ipv4 => "ipv4",
        ParameterType::Ipv6 => "ipv6",
        ParameterType::Integer => "integer",
        ParameterType::SizeB => "size-b",
        ParameterType::SizeKb => "size-kb",
        ParameterType::SizeMb => "size-mb",
        ParameterType::SizeGb => "size-gb",
        ParameterType::SizeTb => "size-tb",
        ParameterType::Permissions => "perm",
        ParameterType::SharedFile => "sharedfile",
        ParameterType::Password => "password",
    }
}

impl From<Parameter> for Input {
    fn from(p: Parameter) -> Self {
        let type_constraint = parameter_type_to_metadata(p._type);

        let regex_constraint = p.constraints.regex.map(|r| Regex {
            error: r
                .error_message
                .unwrap_or(format!("Value should match regex: '{}'", r.value)),
            regex: r.value,
        });

        let password_constraint = if matches!(p._type, ParameterType::Password) {
            Some(
                p.constraints
                    .password_hashes
                    .unwrap()
                    .into_iter()
                    .map(|p| p.to_string())
                    .collect::<Vec<String>>()
                    .join(","),
            )
        } else {
            None
        };

        Self {
            name: p.id.to_string().to_uppercase(),
            variable_name: p.name.clone(),
            description: p.description.unwrap_or(p.name),
            long_description: p.documentation,
            constraint: Constraint {
                _type: type_constraint.to_string(),
                may_be_empty: if p.constraints.allow_empty {
                    Some(true)
                } else {
                    None
                },
                regex: regex_constraint,
                password_hashes: password_constraint,
                default: p.default,
            },
        }
    }
}

#[derive(Debug, PartialEq, Serialize)]
#[serde(rename_all = "UPPERCASE")]
struct SelectItem {
    label: String,
    value: String,
}

impl From<Parameter> for SelectOne {
    fn from(p: Parameter) -> Self {
        let items = if let Some(s) = p.constraints.select {
            s.into_iter()
                .map(|i| SelectItem {
                    label: i.name.unwrap_or(i.value.clone()),
                    value: i.value,
                })
                .collect()
        } else {
            vec![]
        };

        Self {
            name: p.id.to_string().to_uppercase(),
            variable_name: p.name.clone(),
            description: p.description.unwrap_or(p.name),
            long_description: p.documentation,
            item: items,
            constraint: Constraint {
                _type: parameter_type_to_metadata(p._type).to_string(),
                may_be_empty: if p.constraints.allow_empty {
                    Some(true)
                } else {
                    None
                },
                regex: None,
                password_hashes: None,
                default: p.default,
            },
        }
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
        let multi_instance = !src.params.is_empty();
        let policy_generation = if src.params.is_empty() {
            // merged for compatibility?
            "separated"
        } else {
            "separated-with-parameters"
        };

        // First parse block et reports sections
        let mut sections: Vec<SectionType> = SectionType::from(src.items.clone());
        // Now let's add INPUT sections
        let input: Vec<InputType> = src
            .params
            .into_iter()
            .map(|p| {
                if p.constraints.select.is_some() {
                    InputType::SelectOne(p.into())
                } else {
                    InputType::Input(p.into())
                }
            })
            .collect();
        if !input.is_empty() {
            let section = SectionInput {
                name: "Technique parameters".to_string(),
                section: input,
            };
            // parameters should be the first section
            sections.insert(0, SectionType::SectionInput(section));
        }

        Ok(Technique {
            name: src.name.clone(),
            description: src.description.unwrap_or(src.name),
            policy_types: src.policy_types,
            long_description: src.documentation,
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
    description: String,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    policy_types: Vec<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    long_description: Option<String>,
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
    #[serde(rename = "@id")]
    id: String,
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
enum InputType {
    Input(Input),
    #[serde(rename = "SELECT1")]
    SelectOne(SelectOne),
}

#[derive(Debug, PartialEq, Serialize)]
#[serde(rename_all = "UPPERCASE")]
struct SectionInput {
    #[serde(rename = "@name")]
    name: String,
    #[serde(rename = "$value")]
    section: Vec<InputType>,
}

#[derive(Debug, PartialEq, Serialize)]
#[serde(rename_all = "UPPERCASE")]
struct Input {
    // actually, the id
    name: String,
    #[serde(rename = "VARIABLENAME")]
    // actually, the name
    variable_name: String,
    description: String,
    #[serde(rename = "LONGDESCRIPTION")]
    #[serde(skip_serializing_if = "Option::is_none")]
    long_description: Option<String>,
    constraint: Constraint,
}

#[derive(Debug, PartialEq, Serialize)]
#[serde(rename_all = "UPPERCASE")]
struct SelectOne {
    // actually, the id
    name: String,
    #[serde(rename = "VARIABLENAME")]
    // actually, the name
    variable_name: String,
    description: String,
    #[serde(rename = "LONGDESCRIPTION")]
    #[serde(skip_serializing_if = "Option::is_none")]
    long_description: Option<String>,
    item: Vec<SelectItem>,
    constraint: Constraint,
}

#[derive(Debug, PartialEq, Serialize)]
#[serde(rename_all = "UPPERCASE")]
struct Constraint {
    #[serde(rename = "TYPE")]
    _type: String,
    #[serde(rename = "MAYBEEMPTY")]
    #[serde(skip_serializing_if = "Option::is_none")]
    may_be_empty: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    regex: Option<Regex>,
    #[serde(rename = "PASSWORDHASH")]
    #[serde(skip_serializing_if = "Option::is_none")]
    password_hashes: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    default: Option<String>,
}

#[derive(Debug, PartialEq, Serialize)]
struct Regex {
    #[serde(rename = "@error")]
    error: String,
    #[serde(rename = "$value")]
    regex: String,
}

impl SectionType {
    fn from(modules: Vec<ItemKind>) -> Vec<Self> {
        // recursive function to handle blocks. Block recursion levels shouldn't be a
        // stack overflow threat.
        modules
            .into_iter()
            .filter_map(|r| match r {
                ItemKind::Block(b) => Some(SectionType::SectionBlock(SectionBlock {
                    id: b.id.to_string(),
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
                id: "6dc261b7-606b-43a7-8797-cd353763f50c".to_string(),
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
                        section: vec![InputType::Input(Input {
                            name: "server".to_string(),
                            variable_name: "My_parameter".to_string(),
                            description: "My parameter".to_string(),
                            long_description: Some("My interesting parameter".to_string()),
                            constraint: Constraint {
                                _type: "string".to_string(),
                                may_be_empty: Some(false),
                                regex: None,
                                password_hashes: None,
                                default: None,
                            },
                        })],
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
            description: "This is a description".into(),
            long_description: None,
            use_method_reporting: true,
            multi_instance: true,
            policy_generation: "separated-with-parameters".to_string(),
            policy_types: vec!["custom_policy_type".to_string(), "another_type".to_string()],
            agent,
            sections,
        };
        assert_eq!(
            read_to_string("tests/metadata/serialized.xml")
                .unwrap()
                .trim(),
            Metadata::xml(t).unwrap()
        );
    }
}
