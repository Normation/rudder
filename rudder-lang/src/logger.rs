// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use crate::Action;
use log::LevelFilter;
use regex::Regex;
use std::{
    env::current_dir,
    fmt::Display,
    io::Write,
    panic,
    time::{SystemTime, UNIX_EPOCH},
};

#[derive(Clone, Copy, PartialEq)]
pub enum Logger {
    Terminal,
    Json,
}

impl Logger {
    /// Initialize the logger
    pub fn init(self, log_level: LevelFilter, action: Action) {
        // Content called when panic! is encountered to close logger brackets and print error
        Self::set_panic_hook(self, action);

        let mut builder = env_logger::Builder::new();
        if self == Logger::Json {
            Self::start_json(action);
            // prevents any output stylization from the colored crate
            colored::control::set_override(false);
            // Note: record .file() and line() allow to get the origin of the print
            builder.format(move |buf, record| {
                writeln!(
                    buf,
                    r#"    {{
      "status": "{}",
      "message": {:#?}
    }},"#,
                    record.level().to_string().to_ascii_lowercase(),
                    record.args().to_string()
                )
            });
        }
        builder
            .filter(None, log_level)
            .format_timestamp(None)
            .format_level(false)
            .format_module_path(false)
            .init();
    }

    fn start_json(action: Action) {
        let start = SystemTime::now();
        let time = match start.duration_since(UNIX_EPOCH) {
            Ok(since_the_epoch) => since_the_epoch.as_millis().to_string(),
            Err(_) => "could not get correct time".to_owned(),
        };
        println!(
            "{{\n  \"action\": \"{}\",\n  \"time\": \"{}\",\n  \"logs\": [",
            action, time
        );
    }

    /// Trick function to get the core::PanicInfo.message content as a string
    /// since PanicInfo.message is not exposed and getting `message()` is nightly
    /// As soon as getting message() becomes stable, use it and delete this function
    /// It is a edge case but not doing it would eventually break json format
    fn parse_core_panic_message(msg: &str) -> String {
        // note: expect message will be cut if it includes `: `. So not perfect solution, yet the best I found
        // the following only works on `panic!` call
        let re_panic = Regex::new(r#"^.+'(?P<e>.+?): .+message: "(?P<u>.+)".+$"#).unwrap();
        let mut filtered_msg = re_panic.replace(msg, "$e. ($u)");
        if filtered_msg == msg {
            let re_user_unwrap = Regex::new(r#"^.+User\("(?P<e>.+?)"\).+$"#).unwrap();
            filtered_msg = re_user_unwrap.replace(msg, "$e");
        }
        filtered_msg.to_string()
    }

    /// panic default format takeover to print either proper json format output
    /// or rudder-lang own error logging format
    fn set_panic_hook(self, action: Action) {
        panic::set_hook(Box::new(move |e| {
            let e_message = match e.payload().downcast_ref::<&str>() {
                Some(msg) => msg.to_string(),
                None => Self::parse_core_panic_message(&e.to_string()),
            };
            let location = match e.location() {
                Some(loc) => format!(" in file '{}' at line {}", loc.file(), loc.line()),
                None => String::new(),
            };
            let message = format!(
                "The following unrecoverable error occurred {}: '{}'",
                location, e_message
            );
            match self {
                Logger::Json => println!(
                    r#"    {{
      "result": {{
        "status": "unrecoverable error",
        "message": "{}"
      }}
    }}
  ]
}},"#,
                    action
                ),
                Logger::Terminal => error!("{}", message),
            };
        }));
    }

    pub fn end<T: Display>(self, is_success: bool, input_file: T, output_file: T) {
        let pwd = current_dir().unwrap_or_default();
        match self {
            Logger::Json => {
                let res_str = if is_success { "success" } else { "failure" };
                println!(
                    r#"    {{
      "result": {{
        "status": "{}",
        "from": "{}",
        "to": "{}",
        "pwd": {:?}
      }}
    }}
  ]
}},"#,
                    res_str, input_file, output_file, pwd
                );
            }
            Logger::Terminal => {
                if is_success {
                    println!("'{}' written", output_file);
                } else {
                    println!(
                        "An error occurred, '{}' file has not been created from '{}'",
                        output_file, input_file
                    );
                }
            }
        }
    }
}
