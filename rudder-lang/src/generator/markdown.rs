/// Generated documentation for the given file and/or library

/// Generates markdown
use super::Generator;
use crate::{
    command::CommandResult, error::*, generator::Format, ir::ir2::IR2, ir::resource::StateDef,
};
use std::cmp::Ordering;
use std::path::{Path, PathBuf};

pub struct Markdown {}
impl Markdown {
    pub fn new() -> Self {
        Self {}
    }
}

impl Generator for Markdown {
    // TODO methods differ if this is a technique generation or not
    fn generate(
        &mut self,
        gc: &IR2,
        _source_file: &str,
        _dest_file: Option<&Path>,
        _policy_metadata: bool,
    ) -> Result<Vec<CommandResult>> {
        Self::render(gc, None)
    }
}

impl Markdown {
    fn render_constraints(constraints: &toml::map::Map<String, toml::Value>) -> Vec<String> {
        let mut out = vec![];

        // select masks other constraints
        if let Some(select) = constraints.get("select") {
            let options = select
                .as_array()
                .unwrap()
                .iter()
                .map(|o| match o.as_str().unwrap() {
                    "" => "_empty_".to_string(),
                    o => format!("`{}`", o),
                })
                .collect::<Vec<String>>();
            out.push(options.join(", "))
        } else {
            for (constraint, value) in constraints {
                match constraint.as_str() {
                    "allow_empty_string" if value.as_bool().unwrap() => {
                        out.push("can be empty".to_string())
                    }
                    "allow_whitespace_string" if value.as_bool().unwrap() => {
                        out.push("can contain only white-space chars".to_string())
                    }
                    "max_length" if value.as_integer().unwrap() != 16384 => {
                        out.push(format!("max length: {}", value.as_integer().unwrap()))
                    }
                    "regex" => out.push(format!("must match: `{}`", value.as_str().unwrap())),
                    _ => (),
                }
            }
        }

        out
    }

    fn render_parameters(metadata: &toml::map::Map<String, toml::Value>) -> Vec<String> {
        // FIXME type
        let mut out = vec![];
        if let Some(params) = metadata.get("parameter") {
            for (parameter, properties) in params.as_table().unwrap().iter() {
                let constraints = if let Some(constraints) =
                    properties.get("constraints").and_then(|a| a.as_table())
                {
                    format!(" ({})", Self::render_constraints(&constraints).join(", "))
                } else {
                    "".to_string()
                };

                out.push(format!("#### {}{}", parameter, constraints));
                // FIXME hardcode others?

                if let Some(description) = properties.get("description") {
                    out.push(description.as_str().unwrap().to_string());
                }
            }
        }
        out
    }

    /// renders doc from an IR, with an optional resource filter
    /// When filter resource is None the whole IR is rendered
    fn render(gc: &IR2, resource_filter: Option<String>) -> Result<Vec<CommandResult>> {
        let mut files: Vec<CommandResult> = Vec::new();

        for (resource_name, resource) in gc.resources.iter() {
            if let Some(ref name) = resource_filter {
                if name != resource_name.fragment() {
                    continue;
                }
            }

            // one file by resource
            // FIXME proper destination
            let resource_file: PathBuf =
                format!("target/docs/std/{}.md", resource_name.fragment()).into();

            let mut out = vec![];
            out.push(format!("# {}", resource_name.fragment()));

            if let Some(description) = resource.metadata.get("description") {
                out.push(description.as_str().unwrap().to_string());
            }

            // resource signature
            let resource_params = resource
                .parameters
                .iter()
                .map(|p| p.name.fragment())
                .collect::<Vec<&str>>();
            let resource_signature = format!(
                "{}({})",
                resource_name.fragment(),
                resource_params.join(", ")
            );
            out.push(format!(
                "```rudder-lang\nresource {}\n```",
                resource_signature
            ));

            out.extend(Self::render_parameters(&resource.metadata).iter().cloned());

            out.push("## States".to_string());

            let mut states = resource.states.values().collect::<Vec<&StateDef>>();
            // Order alphanumerically, but put deprecated states at the end
            states.sort_by(|a, b| {
                match (a.metadata.get("deprecated"), b.metadata.get("deprecated")) {
                    (Some(_), Some(_)) => b.name.cmp(&a.name),
                    (None, Some(_)) => Ordering::Less,
                    (Some(_), None) => Ordering::Greater,
                    (None, None) => b.name.cmp(&a.name),
                }
            });

            for state in states {
                let platforms = if let Some(targets) = state
                    .metadata
                    .get("supported_targets")
                    .and_then(|a| a.as_array())
                {
                    let targets = targets
                        .iter()
                        .map(|t| match t.as_str().unwrap() {
                            "cf" => "unix",
                            "dsc" => "windows",
                            _ => unreachable!(),
                        })
                        .collect::<Vec<&str>>();
                    format!(" [{}]", targets.join(", "))
                } else {
                    "".to_string()
                };

                let command = if state
                    .metadata
                    .get("command")
                    .and_then(|a| a.as_bool())
                    .unwrap_or(false)
                {
                    " (command)".to_string()
                } else {
                    "".to_string()
                };

                out.push(format!("### {}{}{}", state.name, platforms, command));

                if let Some(deprecation) = state.metadata.get("deprecated") {
                    out.push(format!(
                        "WARNING: *DEPRECATED*: {}",
                        deprecation.as_str().unwrap()
                    ));
                }

                if let Some(description) = state.metadata.get("description") {
                    out.push(description.as_str().unwrap().to_string());
                }

                let state_params = state
                    .parameters
                    .iter()
                    .map(|p| p.name.fragment())
                    .collect::<Vec<&str>>();
                out.push(format!(
                    "```rudder-lang\n{}.{}({})\n```",
                    resource_signature,
                    state.name,
                    state_params.join(", ")
                ));

                if let Some(documentation) = state.metadata.get("documentation") {
                    out.push(documentation.as_str().unwrap().to_string());
                }

                out.extend(Self::render_parameters(&state.metadata).iter().cloned());
            }
            files.push(CommandResult::new(
                Format::Markdown,
                Some(resource_file.into()),
                Some(out.join("\n\n")),
            ));
        }

        Ok(files)
    }
}
