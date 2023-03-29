// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

//! Render documentation about modules.

use std::{fmt, str::FromStr};

use anyhow::{bail, Context, Error, Result};
use askama::Template;
use clap::ValueEnum;
use comrak::{markdown_to_html, ComrakOptions};
use serde::Serialize;

use crate::{compiler::Methods, logs::ok_output};

#[derive(Debug, Copy, Clone, PartialEq, Eq, Serialize, ValueEnum)]
pub enum Format {
    /// Markdown output
    Markdown,
    /// HTML output
    Html,
    /// JSON output
    ///
    /// It replaces the legacy `generic_methods.json` produced by `ncf.py`, to be used by the webapp
    Json,
}

impl Default for Format {
    fn default() -> Self {
        Self::Html
    }
}

impl Format {
    /// Extension of the output files
    pub fn extension(self) -> &'static str {
        match self {
            Self::Markdown => "md",
            Self::Html => "html",
            Self::Json => "json",
        }
    }
}

impl FromStr for Format {
    type Err = Error;

    fn from_str(target: &str) -> Result<Self> {
        match target {
            "md" | "markdown" => Ok(Format::Markdown),
            "html" => Ok(Format::Html),
            "json" => Ok(Format::Json),
            _ => bail!("Could not recognize format {:?}", target),
        }
    }
}

// standard target name
impl fmt::Display for Format {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "{}",
            match self {
                Self::Markdown => "markdown",
                Self::Html => "html",
                Self::Json => "json",
            }
        )
    }
}

impl Format {
    pub fn render(&self, methods: &'static Methods) -> Result<String> {
        match self {
            Self::Html => html(methods),
            Self::Markdown => markdown(methods),
            Self::Json => {
                ok_output("Generating", "modules description".to_owned());
                // FIXME: sort output to limit changes
                serde_json::to_string_pretty(&methods).context("Serializing modules")
            }
        }
    }
}

fn markdown(methods: &'static Methods) -> Result<String> {
    ok_output("Generating", "modules documentation".to_owned());
    // Sort methods
    let mut methods: Vec<_> = methods.iter().collect();
    methods.sort_by(|x, y| x.0.cmp(y.0));

    let mut out = String::new();
    for (_, m) in methods {
        out.push_str(&markdown::method(m)?);
    }
    Ok(out)
}

#[derive(Template)]
#[template(path = "doc.html.askama", escape = "none")]
struct MethodsDocTemplate {
    methods: String,
}

fn html(methods: &'static Methods) -> Result<String> {
    let md = markdown(methods)?;
    let methods = markdown_to_html(&md, &ComrakOptions::default());
    let doc = MethodsDocTemplate { methods };
    doc.render().map_err(|e| e.into())
}

mod markdown {
    use std::collections::HashMap;

    use anyhow::Result;
    use rudder_commons::{Target, DEFAULT_MAX_PARAM_LENGTH};
    use serde::Serialize;

    use crate::frontends::methods::method::{MethodInfo, Parameter};

    /// Render method doc to markdown
    pub fn method(m: &'static MethodInfo) -> Result<String> {
        let example = example(m)?;

        Ok(format!(
            "
### {bundle_name} [{agents}] {deprecated}

{description}

#### Parameters

{parameters}

#### Example

```yaml
{example}
```

{documentation}

----
",
            bundle_name = m.bundle_name,
            agents = m
                .agent_support
                .iter()
                .map(|a| {
                    let t: Target = (*a).into();
                    t.to_string()
                })
                .collect::<Vec<String>>()
                .join(", "),
            deprecated = match m.deprecated {
                Some(_) => " - _DEPRECATED_",
                None => "",
            },
            description = if m.description.ends_with('.') {
                m.description.clone()
            } else {
                format!("{}.", m.description)
            },
            parameters = m
                .parameter
                .iter()
                .map(parameter)
                .collect::<Vec<String>>()
                .join("\n"),
            example = example,
            documentation = m
                .documentation
                .as_ref()
                .map(|d| format!("#### Documentation\n{d}"))
                .unwrap_or_else(|| "".to_string()),
        ))
    }

    fn parameter(p: &Parameter) -> String {
        let mut constraints = String::new();
        if let Some(list) = &p.constraints.select {
            let values = list
                .iter()
                .map(|v| {
                    if v.is_empty() {
                        "_empty_".to_string()
                    } else {
                        format!("`{v}`")
                    }
                })
                .collect::<Vec<String>>()
                .join(", ");
            constraints.push_str(&format!("\n  * Possible values: {values}"));
        } else {
            // No list of allowed valued, document other constraints
            if p.constraints.allow_empty_string {
                constraints.push_str("\n  * Can be empty")
            }
            if p.constraints.allow_whitespace_string {
                constraints.push_str("\n  * Can be empty")
            }
            if let Some(r) = &p.constraints.regex {
                constraints.push_str(&format!("\n  * Must match `{r}`"));
            }
            if p.constraints.max_length != DEFAULT_MAX_PARAM_LENGTH {
                constraints.push_str(&format!(
                    "\n  * Maximal allowed length is {} characters",
                    p.constraints.max_length
                ));
            }
        }

        format!(
            "
* **{name}**: {description}
{constraints}
",
            name = p.name,
            description = p.description,
        )
    }

    #[derive(Serialize)]
    struct Example {
        method: String,
        params: HashMap<String, String>,
    }

    /// Generate a yaml snippet with a usage example
    fn example(m: &MethodInfo) -> Result<String> {
        let mut params = HashMap::new();
        for p in &m.parameter {
            let value = if let Some(ss) = &p.constraints.select {
                // try to use one value of the select, if possible the first non-empty
                if let Some(non_empty) = ss.iter().find(|x| !x.is_empty()) {
                    non_empty.clone()
                } else {
                    // at least one value
                    ss.first().unwrap().clone()
                }
            } else {
                if p.constraints.allow_empty_string {
                    "OPTIONAL_VALUE"
                } else {
                    "VALUE"
                }
                .to_string()
            };
            params.insert(p.name.clone(), value);
        }
        let e = Example {
            method: m.bundle_name.clone(),
            params,
        };
        Ok(serde_yaml::to_string(&e)?)
    }
}
