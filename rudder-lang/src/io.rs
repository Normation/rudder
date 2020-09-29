// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use crate::{error::*, generator::Format, opt::Options, Action};
use serde::Deserialize;
use std::{fmt, path::PathBuf, str::FromStr};

#[derive(Clone, Debug, Deserialize)]
struct LibsConfig {
    stdlib: PathBuf,
}

#[derive(Clone, Debug, Deserialize)]
struct IOPaths {
    input: Option<PathBuf>,
    output: Option<PathBuf>,
}

// deserialized config file main struct
#[derive(Clone, Debug, Deserialize)]
struct Config {
    #[serde(rename = "shared")]
    libs: LibsConfig,
    compile: IOPaths,
    migrate: IOPaths,
    technique_generate: IOPaths,
    technique_read: IOPaths,
}

/// IO context deduced from arguments (structopt) and config file (Config)
// must always reflect Options + add unique fields
#[derive(Clone, Debug, PartialEq)]
pub struct IOContext {
    // GenericOption reflection
    pub stdlib: PathBuf,
    pub input: Option<PathBuf>,
    pub output: Option<PathBuf>,
    // Unique fields
    pub format: Format,
    pub action: Action,
}
// TODO might try to merge io.rs in here
impl IOContext {
    pub fn new(action: Action, opt: &Options, format: Option<Format>) -> Result<Self> {
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

        let action_config = match action {
            Action::Compile => config.compile,
            Action::GenerateTechnique => config.technique_generate,
            Action::Migrate => config.migrate,
            Action::ReadTechnique => config.technique_read,
        };
        let input = get_input(&action_config.input, &opt.input, opt.stdin)?;
        let (output, format) = get_output(
            &action_config.output,
            action,
            &input,
            &opt.output,
            opt.stdout,
            format,
        )?;

        Ok(IOContext {
            stdlib: config.libs.stdlib.clone(),
            input,
            output,
            action,
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

/// If stdin, returns None. Else if input and config lead to existing file, Some(file). Else, error.
fn get_input(
    config_path: &Option<PathBuf>,
    input: &Option<PathBuf>,
    is_stdin: bool,
) -> Result<Option<PathBuf>> {
    if is_stdin {
        if input.is_some() {
            info!("Input file not used because of the --stdin option");
        }
        return Ok(None);
    }
    Ok(Some(match (input, config_path) {
        (Some(i), _) if i.is_file() => i.to_owned(),
        (Some(i), Some(c)) => {
            let path = c.join(i);
            if path.is_file() {
                path.to_owned()
            } else {
                return Err(Error::new(
                    "Commands: input does not match any existing file".to_owned(),
                ));
            }
        }
        (None, Some(c)) if c.is_file() => c.to_owned(),
        _ => {
            return Err(Error::new(
                "Commands: no input or input does not match any existing file".to_owned(),
            ))
        }
    }))
}

/// get explicit output file
fn get_output(
    config_path: &Option<PathBuf>,
    action: Action,
    input: &Option<PathBuf>,
    argv_output: &Option<PathBuf>,
    is_stdout: bool,
    format: Option<Format>,
) -> Result<(Option<PathBuf>, Format)> {
    if is_stdout && argv_output.is_some() {
        warn!("commands: stdout option conflicts with output option. Priority to the former.");
    }
    // is_stdout OR exception for Generate Technique which is designed to work from stdin: default stdout unless output specified
    if is_stdout || (action == Action::GenerateTechnique && argv_output == &None) {
        return Ok((None, get_output_format(action, format, &None)?.1));
    }

    let technique = Some(match (&argv_output, config_path, input) {
        (Some(o), _, _) if o.parent().filter(|p| p.is_dir()).is_some() => o.to_owned(),
        (Some(o), Some(c), _) => {
            let path = c.join(o);
            if path.parent().filter(|p| p.is_dir()).is_some() {
                path
            } else {
                return Err(Error::new(
                    "Commands: paths do not match any existing directory".to_owned(),
                ));
            }
        }
        (None, Some(c), _) if c.is_file() => c.to_owned(),
        (_, _, Some(i)) => i.to_owned(),
        (_, _, None) => {
            return Err(Error::new(format!(
                "Commands: no parameters or configuration output or input to base output file on"
            )))
        }
    });

    // format is part of output file so it makes sense to return it from this function plus it needs to be defined here to update output if needed
    let (format_as_str, format) = get_output_format(action, format, &technique)?;
    Ok((
        technique.map(|output| output.with_extension(&format_as_str)),
        format,
    ))
}

/// get explicit output. If no explicit output get default path + filename. I none, use input path (and update format). If none worked, error
fn get_output_format(
    action: Action,
    format: Option<Format>,
    output: &Option<PathBuf>,
) -> Result<(String, Format)> {
    if action == Action::Compile && format.is_some() {
        info!("Command line format option used");
    }

    // All formats but Compile are hardcoded in Opt implementation, so this is partly double check
    match (action, format) {
        (Action::Compile, Some(fmt)) if fmt == Format::CFEngine || fmt == Format::DSC => {
            Ok((format!("{}.{}", "rl", fmt), fmt))
        }
        (Action::Compile, _) => {
            info!("Commands: missing or invalid format, deducing it from output file extension");
            let ext = match output {
                Some(o) => o.extension(),
                None => None,
            };
            match ext.and_then(|fmt| fmt.to_str()) {
                Some(fmt) => {
                    let fmt = Format::from_str(fmt)?;
                    return Ok((format!("{}", fmt), fmt))
                }
                None => return Err(Error::new(
                    "Commands: missing or invalid format, plus unrecognized or invalid output file extension".to_owned(),
                ))
            }
        }
        (_, Some(fmt)) => Ok((format!("{}", fmt), fmt)),
        (_, None) => {
            panic!("Commands: format should have been defined earlier in program execution")
        }
    }
}
