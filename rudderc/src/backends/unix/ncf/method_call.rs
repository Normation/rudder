// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use crate::backends::unix::cfengine::{promise::Promise, quoted, TRUE_CLASSES};

/// Helper for reporting boilerplate (reporting context + na report)
///
/// Generates a `Vec<Promise>` including:
///
/// * method call
/// * reporting context
/// * n/a report
#[derive(Default, Debug)]
pub struct MethodCall {
    // TODO check if correct
    resource: String,
    // TODO check if correct
    state: String,
    method_alias: Option<String>,
    // TODO check list of parameters
    parameters: Vec<String>,
    report_component: String,
    report_parameter: String,
    condition: String,
    id: String,
    disable_reporting: bool,
    // Generated from
    source: String,
    is_supported: bool,
}

impl MethodCall {
    pub fn new() -> Self {
        Self {
            condition: "true".to_string(),
            ..Self::default()
        }
    }

    pub fn resource(self, resource: String) -> Self {
        Self { resource, ..self }
    }

    pub fn state(self, state: String) -> Self {
        Self { state, ..self }
    }

    pub fn id(self, id: String) -> Self {
        Self { id, ..self }
    }
    pub fn disable_reporting(self, disable_reporting: bool) -> Self {
        Self {
            disable_reporting,
            ..self
        }
    }

    pub fn alias(self, method_alias: Option<String>) -> Self {
        Self {
            method_alias,
            ..self
        }
    }

    pub fn parameters(self, parameters: Vec<String>) -> Self {
        Self { parameters, ..self }
    }

    pub fn source(self, source: String) -> Self {
        Self { source, ..self }
    }

    pub fn report_parameter(self, report_parameter: String) -> Self {
        Self {
            report_parameter,
            ..self
        }
    }

    pub fn report_component(self, report_component: String) -> Self {
        Self {
            report_component,
            ..self
        }
    }

    pub fn condition(self, condition: String) -> Self {
        Self { condition, ..self }
    }

    pub fn supported(self, is_supported: bool) -> Self {
        Self {
            is_supported,
            ..self
        }
    }

    pub fn build(mut self) -> Vec<Promise> {
        // EXCEPTION: reunite variable_string_escaped yaml parameters that appear to be joined from unix side
        if self.resource == "variable" && self.state == "string_escaped" {
            let mut parameters = Vec::new();
            for p in self.parameters.clone() {
                let param = p
                    .strip_prefix('\"')
                    .and_then(|stripped_p| stripped_p.strip_suffix('\"'))
                    .unwrap(); // parameters are quoted so unwrap will work without issue
                parameters.push(param.to_owned());
            }
            let merged_parameters = parameters.join(".");
            self.parameters = vec![quoted(&merged_parameters)];
            self.report_parameter = merged_parameters;
        }

        assert!(!self.resource.is_empty());
        assert!(!self.state.is_empty());
        assert!(!self.report_parameter.is_empty());
        assert!(!self.report_parameter.is_empty());

        // Does the method have a real condition?
        let has_condition = !TRUE_CLASSES.iter().any(|c| c == &self.condition);

        // Reporting context

        let id = self.id.as_str();
        let enable_report = Promise::usebundle(
            "enable_reporting",
            Some(&self.report_component),
            Some(id),
            vec![],
        );
        let disable_report = Promise::usebundle(
            "disable_reporting",
            Some(&self.report_component),
            Some(id),
            vec![],
        );

        let reporting_context = Promise::usebundle(
            "_method_reporting_context_v4",
            Some(&self.report_component),
            Some(id),
            vec![
                quoted(&self.report_component),
                quoted(&self.report_parameter),
                quoted(id),
            ],
        )
        .comment(&self.report_component)
        .comment("")
        .comment("source:")
        .comment(&self.source)
        .comment("");

        let formatted_bundle = match self.method_alias {
            Some(alias) => alias,
            None => format!("{}_{}", self.resource, self.state),
        };
        // Actual method call
        let method = Promise::usebundle(
            formatted_bundle,
            Some(&self.report_component),
            Some(id),
            self.parameters,
        );
        let na_condition = format!(
            "canonify(\"${{class_prefix}}_{}_{}_{}\")",
            self.resource, self.state, self.report_parameter
        );

        let mut bundles = match (has_condition, self.is_supported) {
            (true, true) => vec![
                reporting_context,
                method.if_condition(self.condition.clone()),
                // NA report
                Promise::usebundle("_classes_noop", Some(&self.report_component), Some(id), vec![na_condition.clone()]).unless_condition(&self.condition),
                Promise::usebundle("log_rudder", Some(&self.report_component),  Some(id), vec![
                    quoted(&format!("Skipping method '{}' with key parameter '{}' since condition '{}' is not reached", &self.report_component, &self.report_parameter, self.condition)),
                    quoted(&self.report_parameter),
                    na_condition.clone(),
                    na_condition,
                    "@{args}".to_string()
                ]).unless_condition(&self.condition)
            ],
            (false, true) => vec![reporting_context, method],
            (_, false) => vec![
                reporting_context,
                Promise::usebundle(
                    "log_na_rudder",
                    Some(&self.report_component), Some(id),
                    vec![
                        quoted(&format!(
                            "'{}' method is not available on classic Rudder agent, skip",
                            self.report_component,
                        )),
                        quoted(&self.report_parameter),
                        quoted(&format!(
                            "${{class_prefix}}_{}_{}_{}",
                            self.resource, self.state, self.report_parameter,
                        )),
                        "@{args}".to_string(),
                    ],
                )
            ],
        };

        if self.disable_reporting {
            bundles.push(enable_report);
            let mut res = vec![disable_report];
            res.append(&mut bundles);
            res
        } else {
            bundles
        }
    }
}

#[cfg(test)]
mod tests {
    use pretty_assertions::assert_eq;

    use super::*;
    use crate::backends::unix::cfengine::bundle::Bundle;

    #[test]
    fn format_method() {
        assert_eq!(
            Bundle::agent("test").promise_group(
                MethodCall::new()
                    .id("9e828562-72b5-4fe4-92bd-b23822e682e5".to_string())
                    .resource("package".to_string())
                    .state("present".to_string())
                    .parameters(vec!["vim".to_string()])
                    .report_parameter("parameter".to_string())
                    .report_component("component".to_string())
                    .condition("debian".to_string())
                    .build()
            ).to_string(),
            "bundle agent test {\n\n  methods:\n    # component\n    # \n    # source:\n    # \n    # \n    \"9e828562-72b5-4fe4-92bd-b23822e682e5\" usebundle => _method_reporting_context_v4(\"component\", \"parameter\", \"9e828562-72b5-4fe4-92bd-b23822e682e5\");\n    \"9e828562-72b5-4fe4-92bd-b23822e682e5\" usebundle => log_na_rudder(\"'component' method is not available on classic Rudder agent, skip\", \"parameter\", \"${class_prefix}_package_present_parameter\", @{args});\n\n}"        );
    }
}
