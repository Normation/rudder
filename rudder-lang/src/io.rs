// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use serde::Deserialize;
use std::{fmt, path::PathBuf, str::FromStr};

use crate::{error::*, generators::Format, opt::IOOpt, Action};

// deserialized config file sub struct
#[derive(Clone, Debug, Deserialize)]
struct ActionConfig {
    source: Option<PathBuf>,
    dest: Option<PathBuf>,
    format: Option<Format>,
    // action is defined later in the code, should never be deserialized: set to None at first
    #[serde(skip, default)]
    action: Option<Action>,
}

#[derive(Clone, Debug, Deserialize)]
struct LibsConfig {
    stdlib: PathBuf,
    generic_methods: PathBuf,
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
#[derive(Clone, Debug)]
pub struct IOContext {
    pub stdlib: PathBuf,
    pub generic_methods: PathBuf,
    pub source: PathBuf,
    pub dest: PathBuf,
    pub mode: Action,
    pub format: Format,
}
impl fmt::Display for IOContext {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "{}",
            &format!(
                "{} of {:?} into {:?}. Format used is {}. Libraries and generic_methods paths: {:?}, {:?}.",
                self.mode,
                self.source,
                self.dest,
                self.format,
                self.stdlib,
                self.generic_methods,
            )
        )
    }
}

/// get explicit source. If none, get config path + technique_name. Else, error
fn get_source(config: &ActionConfig, opt: &IOOpt) -> Result<PathBuf> {
    Ok(match &opt.source {
        // 1. argument source, use it as source technique
        Some(technique_path) => technique_path.to_path_buf(),
        None => match &config.source {
            Some(config_source) => match &opt.technique_name {
                // 2. join config dir + source technique name
                Some(technique_name) => config_source.join(technique_name),
                None => {
                    if config_source.is_file() {
                        // 3. config source is a file, use it as source technique
                        config_source.to_path_buf()
                    } else {
                        return Err(Error::User(
                            "Could not determine source: no parameters and configured source is a directory".to_owned(),
                        ));
                    }
                }
            },
            None => {
                return Err(Error::User(
                    "Could not determine source: neither parameters nor configured source"
                        .to_owned(),
                ))
            }
        },
    })
}

/// get explicit dest. If no explicit dest get default path + filename. I none, use source path (and update format). If none worked, error
fn get_dest(config: &ActionConfig, opt: &IOOpt) -> Result<(PathBuf, Format)> {
    let technique = match &opt.dest {
        // 1. argument dest, use it as destination technique
        Some(technique_path) => technique_path.to_path_buf(),
        None => match &config.dest {
            Some(config_dest) => match opt.output_technique_name.as_ref().or(opt.technique_name.as_ref()) {
                // 2. join config dir + (dest) technique name
                Some(technique_name) => config_dest.join(technique_name),
                None => {
                    if config_dest.is_file() {
                        // 3. config dest is a file, use it as destination technique
                        config_dest.to_path_buf()
                    } else {
                        match &opt.source {
                            Some(source) => source.to_path_buf(),
                            None => return Err(Error::User(
                                "Could not determine destination: no parameters, configured destination is a directory and no input to base destination path on".to_owned(),
                            ))
                        }
                    }
                }
            },
            None => match &opt.source {
                Some(source) => source.to_path_buf(),
                None => return Err(Error::User(
                    "Could not determine destination: neither parameters nor configured source nor input to base destination path on".to_owned(),
                ))
            }
        }
    };

    // format is part of dest file so it makes sense to return it from this function plus it needs to be defined here to update dest if needed
    let (format, format_as_str) = get_dest_format(config, opt, &technique)?;
    if technique.ends_with(&format_as_str) {}
    Ok((technique.with_extension(&format_as_str), format))
}

/// get explicit dest. If no explicit dest get default path + filename. I none, use source path (and update format). If none worked, error
fn get_dest_format(config: &ActionConfig, opt: &IOOpt, dest: &PathBuf) -> Result<(Format, String)> {
    if opt.format.is_some() {
        info!("Command line format used");
    } else if config.format.is_some() {
        info!("Configuration format used");
    }
    if config.action == Some(Action::Translate) && (config.format.is_some() || opt.format.is_some())
    {
        warn!("Translate only supports rudder-lang format generation, overriding other settings");
    }

    let fmt: Format = match opt.format.as_ref().or(config.format.as_ref()) {
        Some(fmt) => fmt.clone(),
        None => {
            info!("Destination technique format used");
            match dest.extension().and_then(|fmt| fmt.to_str()) {
                Some(fmt) => Format::from_str(fmt)?,
                None => return Err(Error::User(
                    "Could not determine format: neither from argument nor configuration file, and destination technique has no defined format".to_owned(),
                ))
            }
        }
    };

    // This list must match each Generator implementation type + "rl" translation type
    // TODO get list from Generator directly
    if config.action == Some(Action::Compile) && fmt != Format::RudderLang {
        Ok((fmt.clone(), format!("{}.{}", "rl", fmt))) // TODO discuss about file extension handling
                                                       // Ok((fmt.clone(), fmt.to_string()))
    } else if config.action == Some(Action::Translate) {
        // translate can only have RL as output format
        Ok((Format::RudderLang, "rl".to_owned()))
    } else {
        Err(Error::User(format!(
            "Could not determine format: {} is not a valid format for {}",
            fmt,
            config.action.unwrap()
        )))
    }
}

fn get_opt_action_mode(action: Action, config: &Config) -> ActionConfig {
    match action {
        Action::Compile => {
            info!("Compilation default mode");
            ActionConfig {
                action: Some(Action::Compile),
                ..config.compile.clone()
            }
        }
        Action::Translate => {
            info!("Translation alternative mode requested");
            ActionConfig {
                action: Some(Action::Translate),
                ..config.translate.clone()
            }
        }
    }
}

/// get stdlib and generic_methods paths and
/// get the correct source and dest paths based on parameters
/// source priority is source > config + technique_name
/// dest priority is dest > config + technique_name > source
pub fn get(action: Action, opt: &IOOpt) -> Result<IOContext> {
    let config: Config = match std::fs::read_to_string(&opt.config_file) {
        Err(e) => {
            return Err(Error::User(format!(
                "Could not read toml config file: {}",
                e
            )))
        }
        Ok(config_data) => match toml::from_str(&config_data) {
            Ok(config) => config,
            Err(e) => {
                return Err(Error::User(format!(
                    "Could not parse (probably faulty) toml config file: {}",
                    e
                )))
            }
        },
    };

    let action_config = get_opt_action_mode(action, &config);
    let (dest, format) = get_dest(&action_config, opt)?;

    Ok(IOContext {
        stdlib: config.libs.stdlib.clone(),
        generic_methods: config.libs.generic_methods.clone(),
        source: get_source(&action_config, opt)?,
        dest,
        mode: action_config.action.unwrap(), // always Either Compile or Translate, set in get_opt_action_mode
        format,
    })
}
