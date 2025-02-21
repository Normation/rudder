// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2025 Normation SAS

//! Represents a check error

use ariadne::{Config, Label, Report, ReportKind, Source};
use std::ops::Range;

/// Describes an error that can represented as a diagnostic
/// pointing to a precise span in the source file.
// FIXME: ref
pub struct InlineCheckReport {
    /// The error title
    title: String,
    /// The error message
    message: String,
    /// An optional note about the error
    note: Option<String>,
    /// The span of the error
    span: raugeas::Span,
    file_content: String,
}

impl InlineCheckReport {
    /// Create a new error
    pub fn new(
        title: String,
        message: String,
        span: raugeas::Span,
        file_content: String,
        note: Option<String>,
    ) -> Self {
        Self {
            title,
            message,
            note,
            span,
            file_content,
        }
    }

    /// Converts a raugeas span to an ariadne span
    fn span(span: &raugeas::Span) -> (String, Range<usize>) {
        (
            span.filename.clone().unwrap_or("".to_string()),
            span.span.start as usize..span.span.end as usize,
        )
    }

    pub fn report<'a>(self) -> String {
        let span = Self::span(&self.span);

        let mut report = Report::build(ReportKind::Error, span.clone())
            .with_config(Config::default().with_color(false))
            .with_message(self.title.clone())
            .with_label(Label::new(span).with_message(self.message.clone()));
        if let Some(n) = self.note.clone() {
            report = report.with_note(n);
        }
        let report = report.finish();
        let source = Source::from(self.file_content.as_str());
        let mut out = vec![];
        report
            .write((self.span.filename.unwrap(), source), &mut out)
            .unwrap();
        String::from_utf8_lossy(&out).to_string()
    }
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
        let error = InlineCheckReport::new(
            "Check error".to_string(),
            "IP is not in allowed range: 10.0.0.0/16".to_string(),
            raugeas::Span {
                label: Default::default(),
                filename: Some("/etc/hosts".to_string()),
                span: Range { start: 0, end: 15 },
                value: Default::default(),
            },
            "192.168.215.135 lists.normation.com\n192.168.215.12 mail.normation.com".to_string(),
            Some("This is a note".to_string()),
        );
        let report = error.report();
        println!("{}", report);
        let reference = include_str!("../../tests/ariadne.out");
        assert_eq!(trim_lines(&report), trim_lines(reference));
    }
}
