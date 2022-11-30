// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

//! Difference with `Method` is that this one represents the call in the
//! "ncf/cfengine" model, different on Windows.
//!
//! It trusts its input (which should have already validated the method
//! signature, types and constraints).

use std::convert::TryFrom;

use anyhow::{bail, Error};
use rudder_commons::canonify;

use crate::{
    backends::unix::cfengine::{bundle::Bundle, promise::Promise, quoted},
    frontends::methods::method::Agent,
    ir::{
        condition::Condition,
        technique::{LeafReporting, Method},
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
        let c_id = canonify(id);

        let report_component = m.name.clone();
        let is_supported = info.agent_support.contains(&Agent::CfengineCommunity);

        // Reporting context
        let report_parameter = format!("${{{}}}", info.class_parameter);

        // Let's build the parameters list!
        let mut parameters = vec![];
        for p in &info.parameter {
            parameters.push(match m.params.get(&p.name) {
                Some(p) => quoted(p),
                _ => bail!("Missing parameter {}", p.name),
            })
        }

        let enable_report = Promise::usebundle(
            "enable_reporting",
            Some(&report_component),
            Some(id),
            vec![],
        );
        let disable_report = Promise::usebundle(
            "disable_reporting",
            Some(&report_component),
            Some(id),
            vec![],
        );

        let reporting_context = Promise::usebundle(
            "_method_reporting_context_v4",
            Some(&report_component),
            Some(id),
            vec![quoted(&m.name), quoted(&report_parameter), quoted(id)],
        );

        // Actual method call
        let method = Promise::usebundle(
            &info.bundle_name,
            Some(&report_component),
            Some(id),
            parameters.clone(),
        );
        let na_condition = format!(
            "canonify(\"${{class_prefix}}_{}_{}\")",
            info.bundle_name, &report_parameter
        );

        let mut promises = match (&m.condition, is_supported) {
            (Condition::Expression(_), true) => vec![
                reporting_context,
                method.if_condition(m.condition.clone()),
                Promise::usebundle("_classes_noop", Some(&report_component), Some(id), vec![na_condition.clone()]).unless_condition(&m.condition),
                Promise::usebundle("log_rudder", Some(&report_component),  Some(id), vec![
                    quoted(&format!("Skipping method '{}' with key parameter '{}' since condition '{}' is not reached", &report_component, &report_parameter, m.condition)),
                    quoted(&report_parameter),
                    na_condition.clone(),
                    na_condition,
                    "@{args}".to_string()
                ]).unless_condition(&m.condition)
            ],
            (Condition::NotDefined, true) => vec![
                reporting_context,
                Promise::usebundle("_classes_noop", Some(&report_component), Some(id), vec![na_condition.clone()]),
                Promise::usebundle("log_rudder", Some(&report_component),  Some(id), vec![
                    quoted(&format!("Skipping method '{}' with key parameter '{}' since condition '{}' is not reached", &report_component, &report_parameter, m.condition)),
                    quoted(&report_parameter),
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
                    Some(&report_component), Some(id),
                    vec![
                        quoted(&format!(
                            "'{}' method is not available on classic Rudder agent, skip",
                            report_parameter,
                        )),
                        quoted(&report_parameter),
                        quoted(&format!(
                            "${{class_prefix}}_{}_{}",
                            info.bundle_name, report_parameter,
                        )),
                        "@{args}".to_string(),
                    ],
                )
            ],
        };
        let bundle_content = match m.reporting {
            LeafReporting::Disabled => {
                let mut res = vec![disable_report];
                res.append(&mut promises);
                res.push(enable_report);
                res
            }
            LeafReporting::Enabled => promises,
        };

        let bundle_name = format!("call_{}", c_id);
        let bundle_call = Promise::usebundle(bundle_name.clone(), None, Some(&id), parameters);

        Ok((
            bundle_call,
            Bundle::agent(bundle_name)
                .parameters(info.parameter.iter().map(|p| p.name.clone()).collect())
                .promise_group(bundle_content),
        ))
    }
}
