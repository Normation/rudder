// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use std::{collections::HashMap, fmt};

/// This does not modelize full CFEngine syntax but only a subset of it, which is our
/// execution target, plus some Rudder-specific metadata in comments.

/// This subset should be safe, fast and readable (in that order).

/// Everything that does not have an effect on applied state
/// should follow a deterministic rendering process (attribute order, etc.)
/// This allows easy diff between produced files.

// No need to handle all promises and attributes, we only need to support the ones we are
// able to generate.

pub const MIN_INT: i64 = -99_999_999_999;
pub const MAX_INT: i64 = 99_999_999_999;

pub fn quoted(s: &str) -> String {
    format!("\"{}\"", s)
}

#[derive(Clone, Copy, PartialEq, Eq, Hash)]
pub enum PromiseType {
    Vars,
    Methods,
}

const NORMAL_ORDERING: [PromiseType; 2] = [PromiseType::Vars, PromiseType::Methods];

impl fmt::Display for PromiseType {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "{}",
            match self {
                PromiseType::Vars => "vars",
                PromiseType::Methods => "methods",
            }
        )
    }
}

#[derive(Clone, Copy, PartialEq, Eq, Hash)]
pub enum AttributeType {
    UseBundle,
    Unless,
    If,
    String,
    Slist,
}

const ATTRIBUTES_ORDERING: [AttributeType; 5] = [
    AttributeType::UseBundle,
    AttributeType::String,
    AttributeType::Slist,
    AttributeType::Unless,
    AttributeType::If,
];

const LONGUEST_ATTRIBUTE_LEN: usize = 9;

// TODO add rudder-lang lines to comments

impl PromiseType {
    fn allows(self, attribute_type: AttributeType) -> bool {
        match self {
            PromiseType::Vars => vec![
                AttributeType::Unless,
                AttributeType::If,
                AttributeType::String,
                AttributeType::Slist,
            ],
            PromiseType::Methods => vec![
                AttributeType::Unless,
                AttributeType::If,
                AttributeType::UseBundle,
            ],
        }
        .iter()
        .any(|a| attribute_type == *a)
    }
}

impl fmt::Display for AttributeType {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "{}",
            match self {
                AttributeType::UseBundle => "usebundle",
                AttributeType::Unless => "unless",
                AttributeType::If => "if",
                AttributeType::String => "string",
                AttributeType::Slist => "slist",
            }
        )
    }
}

#[derive(Clone, PartialEq, Eq)]
pub struct Promise {
    /// Comments in output file
    comments: Vec<String>,
    /// Type of the promise
    promise_type: PromiseType,
    /// Target of the promise
    promiser: String,
    /// What the promise does
    attributes: HashMap<AttributeType, String>,
}

/// Cosmetic simplification, skip always true conditions
const TRUE_CLASSES: [&str; 4] = ["true", "any", "concat(\"any\")", "concat(\"true\")"];

/// Cosmetic simplification, skip always true conditions
const FALSE_CLASSES: [&str; 4] = ["false", "!any", "concat(\"!any\")", "concat(\"false\")"];

/// Promise constructors should allow avoiding common mistakes
impl Promise {
    pub fn new<T: Into<String>>(promise_type: PromiseType, promiser: T) -> Self {
        Self {
            promise_type,
            promiser: promiser.into(),
            attributes: HashMap::new(),
            comments: vec![],
        }
    }

    pub fn attribute<S: AsRef<str>>(mut self, attribute_type: AttributeType, value: S) -> Self {
        // Shouldn't happen but prevent generating broken code
        assert!(self.promise_type.allows(attribute_type));

        self.attributes
            .insert(attribute_type, value.as_ref().to_string());
        self
    }

    /// Shortcut for building a string variable with a value to be quoted
    pub fn string<T: Into<String>, S: AsRef<str>>(name: T, value: S) -> Self {
        Promise::string_raw(name, quoted(value.as_ref()))
    }

    /// Shortcut for building a string variable with a raw value
    pub fn string_raw<T: Into<String>, S: AsRef<str>>(name: T, value: S) -> Self {
        Promise::new(PromiseType::Vars, name).attribute(AttributeType::String, value.as_ref())
    }

