
// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use std::path::PathBuf;

use crate::error::*;

/// get explicit input. If none, get default path + filename. If none, error
fn get_input(exec_action: &str, paths: &toml::Value, opt_default: &Option<PathBuf>, opt_input: &Option<PathBuf>) -> Result<PathBuf> {
    Ok(match opt_input {
        Some(input) => input.to_path_buf(),
        None => match paths.get(format!("{}_input", exec_action)) {
            None => return Err(Error::User("There should either be an explicit or default input path".to_owned())),
            Some(path) => {
                let filename = match opt_default {
                    Some(filename) => filename,
                    None => return Err(Error::User("Could not determine output path without input or default defined".to_owned())),
                };
                PathBuf::from(path.as_str().unwrap()).join(filename)
            }
        }
    })
}

/// get explicit output. If no explicit output get default path + filename. I none, use input path (and update extension). If none worked, error
fn get_output(exec_action: &str, paths: &toml::Value, opt_default: &Option<PathBuf>, opt_input: &Option<PathBuf>, opt_output: &Option<PathBuf>) -> Result<PathBuf> {
    Ok(match opt_output {
        Some(output) => output.to_path_buf(),
        None => {
            let path = match opt_default {
                Some(filename) => match paths.get(format!("{}_output", exec_action)) {
                    Some(default_path) => PathBuf::from(default_path.as_str().unwrap()).join(&filename),
                    // if no default, try to get input path
                    None => match get_input(exec_action, paths, opt_default, opt_input) {
                        Ok(input) => input,
                        Err(_) => return Err(Error::User("Could not determine output path without ouput default or input defined".to_owned())),
                    },
                },
                // if no default, try to get input path
                None => match get_input(exec_action, paths, opt_default, opt_input) {
                    Ok(input) => input,
                    Err(_) => return Err(Error::User("Could not determine output path without ouput default or input defined".to_owned())),
                }
            };
            if exec_action == "compile" {
                path.with_extension("rl.cf")
            } else {
                path.with_extension("json.rl")
            }
        }
    })
}

/// get the correct input and output paths based on parameters
/// Input priority is input > default
/// Output prority is output > default > input
pub fn get(exec_action: &str, opt_default: &Option<PathBuf>, opt_input: &Option<PathBuf>, opt_output: &Option<PathBuf>) -> Result<(PathBuf, PathBuf)> {
    // Ease of read closure
    let err_gen = |e: &str| Err(Error::User(format!("{}", e)));

    let config_filename = "tools/rudderc.conf";
    let config: toml::Value = match std::fs::read_to_string(config_filename) {
        Err(_) => return err_gen("Could not read toml config file"),
        Ok(config_data) => match toml::from_str(&config_data) {
            Ok(config) => config,
            Err(_) => return err_gen("Could not parse (probably faulty) toml config file"),
        }
    };
    let paths = match config.get("default_paths") {
        None => return err_gen("No default_path section in toml config file"),
        Some(m) => m
    };

    Ok((
        get_input(exec_action, paths, opt_default, opt_input)?,
        get_output(exec_action, paths, opt_default, opt_input, opt_output)?,
    ))
}
