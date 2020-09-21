// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use serde::Deserialize;
use std::{fmt, path::PathBuf, str::FromStr};

use crate::{error::*, generator::Format, opt::GenericOptions, Action};

// deserialized config file sub struct
#[derive(Clone, Debug, Deserialize)]
struct ActionConfig {
    input: Option<PathBuf>,
    output: Option<PathBuf>,
    format: Option<Format>,
    // action is defined later in the code, should never be deserialized: set to None at first
    #[serde(skip, default)]
    action: Option<Action>,
}

#[derive(Clone, Debug, Deserialize)]
struct LibsConfig {
    stdlib: PathBuf,
}

// deserialized config file main struct
#[derive(Clone, Debug, Deserialize)]
struct Config {
    #[serde(rename = "shared")]
    libs: LibsConfig,
    compile: ActionConfig,
    translate: ActionConfig,
}

/// IO context deduced from arguments (structopt) and config file (Config)
// must always reflect GenericOptions + add unique fields
#[derive(Clone, Debug)]
pub struct IOContext {
    // GenericOption reflection
    pub stdlib: PathBuf,
    pub input: PathBuf,
    pub output: PathBuf,
    // Unique fields
    pub format: Format,
    pub action: Action,
}
// TODO might try to merge io.rs in here
impl IOContext {
    pub fn set(action: Action, opt: &GenericOptions, format: Option<Format>) -> Result<Self> {
        let config: Config = match std::fs::read_to_string(&opt.config_file) {
            Err(e) => {
                return Err(Error::new(format!(
                    "Could not read toml config file: {}",
                    e
                )))
            }
            Ok(config_data) => match toml::from_str(&config_data) {
                Ok(config) => config,
                Err(e) => {
                    return Err(Error::new(format!(
                        "Could not parse (probably faulty) toml config file: {}",
                        e
                    )))
                }
            },
        };

        let action_config = get_opt_action(action, &config);
        let (output, format) = get_output(&action_config, &opt.input, &opt.output, format)?;

        Ok(IOContext {
            stdlib: config.libs.stdlib.clone(),
            input: get_input(&action_config, &opt.input)?,
            output,
            action: action_config.action.unwrap(),
            format,
        })
    }
}
impl fmt::Display for IOContext {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "{}",
            &format!(
                "{} of {:?} into {:?}. Output format is {}. Libraries path: {:?}.",
                self.action, self.input, self.output, self.format, self.stdlib,
            )
        )
    }
}

fn get_opt_action(action: Action, config: &Config) -> ActionConfig {
    info!("Action requested: {}", action);
    ActionConfig {
        action: Some(action),
        ..config.compile.clone()
    }
}

/// get explicit input file or error
fn get_input(config: &ActionConfig, input: &Option<PathBuf>) -> Result<PathBuf> {
    Ok(match &input {
        Some(technique_path) => technique_path.to_path_buf(),
        None => match &config.input {
            Some(config_input) => {
                if config_input.is_file() {
                    config_input.to_path_buf()
                } else {
                    return Err(Error::new(
                        "Could not determine input file: no parameters and configured input is a directory".to_owned(),
                    ));
                }
            }
            None => {
                return Err(Error::new(
                    "Could not determine input file: neither parameters nor configured input"
                        .to_owned(),
                ))
            }
        },
    })
}

/// get explicit output file or error
fn get_output(
    config: &ActionConfig,
    input: &Option<PathBuf>,
    output: &Option<PathBuf>,
    format: Option<Format>,
) -> Result<(PathBuf, Format)> {
    let technique = match &output {
        Some(technique_path) => technique_path.to_path_buf(),
        None => match &config.output {
            Some(config_output) => {
                if config_output.is_file() {
                    config_output.to_path_buf()
                } else {
                    match &input {
                        Some(input) => input.to_path_buf(),
                        None => return Err(Error::new(
                            "Could not determine output file: no parameters, configured output is a directory and no input to base destination path on".to_owned(),
                        ))
                    }
                }
            },
            None => match &input {
                Some(input) => input.to_path_buf(),
                None => return Err(Error::new(
                    "Could not determine output file: neither parameters nor configured input nor input to base destination path on".to_owned(),
                ))
            }
        }
    };

    // format is part of output file so it makes sense to return it from this function plus it needs to be defined here to update output if needed
    let (format, format_as_str) = get_output_format(config, format, &technique)?;
    if technique.ends_with(&format_as_str) {}
    Ok((technique.with_extension(&format_as_str), format))
}

/// get explicit output. If no explicit output get default path + filename. I none, use input path (and update format). If none worked, error
fn get_output_format(
    config: &ActionConfig,
    format: Option<Format>,
    output: &PathBuf,
) -> Result<(Format, String)> {
    if config.action == Some(Action::Compile) && format.is_some() {
        info!("Command line format used");
    } else if config.action == Some(Action::Compile) && config.format.is_some() {
        info!("Configuration format used");
    }

    let fmt: Format = match format.as_ref().or_else(|| config.format.as_ref()) {
        Some(fmt) => fmt.clone(),
        None => {
            info!("Output technique format used");
            match output.extension().and_then(|fmt| fmt.to_str()) {
                Some(fmt) => Format::from_str(fmt)?,
                None => return Err(Error::new(
                    "Could not output file format: neither from argument nor configuration file, and output technique has no defined format".to_owned(),
                ))
            }
        }
    };

    match config.action {
        Some(Action::Compile) if fmt == Format::CFEngine || fmt == Format::DSC => {
            Ok((fmt.clone(), format!("{}.{}", "rl", fmt)))
        }
        Some(Action::GenerateTechnique) => Ok((Format::JSON, "json".to_owned())),
        Some(Action::Migrate) => Ok((Format::RudderLang, "rl".to_owned())),
        Some(Action::ReadTechnique) => Ok((Format::JSON, "json".to_owned())),
        _ => Err(Error::new(format!(
            "Could not determine format: {} is not a valid format for {}",
            fmt,
            config.action.unwrap()
        ))),
    }
}