    /// Shortcut for building an slist variable with a list of values to be quoted
    pub fn slist<T: Into<String>, S: AsRef<str>>(name: T, values: Vec<S>) -> Self {
        Promise::new(PromiseType::Vars, name).attribute(
            AttributeType::Slist,
            format!(
                "{{{}}}",
                values
                    .iter()
                    .map(|v| quoted(v.as_ref()))
                    .collect::<Vec<String>>()
                    .join(", ")
            ),
        )
    }

    /// Shortcut for calling a bundle with parameters
    pub fn usebundle<T: AsRef<str>>(bundle: T, parameters: Vec<String>) -> Self {
        Promise::new(PromiseType::Methods, "method_call").attribute(
            AttributeType::UseBundle,
            format!("{}({})", bundle.as_ref(), parameters.join(", ")),
        )
    }

    /// Shortcut for adding a condition
    pub fn if_condition<T: AsRef<str>>(mut self, condition: T) -> Self {
        // Don't use always true conditions
        if !TRUE_CLASSES.iter().any(|c| c == &condition.as_ref()) {
            self.attributes.insert(
                AttributeType::If,
                format!("concat(\"{}\")", condition.as_ref()),
            );
        }
        self
    }

    /// Shortcut for adding a condition
    pub fn unless_condition<T: AsRef<str>>(mut self, condition: T) -> Self {
        // Don't use always true conditions
        if !FALSE_CLASSES.iter().any(|c| c == &condition.as_ref()) {
            self.attributes.insert(
                AttributeType::Unless,
                format!("concat(\"{}\")", condition.as_ref()),
            );
        }
        self
    }

    pub fn comment<T: AsRef<str>>(mut self, comment: T) -> Self {
        self.comments.push(comment.as_ref().to_string());
        self
    }
}

/// ID that must be unique for each technique instance. Combined with a simple index,
/// it allows enforcing all methods are called, even with identical parameters.
/// This has no semantic meaning and can almost be considered syntactic sugar.
const UNIQUE_ID: &str = "${report_data.directive_id}";
/// Indexes over three chars
const INDEX_LEN: usize = 3;
/// Length of directive id + _ + index
const UNIQUE_ID_LEN: usize = UNIQUE_ID.len() + 1 + INDEX_LEN;

impl Promise {
    /// Index is used to make methods promises unique
    /// It is ignored in other cases
    //
    // padding for arrows is promiser len + max attribute name
    fn format(&self, index: usize, padding: usize) -> String {
        let promiser = if self.promise_type == PromiseType::Methods {
            // Methods need to be unique
            format!("{}_{}", UNIQUE_ID, index)
        } else {
            self.promiser.clone()
        };

        let mut first = true;

        let comment = self
            .comments
            .iter()
            .map(|c| format!("    # {}\n", c))
            .collect::<Vec<String>>()
            .concat();

        if self.attributes.is_empty() {
            format!("{}\"{}\";", comment, self.promiser)
        } else {
            format!(
                "{}{};",
                comment,
                ATTRIBUTES_ORDERING
                    .iter()
                    .filter_map(|t| self.attributes.get(t).map(|p| (t, p)))
                    .map(|(k, v)| {
                        if first {
                            first = false;
                            format!(
                                "    {:<promiser_width$} {:>width$} => {}",
                                quoted(&promiser),
                                k.to_string(),
                                v,
                                promiser_width = padding - LONGUEST_ATTRIBUTE_LEN - 1,
                                width = LONGUEST_ATTRIBUTE_LEN
                            )
                        } else {
                            format!("    {:>width$} => {}", k.to_string(), v, width = padding)
                        }
                    })
                    .collect::<Vec<String>>()
                    .join(",\n")
            )
        }
    }
}

/// Helper for reporting boilerplate (reporting context + na report)
///
/// Generates a `Vec<Promise>` including:
///
/// * method call
/// * reporting context
/// * n/a report
#[derive(Default)]
pub struct Method {
    // TODO check if correct
    resource: String,
    // TODO check if correct
    state: String,
    // TODO check list of parameters
    parameters: Vec<String>,
    report_component: String,
    report_parameter: String,
    condition: String,
    // Generated from
    source: String,
}

impl Method {
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

    pub fn parameters(self, parameters: Vec<String>) -> Self {
        Self { parameters, ..self }
    }

