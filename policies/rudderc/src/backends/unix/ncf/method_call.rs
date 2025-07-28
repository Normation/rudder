// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

//! The difference with `Method` is that this one represents the call in the
//! "ncf/cfengine" model, different on Windows.
//!
//! It trusts its input (which should have already validated the method
//! signature, type and constraints).

use anyhow::{Context, Result, bail};
use rudder_commons::{canonify, methods::method::Agent};

use crate::ir::technique::ForeachResolvedState;
use crate::{
    backends::unix::{
        cfengine::{
            bundle::Bundle, cfengine_canonify_condition, cfengine_escape, expanded,
            promise::Promise, quoted,
        },
        ncf::dry_run_mode,
    },
    ir::{
        condition::Condition,
        technique::{LeafReportingMode, Method, TechniqueId},
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
pub fn method_call(
    technique_id: &TechniqueId,
    m: Method,
    condition: Condition,
) -> Result<(Promise, Option<Bundle>)> {
    assert!(!m.name.is_empty());

    let info = m.info.context(format!(
        "Could not find any 'MethodInfo' for method {}",
        m.method
    ))?;
    let id = m.id.as_ref();
    let c_id = canonify(id);

    let condition = condition.and(&m.condition);

    let report_component = cfengine_escape(&m.name);
    let is_supported = info.agent_support.contains(&Agent::CfengineCommunity);
    let method_name = &m.info.unwrap().name;

    let Some(report_parameter) = m
        .params
        .get(&info.class_parameter)
        .map(|p| cfengine_escape(p))
    else {
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

    let enable_report = Promise::usebundle("enable_reporting", Some(&report_component), vec![]);
    let disable_report = Promise::usebundle("disable_reporting", Some(&report_component), vec![]);

    let reporting_context = Promise::usebundle(
        "_method_reporting_context_v4",
        Some(&report_component),
        vec![expanded("c_name"), expanded("c_key"), expanded("report_id")],
    );
    let define_noop = Promise::usebundle(
        "_classes_noop",
        Some(&report_component),
        vec![quoted("${report_data.method_id}")],
    );

    // Actual method call
    let method = Promise::usebundle(
        &info.bundle_name,
        Some(&report_component),
        parameters_names
            .iter()
            .map(|p| expanded(p.as_str()))
            .collect(),
    );

    let push_policy_mode = dry_run_mode::push_policy_mode(m.policy_mode_override);
    let pop_policy_mode = dry_run_mode::pop_policy_mode(m.policy_mode_override);
    let incall_condition = "${method_call_condition}".to_string();

    let mut promises = match (&condition, is_supported) {
        (Condition::Expression(_), true) => vec![
            Some(reporting_context),
            push_policy_mode,
            Some(method.if_condition(incall_condition.clone())),
            pop_policy_mode,
            Some(define_noop.unless_condition(incall_condition.clone())),
            Some(Promise::usebundle("log_rudder_v4", Some(&report_component), vec![
                quoted("${c_key}"),
                quoted(&format!("Skipping method '{}' with key parameter '${{c_key}}' since condition '{}' is not reached", &method_name, incall_condition)),
                quoted(""),
            ]).unless_condition(incall_condition))
        ].into_iter().flatten().collect(),
        (Condition::NotDefined, true) => vec![
            reporting_context,
            define_noop,
            Promise::usebundle("log_rudder_v4", Some(&report_component),  vec![
                quoted("${c_key}"),
                quoted(&format!("Skipping method '{}' with key parameter '${{c_key}}' since condition '{}' is not reached", &method_name, condition)),
                quoted("")
            ])
        ],
        (Condition::Defined, true) => vec![
            Some(reporting_context),
            push_policy_mode,
            Some(method),
            pop_policy_mode,
        ].into_iter().flatten().collect(),
        (_, false) => vec![
            reporting_context,
            define_noop,
            Promise::usebundle("log_rudder_v4", Some(&report_component),  vec![
                quoted("${c_key}"),
                quoted(&format!(
                    "'{}' method is not available on classic Rudder agent, skip",
                    m.name,
                )),
                quoted("")
            ])
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
    let bundle_name = format!("call_{technique_id}_{c_id}");
    let mut call_parameters = vec![
        quoted(&report_component),
        quoted(&report_parameter),
        quoted(id),
        "@{args}".to_string(),
        quoted("${class_prefix}"),
    ];
    let mut method_parameters = vec![
        "c_name".to_string(),
        "c_key".to_string(),
        "report_id".to_string(),
        "args".to_string(),
        "class_prefix".to_string(),
    ];
    if let Condition::Expression(_) = condition {
        call_parameters.push(cfengine_canonify_condition(condition.as_ref()));
        method_parameters.push("method_call_condition".to_string());
    }

    call_parameters.append(&mut parameters);
    let bundle_call =
        Promise::usebundle(bundle_name.clone(), None, call_parameters).if_condition("pass3");

    // Get everything together
    let mut specific_parameters = parameters_names;
    method_parameters.append(&mut specific_parameters);
    Ok((
        bundle_call,
        match m.resolved_foreach_state {
            //Some(ForeachResolvedState::Virtual) => None,
            Some(ForeachResolvedState::Virtual) => Some(
                Bundle::agent(bundle_name)
                    .parameters(method_parameters)
                    .promise_group(bundle_content),
            ),
            Some(ForeachResolvedState::Main) => Some(
                Bundle::agent(bundle_name)
                    .parameters(method_parameters)
                    .promise_group(bundle_content),
            ),
            None => Some(
                Bundle::agent(bundle_name)
                    .parameters(method_parameters)
                    .promise_group(bundle_content),
            ),
        },
    ))
}
