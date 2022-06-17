// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2022 Normation SAS

use std::{collections::HashMap, fmt};

use crate::backends::unix::cfengine::promise::{Promise, PromiseType, LONGEST_ATTRIBUTE_LEN};

const NORMAL_ORDERING: [PromiseType; 2] = [PromiseType::Vars, PromiseType::Methods];

/// ID that must be unique for each technique instance. Combined with a simple index,
/// it allows enforcing all methods are called, even with identical parameters.
/// This has no semantic meaning and can almost be considered syntactic sugar.
pub const UNIQUE_ID: &str = "${report_data.directive_id}";
/// Indexes over three chars
const INDEX_LEN: usize = 3;
/// Length of directive id + _ + index
pub const UNIQUE_ID_LEN: usize = UNIQUE_ID.len() + 1 + INDEX_LEN;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
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

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Bundle {
    bundle_type: BundleType,
    pub name: String,
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
            .filter_map(|t| self.promises.get(t).map(|p| (t, p)))
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
                        promise.format(index, max_promiser + LONGEST_ATTRIBUTE_LEN + 3)
                    )?;
                }
                writeln!(f)?;
            }
        }

        write!(f, "}}")
    }
}

#[cfg(test)]
mod tests {
    use pretty_assertions::assert_eq;

    use super::*;

    #[test]
    fn format_bundle() {
        assert_eq!(
            Bundle::agent("test").to_string(),
            "bundle agent test {\n\n}"
        );
        assert_eq!(
            Bundle::agent("test")
                .parameters(vec!["file".to_string(), "lines".to_string()])
                .promise_group(vec![Promise::usebundle("test", None, None, vec![])])
                .to_string(),
            "bundle agent test(file, lines) {\n\n  methods:\n    \"${report_data.directive_id}_0\"   usebundle => test();\n\n}"
        );
    }
}
