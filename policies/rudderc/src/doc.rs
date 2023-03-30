// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

//! Render documentation about modules.

use std::fmt;

use anyhow::{Context, Result};
use clap::ValueEnum;
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
            Self::Markdown => markdown(methods),
            Self::Json => {
                ok_output("Generating", "modules description".to_owned());
                // FIXME: sort output to limit changes
                serde_json::to_string_pretty(&methods).context("Serializing modules")
            }
            Self::Html => unreachable!(),
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
        out.push_str("\n----\n")
    }
    Ok(out)
}

pub mod book {
    use std::{
        fs::{create_dir_all, remove_dir_all, File, OpenOptions},
        io::Write,
        path::{Path, PathBuf},
    };

    use anyhow::{Context, Result};
    use include_dir::{include_dir, Dir};
    use mdbook::MDBook;

    use crate::{compiler::Methods, doc::markdown};

    // Include doc dir at compile time
    static BOOK_DIR: Dir<'_> = include_dir!("$CARGO_MANIFEST_DIR/docs");

    pub fn render(methods: &'static Methods, target_dir: &Path) -> Result<PathBuf> {
        let root_dir = target_dir.join("doc");
        let src_dir = root_dir.join("src");
        if src_dir.exists() {
            // Clean generated files
            remove_dir_all(&src_dir)?;
        }
        create_dir_all(&root_dir)?;

        // Static docs
        BOOK_DIR.extract(&root_dir)?;

        // we will append content to the static summary
        let summary_file = src_dir.join("SUMMARY.md");
        let mut summary = OpenOptions::new()
            .append(true)
            .open(&summary_file)
            .with_context(|| format!("Failed to open summary file '{}'", summary_file.display()))?;

        // Now let's extract the categories from method list
        let mut methods: Vec<_> = methods.iter().collect();
        methods.sort_by(|x, y| x.0.cmp(y.0));
        let mut categories: Vec<&str> = methods
            .iter()
            .map(|(n, _)| n.split('_').next().unwrap())
            .collect();
        categories.dedup();

        // Dynamic content
        for category in categories {
            let mut pretty_category = category.to_string();
            if let Some(r) = pretty_category.get_mut(0..1) {
                r.make_ascii_uppercase();
            }
            writeln!(summary, "# {pretty_category}")?;
            for (_, m) in methods.iter().filter(|(n, _)| n.starts_with(category)) {
                let md_file = format!("{}.md", m.bundle_name);
                let mut file = File::create(src_dir.join(&md_file))?;
                file.write_all(markdown::method(m)?.as_bytes())?;
                writeln!(summary, "- [{}]({})", m.bundle_name, &md_file)?;
            }
        }

        // Build
        MDBook::load(&root_dir)?.build()?;
        Ok(root_dir.join("book").join("index.html"))
    }
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
### {bundle_name}
 
{description}

⚙️ **Compatible targets**: {agents}

{deprecated}

#### Parameters

| Name | Documentation |
|------|---------------|
{parameters}

#### Example

```yaml
{example}
```

{documentation}
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
                Some(_) => "⚠️ **Deprecated**: This method is deprecated and should not be used.",
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
        let mut constraints = vec![];
        if let Some(list) = &p.constraints.select {
            let values = list
                .iter()
                // empty value is assimilated to "optional" and handled below
                .filter(|v| !v.is_empty())
                .map(|v| format!("<li>`{v}`</li>"))
                .collect::<Vec<String>>()
                .join("");
            constraints.push(format!("Choices:<br><ul>{values}</ul>"));
        } else {
            if p.constraints.allow_whitespace_string {
                constraints.push("This parameter can contain only whitespaces.".to_string())
            }
            if let Some(r) = &p.constraints.regex {
                constraints.push(format!("This parameter must match `{r}`."));
            }
            if p.constraints.max_length != DEFAULT_MAX_PARAM_LENGTH {
                constraints.push(format!(
                    "The maximal length for thi parameter is {} characters.",
                    p.constraints.max_length
                ));
            }
        }
        // No list of allowed valued, document other constraints
        if p.constraints.allow_empty_string {
            constraints.push("This parameter is optional.".to_string())
        } else {
            constraints.push("This parameter is required.".to_string())
        }

        format!(
            "|{name}|{description}<br><br>{constraints}|",
            name = if p.constraints.allow_empty_string {
                format!("_{}_", p.name)
            } else {
                format!("**{}**", p.name)
            },
            constraints = constraints.join("<br>"),
            description = if p.description.ends_with('.') {
                p.description.clone()
            } else {
                let mut d = p.description.clone();
                d.push('.');
                d
            },
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
