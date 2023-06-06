// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

//! The difference with `Method` is that this one represents the call in the
//! "ncf/cfengine" model, different on Windows.
//!
//! It trusts its input (which should have already validated the method
//! signature, type, and constraints).

use std::convert::TryFrom;

use anyhow::{bail, Error};
use rudder_commons::canonify;

use crate::{
    backends::unix::cfengine::{
        bundle::Bundle, cfengine_escape, expanded, promise::Promise, quoted,
    },
    frontends::methods::method::Agent,
    ir::{
        condition::Condition,
        technique::{LeafReportingMode, Method},
    },
};

/// Includes reporting boilerplate (reporting context + na report)
///
/// Generates a `Bundle` including:
///
/// * method call
/// * reporting context
/// * n/a report
///
/// Plus a calling `Promise`
impl TryFrom<Method> for (Promise, Bundle) {
    type Error = Error;

    fn try_from(m: Method) -> Result<Self, Self::Error> {
        assert!(!m.name.is_empty());

        let info = m.info.unwrap();
        let id = m.id.as_ref();
        let unique = &format!("{}_${{report_data.directive_id}}", m.id.as_ref());
        let c_id = canonify(id);

        let report_component = cfengine_escape(&m.name);
        let is_supported = info.agent_support.contains(&Agent::CfengineCommunity);
        let method_name = &m.info.unwrap().name;

        let Some(report_parameter) = m
            .params
            .get(&info.class_parameter) else {
            bail!("Missing parameter {}", info.class_parameter)
        };

        // parameters names
        let parameters_names: Vec<String> = info.parameter.iter().map(|p| p.name.clone()).collect();

        // parameters values
        let mut parameters = vec![];
        for p in &info.parameter {
            parameters.push(match m.params.get(&p.name) {
                Some(p) => quoted(&cfengine_escape(p)),
                _ => bail!("Missing parameter {}", p.name),
            })
        }

        let enable_report = Promise::usebundle(
            "enable_reporting",
            Some(&report_component),
            Some(unique),
            vec![],
        );
        let disable_report = Promise::usebundle(
            "disable_reporting",
            Some(&report_component),
            Some(unique),
            vec![],
        );

        let reporting_context = Promise::usebundle(
            "_method_reporting_context_v4",
            Some(&report_component),
            Some(unique),
            vec![expanded("c_name"), expanded("c_key"), expanded("report_id")],
        );

        // Actual method call
        let method = Promise::usebundle(
            &info.bundle_name,
            Some(&report_component),
            Some(unique),
            parameters_names
                .iter()
                .map(|p| expanded(p.as_str()))
                .collect(),
        );
        let na_condition = format!(
            "canonify(\"${{class_prefix}}_{}_{}\")",
            info.bundle_name, &report_parameter
        );

        let mut promises = match (&m.condition, is_supported) {
            (Condition::Expression(_), true) => vec![
                reporting_context,
                method.if_condition(m.condition.clone()),
                Promise::usebundle("_classes_noop", Some(&report_component), Some(unique), vec![na_condition.clone()]).unless_condition(&m.condition),
                Promise::usebundle("log_rudder", Some(&report_component),  Some(unique), vec![
                    quoted(&format!("Skipping method '{}' with key parameter '{}' since condition '{}' is not reached", &method_name, &report_parameter, m.condition)),
                    quoted(report_parameter),
                    na_condition.clone(),
                    na_condition,
                    "@{args}".to_string()
                ]).unless_condition(&m.condition)
            ],
            (Condition::NotDefined, true) => vec![
                reporting_context,
                Promise::usebundle("_classes_noop", Some(&report_component), Some(unique), vec![na_condition.clone()]),
                Promise::usebundle("log_rudder", Some(&report_component),  Some(unique), vec![
                    quoted(&format!("Skipping method '{}' with key parameter '{}' since condition '{}' is not reached", &method_name, &report_parameter, m.condition)),
                    quoted(report_parameter),
                    na_condition.clone(),
                    na_condition,
                    "@{args}".to_string()
                ])
            ],
            (Condition::Defined, true) => vec![reporting_context, method],
            (_, false) => vec![
                reporting_context,
                Promise::usebundle(
                    "log_na_rudder",
                    Some(&report_component), Some(unique),
                    vec![
                        quoted(&format!(
                            "'{}' method is not available on classic Rudder agent, skip",
                            report_parameter,
                        )),
                        quoted(report_parameter),
                        quoted(unique),
                        "@{args}".to_string(),
                    ],
                )
            ],
        };
        let bundle_content = match m.reporting.mode {
            LeafReportingMode::Disabled => {
                let mut res = vec![disable_report];
                res.append(&mut promises);
                res.push(enable_report);
                res
            }
            LeafReportingMode::Enabled => promises,
        };

        // Calling bundle
        let bundle_name = format!("call_{}", c_id);
        let mut call_parameters = vec![
            quoted(&cfengine_escape(&report_component)),
            quoted(&cfengine_escape(report_parameter)),
            quoted(id),
            "@{args}".to_string(),
            quoted("${class_prefix}"),
        ];
        call_parameters.append(&mut parameters);
        let bundle_call =
            Promise::usebundle(bundle_name.clone(), None, Some(unique), call_parameters);

        // Get everything together
        let mut method_parameters = vec![
            "c_name".to_string(),
            "c_key".to_string(),
            "report_id".to_string(),
            "args".to_string(),
            "class_prefix".to_string(),
        ];
        let mut specific_parameters = parameters_names;
        method_parameters.append(&mut specific_parameters);
        Ok((
            bundle_call,
            Bundle::agent(bundle_name)
                .parameters(method_parameters)
                .promise_group(bundle_content),
        ))
    }
}
