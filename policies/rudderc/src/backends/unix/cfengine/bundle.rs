// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2022 Normation SAS

use std::{collections::HashMap, fmt};

use crate::backends::unix::cfengine::promise::{LONGEST_ATTRIBUTE_LEN, Promise, PromiseType};

const NORMAL_ORDERING: [PromiseType; 3] = [
    PromiseType::Vars,
    PromiseType::Classes,
    PromiseType::Methods,
];

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
        let mut r = Self {
            name: name.into(),
            bundle_type: BundleType::Agent,
            parameters: Vec::new(),
            promises: HashMap::new(),
        };

        // Global index increment, once per bundle call
        let guard_class = "rudder_increment_guard";
        let increment = Promise::int(
            "report_data.index",
            "int(eval(\"${report_data.index}+1\", \"math\", \"infix\"))",
        )
        .unless_condition(guard_class);
        // take a snapshot of the index for local use, as it will be incremented by methods
        let local =
            Promise::int("local_index", "${report_data.index}").unless_condition(guard_class);
        // equivalent to pass1 but don't mess with business logic
        let guard = Promise::class_expression(guard_class, "any");
        r.add_promise_group(vec![increment, local]);
        r.add_promise_group(vec![guard]);
        r
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

        let mut index = 0;

        for (promise_type, promises) in NORMAL_ORDERING
            .iter()
            .filter_map(|t| self.promises.get(t).map(|p| (t, p)))
        {
            writeln!(f, "  {promise_type}:")?;

            for group in promises {
                // Align promise groups
                let mut max_promiser = group.iter().map(|p| p.promiser.len()).max().unwrap_or(0);
                // Take special method promiser into account
                if *promise_type == PromiseType::Methods {
                    max_promiser = std::cmp::max(max_promiser, Promise::unique_id(index).len());
                }

                for promise in group {
                    writeln!(
                        f,
                        "{}",
                        promise.format(index, max_promiser + LONGEST_ATTRIBUTE_LEN + 3)
                    )?;
                    // Avoid useless increment for readability
                    if *promise_type == PromiseType::Methods {
                        index += 1;
                    }
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
            r#"bundle agent test {

  vars:
    "report_data.index" int => int(eval("${report_data.index}+1", "math", "infix")),
                           unless => "rudder_increment_guard";
    "local_index"       int => ${report_data.index},
                           unless => "rudder_increment_guard";

  classes:
    "rudder_increment_guard" expression => "any";

}"#
        );
        assert_eq!(
            Bundle::agent("test")
                .parameters(vec!["file".to_string(), "lines".to_string()])
                .promise_group(vec![Promise::usebundle("test", None, vec![])])
                .to_string(),
            r#"bundle agent test(file, lines) {

  vars:
    "report_data.index" int => int(eval("${report_data.index}+1", "math", "infix")),
                           unless => "rudder_increment_guard";
    "local_index"       int => ${report_data.index},
                           unless => "rudder_increment_guard";

  classes:
    "rudder_increment_guard" expression => "any";

  methods:
    "index_${local_index}_0" usebundle => test();

}"#
        );
    }
}