    pub fn source(self, source: &str) -> Self {
        Self {
            source: source.to_string(),
            ..self
        }
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

    pub fn build(self) -> Vec<Promise> {
        assert!(!self.resource.is_empty());
        assert!(!self.state.is_empty());
        assert!(!self.report_parameter.is_empty());
        assert!(!self.report_parameter.is_empty());

        // Does the method have a real condition?
        let has_condition = !TRUE_CLASSES.iter().any(|c| c == &self.condition);

        // Reporting context
        let reporting_context = Promise::usebundle(
            "_method_reporting_context",
            vec![
                quoted(&self.report_component),
                quoted(&self.report_parameter),
            ],
        )
        .comment(format!("{}:", self.report_component,))
        .comment("")
        .comment(format!("  {}", self.source,))
        .comment("");

        // Actual method call
        let method =
            Promise::usebundle(format!("{}_{}", self.resource, self.state), self.parameters);

        if has_condition {
            let na_condition = format!(
                "canonify(\"${{class_prefix}}_{}_{}_{}\")",
                self.resource, self.state, self.report_parameter
            );

            vec![
                reporting_context.if_condition(self.condition.clone()),
                method.if_condition(self.condition.clone()),
                // NA report
                Promise::usebundle(
                    "_classes_noop",
                    vec![na_condition.clone()],
                )
                .unless_condition(&self.condition),
                Promise::usebundle("log_rudder", vec![
                    quoted(&format!("Skipping method '{}' with key parameter '{}' since condition '{}' is not reached", &self.report_component, &self.report_parameter, self.condition)),
                    quoted(&self.report_parameter),
                    na_condition.clone(),
                    na_condition,
                    "@{args}".to_string()
                ]).unless_condition(&self.condition),
            ]
        } else {
            vec![reporting_context, method]
        }
    }
}

#[derive(Clone, Copy, PartialEq, Eq, Hash)]
pub enum BundleType {
    Agent,
}

impl fmt::Display for BundleType {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "{}",
            match self {
                BundleType::Agent => "agent",
            }
        )
    }
}

// Promises of a given promise type need to be ordered specifically,
// hence the `Vec`.
// Group promises for better readability (e.g. a generic method call)
// String is a comment
type Promises = HashMap<PromiseType, Vec<Vec<Promise>>>;

#[derive(Clone, PartialEq, Eq)]
pub struct Bundle {
    bundle_type: BundleType,
    name: String,
    // Order matters!
    parameters: Vec<String>,
    promises: Promises,
}

impl Bundle {
    pub fn agent<T: Into<String>>(name: T) -> Self {
        Self {
            name: name.into(),
            bundle_type: BundleType::Agent,
            parameters: Vec::new(),
            promises: HashMap::new(),
        }
    }

    pub fn parameters(self, parameters: Vec<String>) -> Self {
        Self { parameters, ..self }
    }

    pub fn promise_group(mut self, promise_group: Vec<Promise>) -> Self {
        self.add_promise_group(promise_group);
        self
    }

    pub fn add_promise_group(&mut self, promise_group: Vec<Promise>) {
        if promise_group.is_empty() {
            return;
        }
        let promise_type = promise_group[0].promise_type;
        assert!(promise_group.iter().all(|p| p.promise_type == promise_type));
        match self.promises.get_mut(&promise_type) {
            Some(promises) => promises.push(promise_group),
            None => {
                self.promises.insert(promise_type, vec![promise_group]);
            }
        }
    }
}

impl fmt::Display for Bundle {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        writeln!(
            f,
            "bundle {} {}{} {{\n",
            self.bundle_type,
            self.name,
            if self.parameters.is_empty() {
                String::new()
            } else {
                format!("({})", self.parameters.join(", "))
            },
        )?;

        for (promise_type, promises) in NORMAL_ORDERING
            .iter()
            .filter_map(|t| self.promises.get(&t).map(|p| (t, p)))
        {
            writeln!(f, "  {}:", promise_type)?;

            for (index, group) in promises.iter().enumerate() {
                // Align promise groups
                let mut max_promiser = group.iter().map(|p| p.promiser.len()).max().unwrap_or(0);
                // Take special method promiser into account
                if *promise_type == PromiseType::Methods {
                    max_promiser = std::cmp::max(max_promiser, UNIQUE_ID_LEN);
                }

                for promise in group {
                    writeln!(
                        f,
                        "{}",
                        promise.format(index, max_promiser + LONGUEST_ATTRIBUTE_LEN + 3)
                    )?;
                }
                writeln!(f)?;
            }
        }

