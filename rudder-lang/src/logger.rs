// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use log::LevelFilter;
use regex::Regex;
use std::{
    io::Write,
    panic,
    path::{Path, PathBuf},
    time::{SystemTime, UNIX_EPOCH}
};

/// Adds verbose levels: off, error, warn, info, debug, trace. For example info includes error, debug includes info and error
/// The level is set through program arguments. Default is Warn
/// run the program with `-l info` (eq. `--logger info`) optional argument. Case-insensitive
/// There is also an optional json formatter that outputs plain json format. run the program with `-j` or `--json` optional argument.
pub fn set(log_level: Option<LevelFilter>, is_fmt_json: bool, input: &Path, output: &Path, exec_action: &str) {
    let log_level = match log_level {
        Some(level) => level,
        None => LevelFilter::Warn
    };

    // Content called when panic! is encountered to close logger brackets and print error
    set_panic_hook(is_fmt_json, exec_action.to_owned());

    if is_fmt_json {
        print_json_fmt_openning(input, output, exec_action);
        // prevents any output stylization from the colored crate
        colored::control::set_override(false);
    }
    
    let mut builder = env_logger::Builder::new();
    if is_fmt_json {
        // Note: record .file() and line() allow to get the origin of the print
        builder.format(move |buf, record| {
            writeln!(buf, r#"    {{
      "status": {:?},
      "message": {:?}
    }},"#,
            record.level().to_string().to_ascii_lowercase(),
            record.args().to_string()
        )});
    }
    builder.filter(None, log_level)
    .format_timestamp(None)
    .format_level(false)
    .format_module_path(false)
    .init();
}

/// Trick function to get the core::PanicInfo.message content as a string
/// since PanicInfo.message is not exposed and getting `message()` is nightly
/// As soon as getting message() becomes stable, use it and delete this function
/// It is a edge case but not doing it would eventually break json format
fn parse_core_panic_message(msg: &str) -> String {
    // note: expect message will be cut if it includes `: `. So not perfect solution, yet the best I found
    let re = Regex::new(r#"^.+'(?P<e>.+?): .+message: "(?P<u>.+)".+$"#).unwrap();
    let msg = re.replace_all(msg, "$e. ($u)");
    msg.to_string()
}

/// panic default format takeover to print either proper json format output
/// or rudder-lang own error logging format
fn set_panic_hook(is_fmt_json: bool, exec_action: String) {
    panic::set_hook(Box::new(move |e| {
        let e_message = match e.payload().downcast_ref::<&str>() {
            Some(msg) => msg.to_string(),
            None => parse_core_panic_message(&e.to_string()),
        };
        let location = match e.location() {
            Some(loc) => {
                format!(" in file '{}' at line {}", loc.file(), loc.line())
            },
            None => String::new()
        };
        let message = format!("The following unrecoverable error occured{}: '{}'", location, e_message);
        if is_fmt_json {
            println!(r#"    {{
      "Result": {{
          "action": {:?},
          "status": "unrecoverable error",
          "message": {:?}
      }}
    }}
  ]
}}"#, exec_action, message);
        } else {
            error!("{}", message);
        }
    }));
}

fn print_json_fmt_openning(input: &Path, output: &Path, exec_action: &str) {
    let start = SystemTime::now();
    let time = match start.duration_since(UNIX_EPOCH) {
        Ok(since_the_epoch) => since_the_epoch.as_millis().to_string(),
        Err(_) => "could not get correct time".to_owned()
    };
    println!("{{\n  \"action\": {:?},\n  \"input\": {:?},\n  \"output\": {:?},\n  \"time\": {:?},\n  \"logs\": [", exec_action, input, output, time);
}

pub fn print_output_closure(is_fmt_json: bool, is_success: bool, input_file: &str, output_file: &str, exec_action: &str) {
    let pwd = std::env::current_dir().unwrap_or(PathBuf::new());
    match is_fmt_json {
        true => {
            let res_str = match is_success {
                true => "success",
                false => "failure"
            };
            println!(r#"    {{
      "Result": {{
        "action": {:?},
        "status": {:?},
        "from": {:?},
        "to": {:?},
        "pwd": {:?}
      }}
    }}
  ]
}}"#, exec_action, res_str, input_file, output_file, pwd);
        },
        false => {
            let res_str = match is_success {
                true => format!("Everything worked as expected, \"{}\" generated from \"{}\"", output_file, input_file),
                false => format!("An error occured, \"{}\" file has not been created from \"{}\"", output_file, input_file)
            };
            println!("{}", res_str)
        }
    };
}
