// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use crate::Action;
use colored::Colorize;
use lazy_static::lazy_static;
use log::LevelFilter;
use regex::Regex;
use std::{
    env::current_dir,
    fmt,
    io::Write,
    panic,
    time::{SystemTime, UNIX_EPOCH},
};

#[derive(Clone)]
pub struct Backtrace(backtrace::Backtrace);

impl Backtrace {
    pub fn new() -> Self {
        Self(backtrace::Backtrace::new())
    }

    /// this funtion can be invoked from anywhere in the code to get a proper backtrace up to the creation of the programs thread
    /// This could be done for debug purposes or placed at strategic places like panic calls
    fn format_symbol(index: usize, sym: &backtrace::BacktraceSymbol) -> Option<String> {
        lazy_static! {
            // if starts with rudderc + remove ending addr that is not helpful as is + handle aliases
            static ref SYMBOL_PATH: Regex = Regex::new(r"^<?(?P<path>rudderc(::[{}\d\w]+)+)( as .*>(?P<endingpath>::[{}\d\w]+)+)?(::[\da-z]+)$").unwrap();
        }

        Self::get_symbol_name(sym).and_then(|str_name| {
            SYMBOL_PATH
                .captures(&str_name)
                .and_then(|caps| match (caps.name("path"), caps.name("endingpath")) {
                    (None, _) => None,
                    (Some(start), Some(end)) => [start.as_str(), end.as_str()].concat().into(),
                    (Some(start), None) => start.as_str().to_owned().into(),
                })
                .and_then(|fmt_name| {
                    // do not put logger in the backtrace since it always ultimately calls panic_hook and print_backtrace
                    if fmt_name.starts_with("rudderc::logger") || fmt_name.starts_with("rudderc::error::Error::new") {
                        return None;
                    }
                    Some(format!(
                        "  {offset}{name} at '{filename}:{line}'",
                        offset = " ".repeat(index * 2),
                        name = fmt_name,
                        filename = Self::get_symbol_filename(sym),
                        line = Self::get_symbol_line(sym)
                    ))
                })
        })
    }

    fn get_symbol_name(sym: &backtrace::BacktraceSymbol) -> Option<String> {
        sym.name().and_then(|name| format!("{:?}", name).into())
    }

    fn get_symbol_line(sym: &backtrace::BacktraceSymbol) -> String {
        sym.lineno()
            .and_then(|n| n.to_string().into())
            .unwrap_or("undefined".to_owned())
    }

    fn get_symbol_filename(sym: &backtrace::BacktraceSymbol) -> String {
        sym.filename()
            .and_then(|path| path.to_str())
            .unwrap_or("undefined")
            .to_owned()
    }
}

/// Display backtrace to the final user
impl fmt::Display for Backtrace {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        let stringified_trace = self.0
            .frames()
            .iter()
            .filter_map(|frame| {
                frame
                    .symbols()
                    .into_iter()
                    .enumerate()
                    .map(|(index, sym)| Self::format_symbol(index, sym))
                    .collect::<Option<Vec<String>>>()
            })
            .flatten()
            .collect::<Vec<String>>()
            .join("\n");

        write!(f, "\nTrace:\n{}", stringified_trace)
    }
}

#[derive(Clone, Copy, PartialEq)]
pub enum Logger {
    Terminal,
    Json,
}

impl Logger {
    /// Initialize the logger
    pub fn init(self, log_level: LevelFilter, action: Action, backtrace_enabled: bool) {
        // Content called when panic! is encountered to close logger brackets and print error
        Self::set_panic_hook(self, action, backtrace_enabled);

        if backtrace_enabled {
            std::env::set_var("RUDDERC_BACKTRACE", "1");
        }

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
        lazy_static! {
            // applies in case of ??? failure ->          ...'<error>: ...message: "<msg>"...
            static ref RE_EXPECT: Regex = Regex::new(r#"^.+'(?P<e>.+?): .+message: "(?P<u>.+)".+$"#).unwrap();
            // applies in case of ??? failure ->        ...User("<...>")"...
            static ref RE_USER: Regex = Regex::new(r#"^.+User\("(?P<e>.+?)"\).+$"#).unwrap();
            // on expect/unwrap() failure ->             panicked at '<msg>', [path:line]...
            static ref RE_UNWRAP: Regex = Regex::new(r#"^panicked at '(?P<e>.+?)', [\w\-/.:]+$"#).unwrap();
        }

        let mut filtered_msg = RE_EXPECT.replace(msg, "$e. ($u)");
        if filtered_msg == msg {
            filtered_msg = RE_USER.replace(msg, "$e");
        }
        if filtered_msg == msg {
            filtered_msg = RE_UNWRAP.replace(msg, "$e");
        }
        filtered_msg.to_string()
    }

    /// panic default format takeover to print either proper json format output
    /// or rudder-lang own error logging format
    fn set_panic_hook(self, action: Action, backtrace_enabled: bool) {
        panic::set_hook(Box::new(move |panic_info| {
            let e_message = match panic_info.payload().downcast_ref::<&str>() {
                Some(msg) => msg.to_string(), // PANIC!
                None => Self::parse_core_panic_message(&panic_info.to_string()), // UNWRAP failed
            };
            let location = match panic_info.location() {
                Some(loc) => format!(" at '{}:{}'", loc.file(), loc.line()),
                None => "".to_owned(),
            };
            let backtrace = match backtrace_enabled {
                true => Some(Backtrace::new()),
                false => None,
            };
            let message = format!(
                "{}: an unrecoverable error occurred{}: {}{}",
                "rudderc failure".red().bold(),
                location,
                e_message,
                backtrace.map_or("".to_owned(), |bt| bt.to_string())
            );
            match self {
                Logger::Json => println!(
                    r#"    {{
      "result": {{
        "status": "rudderc: unrecoverable error",
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

    pub fn end<T: fmt::Display>(self, is_success: bool, input_file: T, output_file: T) {
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