        write!(f, "}}")
    }
}

pub struct Policy {
    bundles: Vec<Bundle>,
    name: Option<String>,
    version: Option<String>,
}

impl Policy {
    pub fn new() -> Self {
        Self {
            name: None,
            version: None,
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

    pub fn bundle(mut self, bundle: Bundle) -> Self {
        self.bundles.push(bundle);
        self
    }
}

impl fmt::Display for Policy {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        writeln!(f, "# generated by rudderc")?;
        if self.name.is_some() {
            writeln!(f, "# @name {}", self.name.as_ref().unwrap())?;
        }
        if self.version.is_some() {
            writeln!(f, "# @version {}", self.version.as_ref().unwrap())?;
        }

        let mut sorted_bundles = self.bundles.clone();
        sorted_bundles.sort_by(|a, b| b.name.cmp(&a.name));
        for bundle in sorted_bundles {
            write!(f, "\n{}", bundle)?;
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use pretty_assertions::assert_eq;

    #[test]
    fn format_promise() {
        assert_eq!(
            Promise::new(PromiseType::Vars, "test")
                .format(0, LONGUEST_ATTRIBUTE_LEN + 3 + UNIQUE_ID_LEN),
            "\"test\";"
        );
        assert_eq!(
            Promise::new(PromiseType::Vars, "test")
                .comment("test".to_string())
                .format(0, LONGUEST_ATTRIBUTE_LEN + 3 + UNIQUE_ID_LEN),
            "    # test\n\"test\";"
        );
        assert_eq!(
            Promise::string("test", "plop")
                .if_condition("debian")
                .format(0, LONGUEST_ATTRIBUTE_LEN + 3 + UNIQUE_ID_LEN),
            "    \"test\"                               string => \"plop\",\n                                             if => concat(\"debian\");"
        );
    }

    #[test]
    fn format_method() {
        assert_eq!(
            Bundle::agent("test").promise_group(
            Method::new()
                    .resource("package".to_string())
                    .state("present".to_string())
                    .parameters(vec!["vim".to_string()])
                    .report_parameter("parameter".to_string())
                    .report_component("component".to_string())
                    .condition("debian".to_string())
                    .build()).to_string()
            ,
            "bundle agent test {\n\n  methods:\n    # component:\n    # \n    #   \n    # \n    \"${report_data.directive_id}_0\"   usebundle => _method_reporting_context(\"component\", \"parameter\"),\n                                             if => concat(\"debian\");\n    \"${report_data.directive_id}_0\"   usebundle => package_present(vim),\n                                             if => concat(\"debian\");\n    \"${report_data.directive_id}_0\"   usebundle => _classes_noop(canonify(\"${class_prefix}_package_present_parameter\")),\n                                         unless => concat(\"debian\");\n    \"${report_data.directive_id}_0\"   usebundle => log_rudder(\"Skipping method \'component\' with key parameter \'parameter\' since condition \'debian\' is not reached\", \"parameter\", canonify(\"${class_prefix}_package_present_parameter\"), canonify(\"${class_prefix}_package_present_parameter\"), @{args}),\n                                         unless => concat(\"debian\");\n\n}"
        );
    }

    #[test]
    fn format_bundle() {
        assert_eq!(
            Bundle::agent("test").to_string(),
            "bundle agent test {\n\n}"
        );
        assert_eq!(
            Bundle::agent("test")
            .parameters(vec!["file".to_string(), "lines".to_string()])
            .promise_group(vec![Promise::usebundle("test", vec![])])
            .to_string(),
            "bundle agent test(file, lines) {\n\n  methods:\n    \"${report_data.directive_id}_0\"   usebundle => test();\n\n}"
        );
    }

    #[test]
    fn format_policy() {
        let mut meta = HashMap::new();
        meta.insert("extra".to_string(), "plop".to_string());

        assert_eq!(
            Policy::new()
                .name("test")
                .version("1.0")
                .bundle(Bundle::agent("test"))
                .to_string(),
            "# generated by rudderc\n# @name test\n# @version 1.0\n\nbundle agent test {\n\n}"
        );
    }
}
