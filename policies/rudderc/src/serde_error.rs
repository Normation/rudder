// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

//! Human-readable YAML parsing errors, showing the source of the error in context.
//!
//! Example output:
//!
//! ```text
//!    | values:
//!    |   - 'first'
//!    |   - 'second'
//!  4 |   - third:
//!    |     ^ values[2]: invalid type: map, expected a string at line 4 column 5
//! ```
//!
//! Vendored from <https://github.com/AlexanderThaller/format_serde_error> 0.3.1,
//! by Alexander Thaller, under MIT license. We only keep what we use: the
//! `serde_yaml` error type, and colored output. The other differences with
//! upstream:
//!
//! * long lines are split on chars instead of grapheme clusters, which only
//!   changes where a very long line is truncated in the message,
//! * the context sizes are constants instead of globally mutable state,
//! * the error column is computed with a saturating subtraction, as the
//!   upstream computation can underflow and panic when the error is inside the
//!   stripped indentation (a tab-indented technique is enough to hit it).
//!
//! The output was checked to be byte-identical to upstream's on a range of
//! inputs, the only exceptions being those two cases.

use colored::Colorize;
use std::fmt;

/// Number of lines shown before and after the line containing the error.
const CONTEXT_LINES: usize = 3;

/// Number of characters shown before and after the column containing the error,
/// when the line is too long to be shown in full.
const CONTEXT_CHARACTERS: usize = 30;

/// Separator between the line numbers and the source.
const SEPARATOR: &str = " | ";

/// Marks a line that has been truncated.
const ELLIPSIS: &str = "...";

/// A `serde_yaml` error, formatted with the part of the input it points to.
#[derive(Debug)]
pub struct SerdeError {
    input: String,
    message: String,
    line: Option<usize>,
    column: Option<usize>,
}

impl std::error::Error for SerdeError {}

impl fmt::Display for SerdeError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        self.format(f)
    }
}

impl SerdeError {
    pub fn new(input: String, err: serde_yaml::Error) -> Self {
        let (message, line, column) = match err.location() {
            // Without a location we can't point at anything
            None => (err.to_string(), None, None),
            Some(location) => (
                err.to_string(),
                Some(location.line()),
                // The location is 1-based, we want an offset
                Some(location.column().saturating_sub(1)),
            ),
        };

        Self {
            input,
            message,
            line,
            column,
        }
    }

    fn format(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        // Without a location, we can't do better than the raw message
        if self.line.is_none() && self.column.is_none() {
            return writeln!(f, "{}", self.message.red().bold());
        }

        let error_line = self.line.unwrap_or_default();
        let error_column = self.column.unwrap_or_default();

        // Skip until we are `CONTEXT_LINES` before the error line, plus the line with
        // the error itself. Saturating, as an error in the first few lines can't have
        // that much context before it.
        let skip = usize::saturating_sub(error_line, CONTEXT_LINES + 1);
        // Lines before and after the error, plus the line with the error itself
        let take = CONTEXT_LINES * 2 + 1;

        let shown_lines: Vec<String> = self
            .input
            .lines()
            .skip(skip)
            .take(take)
            .map(|line| line.replace('\t', " "))
            .collect();

        // An empty input can't be shown in context either
        if shown_lines.is_empty() {
            return writeln!(f, "{}", self.message.red().bold());
        }

        // Remove the indentation the shown lines have in common, to save horizontal
        // space. We can't trim each line, as that would lose the relative indentation.
        let whitespace_count = shown_lines
            .iter()
            .map(|line| line.chars().take_while(|c| c.is_whitespace()).count())
            .min()
            .unwrap_or_default();

        let separator = SEPARATOR.blue().bold();
        // Lines without a number are aligned with whitespace instead
        let fill_line_position = format!("{: >fill$}", "", fill = error_line.to_string().len());

        // The caller may have written something before us on this line, e.g. anyhow
        // prefixes the output with "Error:"
        writeln!(f)?;

        self.input
            .lines()
            .enumerate()
            .skip(skip)
            .take(take)
            .map(|(index, text)| {
                (
                    // Line numbers start at 1
                    index + 1,
                    text.chars()
                        .skip(whitespace_count)
                        .collect::<String>()
                        .replace('\t', " "),
                )
            })
            .try_for_each(|(line_position, text)| {
                self.format_line(
                    f,
                    line_position,
                    error_line,
                    error_column,
                    text,
                    whitespace_count,
                    &separator,
                    &fill_line_position,
                )
            })
    }

