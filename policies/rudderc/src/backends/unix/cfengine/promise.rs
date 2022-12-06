// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2022 Normation SAS

use std::{collections::HashMap, fmt};

use crate::backends::unix::cfengine::{bundle::UNIQUE_ID, quoted};

#[derive(Clone, Copy, PartialEq, Eq, Hash, Debug)]
pub enum PromiseType {
    Vars,
    Methods,
}

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

#[derive(Clone, Copy, PartialEq, Eq, Hash, Debug)]
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

/// Used for formatting
pub const LONGEST_ATTRIBUTE_LEN: usize = 9;

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

#[derive(Clone, PartialEq, Eq, Debug)]
pub struct Promise {
    /// Comments in output file
    comments: Option<String>,
    /// Module/state the promise calls
    component: Option<String>,
    id: Option<String>,
    /// Type of the promise
    pub promise_type: PromiseType,
    /// Target of the promise
    pub promiser: String,
    /// What the promise does
    attributes: HashMap<AttributeType, String>,
}

/// Promise constructors should allow avoiding common mistakes
impl Promise {
    pub fn new<T: Into<String>>(
        promise_type: PromiseType,
        component: Option<String>,
        id: Option<String>,
        promiser: T,
    ) -> Self {
        Self {
            promise_type,
            component,
            id,
            promiser: promiser.into(),
            attributes: HashMap::new(),
            comments: None,
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
        Promise::new(PromiseType::Vars, None, None, name)
            .attribute(AttributeType::String, value.as_ref())
    }

    /// Shortcut for building an slist variable with a list of values to be quoted
    pub fn slist<T: Into<String>, S: AsRef<str>>(name: T, values: Vec<S>) -> Self {
        Promise::new(PromiseType::Vars, None, None, name).attribute(
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
    pub fn usebundle<T: AsRef<str>>(
        bundle: T,
        component: Option<&str>,
        id: Option<&str>,
        parameters: Vec<String>,
    ) -> Self {
        Promise::new(
            PromiseType::Methods,
            component.map(String::from),
            id.map(String::from),
            "${report_data.method_id}",
        )
        .attribute(
            AttributeType::UseBundle,
            format!("{}({})", bundle.as_ref(), parameters.join(", ")),
        )
    }

    /// Shortcut for adding a condition
    pub fn if_condition<T: AsRef<str>>(mut self, condition: T) -> Self {
        self.attributes.insert(
            AttributeType::If,
            format!("concat(\"{}\")", condition.as_ref()),
        );
        self
    }

    /// Shortcut for adding a condition
    pub fn unless_condition<T: AsRef<str>>(mut self, condition: T) -> Self {
        self.attributes.insert(
            AttributeType::Unless,
            format!("concat(\"{}\")", condition.as_ref()),
        );
        self
    }

    pub fn comment<T: AsRef<str>>(mut self, comment: T) -> Self {
        let c = match self.comments.clone() {
            Some(mut c) => {
                c.push('\n');
                c.push_str(comment.as_ref());
                c
            }
            None => comment.as_ref().to_string(),
        };
        self.comments = Some(c);
        self
    }

    /// Index is used to make methods promises unique
    /// It is ignored in other cases
    //
    // padding for arrows is promiser len + max attribute name
    pub fn format(&self, index: usize, padding: usize) -> String {
        let promiser = match self.id.clone() {
            Some(id) if !id.is_empty() => id,
            _ => {
                if self.promise_type == PromiseType::Methods {
                    // Methods need to be unique
                    match self.component.clone() {
                        Some(method) => format!("{}_{}_{}", method, UNIQUE_ID, index),
                        None => format!("{}_{}", UNIQUE_ID, index),
                    }
                } else {
                    self.promiser.clone()
                }
            }
        };

        let mut first = true;

        let comment = match &self.comments {
            Some(c) => c
                .split('\n')
                .map(|c| format!("    # {}\n", c))
                .collect::<Vec<String>>()
                .concat(),
            None => "".to_string(),
        };

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
                                k,
                                v,
                                promiser_width = padding - LONGEST_ATTRIBUTE_LEN - 1,
                                width = LONGEST_ATTRIBUTE_LEN
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

#[cfg(test)]
mod tests {
    use pretty_assertions::assert_eq;

    use super::*;
    use crate::backends::unix::cfengine::bundle::UNIQUE_ID_LEN;

    #[test]
    fn format_promise() {
        assert_eq!(
            Promise::new(PromiseType::Vars, None, None, "test")
                .format(0, LONGEST_ATTRIBUTE_LEN + 3 + UNIQUE_ID_LEN),
            "\"test\";"
        );
        assert_eq!(
            Promise::new(PromiseType::Vars, None, None, "test")
                .comment("test")
                .format(0, LONGEST_ATTRIBUTE_LEN + 3 + UNIQUE_ID_LEN),
            "    # test\n\"test\";"
        );
        assert_eq!(
            Promise::string("test", "plop")
                .if_condition("debian")
                .format(0, LONGEST_ATTRIBUTE_LEN + 3 + UNIQUE_ID_LEN),
            "    \"test\"                            string => \"plop\",\n                                             if => concat(\"debian\");"
        );
    }
}
