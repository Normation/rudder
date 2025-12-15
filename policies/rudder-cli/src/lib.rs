// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2022 Normation SAS

pub mod logs;

use ariadne::{Config, IndexType, Label, Report, ReportKind, Source};
use std::fmt::{Display, Formatter};
use std::io::IsTerminal;
use std::ops::Range;
use std::panic;
use std::process::exit;

// taken from https://github.com/yamafaktory/jql/commit/12f5110b3443c33c09cf60d03fe638c2c266de98
// under MIT/Apache 2 license

/// Use a custom hook to manage broken pipe errors.
///
/// See https://github.com/rust-lang/rust/issues/46016 for cause.
pub fn custom_panic_hook_ignore_sigpipe() {
    // Take the hook.
    let hook = panic::take_hook();

    // Register a custom panic hook.
    panic::set_hook(Box::new(move |panic_info| {
        let panic_message = panic_info.to_string();

        // Exit on a broken pipe message.
        if panic_message.contains("Broken pipe") || panic_message.contains("os error 32") {
            exit(0);
        }

        // Hook back to default.
        (hook)(panic_info)
    }));
}

#[derive(Debug, Clone)]
pub enum FileRange {
    Byte(Range<usize>),
    Char(Range<usize>),
}

/// Represents a range in a file
///
/// It can be either a byte range or a character range depending on its source.
impl FileRange {
    fn to_ariadne_range(&self) -> (IndexType, Range<usize>) {
        match self.clone() {
            FileRange::Byte(r) => (IndexType::Byte, r),
            FileRange::Char(r) => (IndexType::Char, r),
        }
    }
}

#[derive(Debug, Clone, Copy)]
pub enum OutputType {
    Report,
    Terminal,
}

/// Compiler-like error in a file, pointing at a specific range
///
/// Example:
///
/// ```text
/// Error: Check error
//    ╭─[ /etc/hosts:1:1 ]
//    │
//  1 │ 192.168.215.135 lists.normation.com
//    │ ───────┬───────
//    │        ╰───────── IP is not in allowed range: 10.0.0.0/16
//    │
//    │ Note: This is a note
// ───╯
/// ```
pub struct FileError<'a> {
    title: &'a str,
    message: &'a str,
    range: FileRange,
    file_name: &'a str,
    file_content: &'a str,
    note: Option<&'a str>,
}

impl<'a> FileError<'a> {
    pub fn new(
        title: &'a str,
        message: &'a str,
        range: FileRange,
        file_name: &'a str,
        file_content: &'a str,
        note: Option<&'a str>,
    ) -> Self {
        Self {
            title,
            message,
            range,
            file_name,
            file_content,
            note,
        }
    }

    pub fn render(&self, in_terminal: Option<bool>) -> String {
        let (index_type, range) = self.range.to_ariadne_range();
        let span = (self.file_name, range);

        #[cfg(not(test))]
        let in_terminal = in_terminal.unwrap_or_else(|| std::io::stdout().is_terminal());
        #[cfg(test)]
        let in_terminal = false;

        let mut report = Report::build(ReportKind::Error, span.clone())
            .with_config(
                Config::default()
                    .with_color(false)
                    .with_index_type(index_type)
                    .with_color(in_terminal)
                    .with_compact(!in_terminal),
            )
            .with_message(self.title)
            .with_label(Label::new(span).with_message(self.message));
        if let Some(n) = self.note {
            report = report.with_note(n);
        }
        let report = report.finish();
        let source = Source::from(self.file_content);
        let mut out = vec![];

        report.write((self.file_name, source), &mut out).unwrap();
        String::from_utf8_lossy(&out).to_string()
    }
}

impl Display for FileError<'_> {
    fn fmt(&self, f: &mut Formatter<'_>) -> std::fmt::Result {
        let rendered = self.render(None);
        write!(f, "{}", rendered)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_inline_check_error() {
        let report = FileError::new(
            "Check error",
            "IP is not in allowed range: 10.0.0.0/16",
            FileRange::Char(0..15),
            "/etc/hosts",
            "192.168.215.135 lists.normation.com\n192.168.215.12 mail.normation.com",
            Some("This is a note"),
        );
        let output = include_str!("../tests/report.log");
        let output_stdout = include_str!("../tests/report-stdout.log");
        assert_eq!(report.render(Some(false)), output);
        assert_eq!(report.render(Some(true)), output_stdout);
    }
}
