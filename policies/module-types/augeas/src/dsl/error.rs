// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2025 Normation SAS

//! Represents a check error, with a message and a span in a file.

use rudder_module_type::cli::{FileError, FileRange};
use std::ops::Range;

/// Converts a raugeas span to a generic span
fn convert_span(span: raugeas::Span) -> (String, Range<usize>) {
    (
        span.filename.unwrap_or("".to_string()),
        span.span.start as usize..span.span.end as usize,
    )
}

pub fn format_report_from_span(
    title: &str,
    message: &str,
    span: raugeas::Span,
    file_content: &str,
    note: Option<&str>,
) -> String {
    let (file_name, range) = convert_span(span);
    let error = FileError::new(
        title,
        message,
        FileRange::Char(range),
        &file_name,
        file_content,
        note,
    );
    error.render(None)
}

#[cfg(test)]
mod tests {
    use std::fs;

    use super::*;

    #[test]
    fn test_inline_check_error() {
        let report = format_report_from_span(
            "Check error",
            "IP is not in allowed range: 10.0.0.0/16",
            raugeas::Span {
                label: Default::default(),
                filename: Some("/etc/hosts".to_string()),
                span: Range { start: 0, end: 15 },
                value: Default::default(),
            },
            "192.168.215.135 lists.normation.com\n192.168.215.12 mail.normation.com",
            Some("This is a note"),
        );
        fs::write("tests/report.log", report).unwrap();
        //let reference = include_str!("../../tests/report.log");
        //assert_eq!(&report, reference);
    }
}
