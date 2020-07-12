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
}

const ATTRIBUTES_ORDERING: [AttributeType; 4] = [
    AttributeType::UseBundle,
    AttributeType::String,
    AttributeType::Unless,
    AttributeType::If,
];

impl PromiseType {
    fn allows(&self, attribute_type: AttributeType) -> bool {
        match self {
            PromiseType::Vars => [
                AttributeType::Unless,
                AttributeType::If,
                AttributeType::String,
            ],
            PromiseType::Methods => [
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
            }
        )
    }
}

#[derive(Clone, PartialEq, Eq)]
pub struct Promise {
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
        }
    }

    pub fn attribute<S: AsRef<str>>(mut self, attribute_type: AttributeType, value: S) -> Self {
        // Shouldn't happen but prevent generating broken code
        assert!(self.promise_type.allows(attribute_type));

        self.attributes
            .insert(attribute_type, value.as_ref().to_string());
        self
    }

    /// Shortcut for building a string variable with a raw value
    pub fn string<T: Into<String>, S: AsRef<str>>(name: T, value: S) -> Self {
        Promise::new(PromiseType::Vars, name)
            .attribute(AttributeType::String, quoted(value.as_ref()))
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
}

/// ID that must be unique for each technique instance. Combined with a simple index,
/// it allows enforcing all methods are called, even with identical parameters.
/// This has no semantic meaning and can almost be considered syntactic sugar.
const UNIQUE_ID: &str = "${report_data.directive_id}";

impl Promise {
    /// Index is used to make methods promises unique
    /// It is ignored in other cases
    fn format(&self, index: usize) -> String {
        let promiser = if self.promise_type == PromiseType::Methods {
            // Methods need to be unique
            format!("{}_{}", UNIQUE_ID, index)
        } else {
            self.promiser.clone()
        };

        let padding = promiser.len()
            // two quote and one space
            + 3
            + self
                .attributes
                .iter()
                .map(|(k, _)| k.to_string().len())
                .max()
                .unwrap_or(0);

        let mut first = true;

        if self.attributes.is_empty() {
            format!("\"{}\";", self.promiser)
        } else {
            format!(
                "{};",
                ATTRIBUTES_ORDERING
                    .iter()
                    .filter_map(|t| self.attributes.get(t).map(|p| (t, p)))
                    .map(|(k, v)| {
                        if first {
                            first = false;
                            format!("    \"{}\" {} => {}", promiser, k, v)
                        } else {
                            format!("    {:>width$} => {}", k, v, width = padding)
                        }
                    })
                    .collect::<Vec<String>>()
                    .join(",\n")
            )
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

#[derive(Clone, PartialEq, Eq)]
pub struct Bundle {
    bundle_type: BundleType,
    name: String,
    // Order matters!
    parameters: Vec<String>,
    // Promises of a given promise type need to be ordered specifically,
    // hence the `Vec`.
    promises: HashMap<PromiseType, Vec<Promise>>,
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

    pub fn promise(mut self, promise: Promise) -> Self {
        self.add_promise(promise);
        self
    }

    pub fn add_promise(&mut self, promise: Promise) {
        match self.promises.get_mut(&promise.promise_type) {
            Some(promises) => promises.push(promise),
            None => {
                self.promises.insert(promise.promise_type, vec![promise]);
            }
        }
    }
}

impl fmt::Display for Bundle {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "bundle {} {}{} {{\n{}}}",
            self.bundle_type,
            self.name,
            if self.parameters.is_empty() {
                String::new()
            } else {
                format!("({})", self.parameters.join(", "))
            },
            NORMAL_ORDERING
                .iter()
                .filter_map(|t| self.promises.get(&t).map(|p| (t, p)))
                .map(|(t, p)| format!(
                    "  {}:\n{}\n",
                    t,
                    p.iter()
                        .enumerate()
                        .map(|(i, p)| format!("{}", p.format(i)))
                        .collect::<Vec<String>>()
                        .join("\n")
                ))
                .collect::<Vec<String>>()
                .join("\n")
        )
    }
}

pub struct Technique {
    bundles: Vec<Bundle>,
    name: Option<String>,
    version: Option<String>,
}

impl Technique {
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

impl fmt::Display for Technique {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        writeln!(f, "# @generated by rudderc")?;
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

    #[test]
    fn format_promise() {
        assert_eq!(
            Promise::new(PromiseType::Vars, "test").format(0),
            "\"test\";"
        );
        assert_eq!(
            Promise::string("test", "plop")
                .if_condition("debian")
                .format(0),
            "    \"test\" string => \"plop\",\n    if => concat(\"debian\");"
        );
    }

    #[test]
    fn format_bundle() {
        assert_eq!(Bundle::agent("test").to_string(), "bundle agent test {\n}");
        assert_eq!(
            Bundle::agent("test")
            .parameters(vec!["file".to_string(), "lines".to_string()])
            .promise(Promise::usebundle("test", vec![]))
            .to_string(),
            "bundle agent test(file, lines) {\n  methods:\n    \"${report_data.directive_id}_0\" usebundle => test();\n}"
        );
    }

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
            "# @generated by rudderc\n# @name test\n# @version 1.0\n\nbundle agent test {\n}"
        );
    }
}
