// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2025 Normation SAS

//! Represents a check error, with a message and a span in a file.

use ariadne::{Config, Label, Report, ReportKind, Source};
use std::ops::Range;

/// Converts a raugeas span to an ariadne span
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
    format_report(title, message, range, &file_name, file_content, note)
}

pub fn format_report(
    title: &str,
    message: &str,
    range: Range<usize>,
    file_name: &str,
    file_content: &str,
    note: Option<&str>,
) -> String {
    let miette_span = (file_name, range);

    let mut report = Report::build(ReportKind::Error, miette_span.clone())
        .with_config(Config::default().with_color(false))
        .with_message(title)
        .with_label(Label::new(miette_span).with_message(message));
    if let Some(n) = note {
        report = report.with_note(n);
    }
    let report = report.finish();
    let source = Source::from(file_content);
    let mut out = vec![];
    report.write((file_name, source), &mut out).unwrap();
    String::from_utf8_lossy(&out).to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn trim_lines(s: &str) -> String {
        s.lines()
            .map(|l| l.trim())
            .collect::<Vec<&str>>()
            .join("\n")
    }

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
        println!("{report}");
        let reference = include_str!("../../tests/ariadne.out");
        assert_eq!(trim_lines(&report), trim_lines(reference));
    }
}
