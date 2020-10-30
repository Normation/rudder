// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

mod backtrace;

pub use self::backtrace::Backtrace;

use super::logs::*;
use crate::{command::*, error::Result};
use colored::Colorize;
use lazy_static::lazy_static;
use regex::Regex;
use serde::Serialize;
use std::{
    fs,
    io::Write,
    panic,
    time::{SystemTime, UNIX_EPOCH},
};

// NOTE that OutputFmtPanic is hardcoded, not serializable structure to pass to it
// struct OutputFmtPanic {
//     status: String,
//     message: String,
// }
/// Note that `source` field is either a file or `STDIN`. It can even be empty in some error cases
#[derive(Serialize)]
struct OutputFmtOk {
    command: String,
    source: Option<String>, // source file path or STDIN
    time: String,
    status: String, // either "success" or "failure"
    logs: Logs,
    data: Vec<CommandResult>,
    errors: Vec<String>,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum LogOutput {
    Raw,
    JSON,
    None,
}
impl LogOutput {
    /// Initialize the output generator
    pub fn init(self, command: Command, log_level: LogLevel, is_backtraced: bool) {
        set_global_log_level(log_level);
        // Content called if panic! is encountered to close logger brackets and print error
        self.set_panic_hook(command, is_backtraced);
        // enable rust backtrace if backtrace requested
        if is_backtraced {
            std::env::set_var("RUDDERC_BACKTRACE", "1");
        }
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
    fn set_panic_hook(self, command: Command, is_backtraced: bool) {
        panic::set_hook(Box::new(move |panic_info| {
            let e_message = match panic_info.payload().downcast_ref::<&str>() {
                Some(msg) => msg.to_string(), // PANIC!
                None => Self::parse_core_panic_message(&panic_info.to_string()), // UNWRAP failed
            };
            let location = match panic_info.location() {
                Some(loc) => format!(" at '{}:{}'", loc.file(), loc.line()),
                None => "".to_owned(),
            };
            let message = format!(
                "{}{}: {}",
                "Unrecoverable RUDDERC failure".red().bold(),
                location,
                e_message,
            );
            match self {
                LogOutput::None => (),
                _ => error!("{}", message),
            };

            self.print(command, None, Err(crate::error::Error::new(message)));

            // TODO print backtrace somehow + somewhere else
            println!("{}", Backtrace::new_from_bool(is_backtraced));
        }));
    }

    pub fn print(
        self,
        command: Command,
        source: Option<String>,
        result: Result<Vec<CommandResult>>,
    ) {
        let (is_success, mut data, errors) = match result {
            Ok(data) => (true, data, Vec::new()),
            Err(e) => (false, Vec::new(), e.clean_format_list()),
        };

        let dest_files = &data
            .iter()
            .filter_map(|res| res.destination.as_ref().map(|d| format!("'{:?}'", d)))
            .collect::<Vec<String>>()
            .join(", ");

        let start = SystemTime::now();
        let time = match start.duration_since(UNIX_EPOCH) {
            Ok(since_the_epoch) => since_the_epoch.as_millis().to_string(),
            Err(_) => "could not get correct time".to_owned(),
        };

        // remove data if it has been written to a file to avoid duplicate content
        self.print_to_file(&mut data, command);

        if !is_success {
            let output = if let Some(input) = &source {
                format!(
                    "An error occurred, could not create content from '{}': {}",
                    input,
                    errors.join(" ; ")
                )
            } else {
                format!(
                    "An error occurred, could not create content: {}",
                    errors.join(" ; ")
                )
            };
            eprintln!("{}", output);
        } else if is_success {
            info!("Content written to {}", dest_files);
        }
        // must have cloneable logger, cannot safely clone `static mut`
        // must be done last to avoid logs loss since logs are pushed to STATIC LOGS directly
        let logger = clone_logs();

        if self == LogOutput::JSON {
            let status = if is_success { "success" } else { "failure" };
            let output = OutputFmtOk {
                command: format!("{}", command),
                time,
                status: status.to_owned(),
                source,
                logs: logger.clone(),
                data: data.clone(),
                errors,
            };
            let fmtoutput = serde_json::to_string_pretty(&output)
                .map_err(|e| format!("Building JSON output led to an error: {}", e))
                .unwrap(); // dev error if this does not work
            println!("{}", fmtoutput);
        } else {
            println!("{}", logger);
        }
    }

    // print content into a file and if successfully written, remove it from payload
    fn print_to_file(&self, files: &mut Vec<CommandResult>, command: Command) {
        if self == &LogOutput::None {
            if command == Command::GenerateTechnique {
                println!(
                    "{}",
                    serde_json::to_string_pretty(&files)
                        .map_err(|e| format!("Building JSON output led to an error: {}", e))
                        .unwrap() // dev error if this does not work
                )
            } else if files[0].content.is_some() {
                // no error, expected length = 1
                println!("{}", &files[0].clone().content.unwrap());
            } else {
                panic!("BUG! Output should be stdout but there is no content to print");
            }
        }
        // else
        // note there might be no fle to print. ie technique generate
        for file in files.iter_mut() {
            if let (Some(dest), Some(content)) = (&file.destination, &file.content) {
                if let Some(prefix) = dest.parent() {
                    fs::create_dir_all(prefix)
                        .expect("Could not create output file parent directories");
                }
                let mut file_to_create =
                    fs::File::create(dest).expect("Could not create output file");
                file_to_create
                    .write_all(content.as_bytes())
                    .expect("Could not write content into output file");
                file.content = None;
            } else {
                debug!("File content could not be printed");
            }
        }
    }
}