    #[allow(clippy::too_many_arguments)]
    fn format_line(
        &self,
        f: &mut fmt::Formatter<'_>,
        line_position: usize,
        error_line: usize,
        error_column: usize,
        text: String,
        whitespace_count: usize,
        separator: &colored::ColoredString,
        fill_line_position: &str,
    ) -> fmt::Result {
        if line_position != error_line {
            return writeln!(f, " {}{}{}", fill_line_position, separator, text.yellow());
        }

        // A long line is one longer than the context shown on either side of the
        // error, plus the error itself
        let is_long_line = CONTEXT_CHARACTERS * 2 + 1 < text.len();

        let (context_line, error_column, truncated_before, truncated_after) = if is_long_line {
            Self::truncate_long_line(&text, error_column)
        } else {
            (text, error_column, false, false)
        };

        // The line with the error
        write!(
            f,
            " {}{}",
            line_position.to_string().blue().bold(),
            separator
        )?;
        if truncated_before {
            write!(f, "{}", ELLIPSIS.blue().bold())?;
        }
        write!(f, "{context_line}")?;
        if truncated_after {
            write!(f, "{}", ELLIPSIS.blue().bold())?;
        }
        writeln!(f)?;

        // The message, below the column it points to. We need to account for the
        // indentation we stripped, and for the ellipsis if there is one.
        let ellipsis_space = if truncated_before { ELLIPSIS.len() } else { 0 };
        let marker = format!(
            "{: >column$}^ {}",
            "",
            self.message,
            column = (error_column + ellipsis_space).saturating_sub(whitespace_count)
        );
        writeln!(
            f,
            " {}{}{}",
            fill_line_position,
            separator,
            marker.red().bold()
        )
    }

    /// Keeps `CONTEXT_CHARACTERS` around the error column, and reports whether
    /// the line was truncated before and after the kept part.
    fn truncate_long_line(text: &str, error_column: usize) -> (String, usize, bool, bool) {
        let chars: Vec<char> = text.chars().collect();

        // Same reasoning as for lines, on characters this time
        let skip = usize::saturating_sub(error_column, CONTEXT_CHARACTERS + 1);
        let take = CONTEXT_CHARACTERS * 2 + 1;

        let truncated_before = skip != 0;
        let truncated_after = skip + take < chars.len();

        let line: String = chars.into_iter().skip(skip).take(take).collect();
        // The error moved left by what we skipped
        let error_column = usize::saturating_sub(error_column, skip);

        (line, error_column, truncated_before, truncated_after)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use pretty_assertions::assert_eq;

    #[derive(Debug, serde::Deserialize)]
    struct Config {
        #[allow(dead_code)]
        values: Vec<String>,
    }

    fn error_for(input: &str) -> String {
        let err = serde_yaml::from_str::<Config>(input).unwrap_err();
        colored::control::set_override(false);
        SerdeError::new(input.to_string(), err).to_string()
    }

    #[test]
    fn it_shows_the_line_and_column_of_the_error() {
        let input = "values:\n  - 'first'\n  - 'second'\n  - third:\n";
        assert_eq!(
            error_for(input),
            "
   | values:
   |   - 'first'
   |   - 'second'
 4 |   - third:
   |     ^ values[2]: invalid type: map, expected a string at line 4 column 5
"
        );
    }

    #[test]
    fn it_only_shows_the_lines_around_the_error() {
        let input = format!("values:\n{}  - third:\n", "  - 'a'\n".repeat(10));
        let shown = error_for(&input);
        assert_eq!(shown.lines().filter(|l| !l.is_empty()).count(), 5);
        // the common indentation is stripped
        assert!(shown.contains(" 12 | - third:"), "{shown}");
    }

    #[test]
    fn it_truncates_the_error_line_when_it_is_too_long() {
        let input = format!("values:\n  - x{}: 1\n", "a".repeat(200));
        let shown = error_for(&input);
        // only the part of the line around the error is shown
        assert!(shown.contains(ELLIPSIS), "{shown}");
        assert!(shown.lines().all(|l| l.chars().count() < 150), "{shown}");
    }

    #[test]
    fn it_does_not_truncate_context_lines() {
        // the long line here is context, the error is on the short line below it
        let input = format!("values:\n  - '{}'\n  - third:\n", "a".repeat(200));
        let shown = error_for(&input);
        assert!(!shown.contains(ELLIPSIS), "{shown}");
    }

    #[test]
    fn it_falls_back_to_the_raw_message_without_an_input() {
        let err = serde_yaml::from_str::<Config>("values: 1").unwrap_err();
        colored::control::set_override(false);
        // An input that does not match the error can't happen in practice, but must
        // not panic: we print the message alone.
        assert_eq!(
            SerdeError::new(String::new(), err).to_string(),
            "values: invalid type: integer `1`, expected a sequence at line 1 column 9\n"
        );
    }
}
