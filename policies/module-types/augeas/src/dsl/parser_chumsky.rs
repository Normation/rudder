// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

//! Lexer & parser for the Rudder augeas language.
//!
//! The main goal here is to have quality error messages.

use crate::dsl::comparator::{Comparison, NumComparator};
use crate::dsl::value_type::ValueType;
use crate::dsl::{AugPath, Sub};
use ariadne::{sources, Color, Label, Report, ReportKind};
use chumsky::Parser;
use chumsky::{input::BorrowInput, prelude::*};
use raugeas::Position;
use std::collections::HashMap;
use std::{env, fmt, fs};
use zxcvbn::Score;

pub type Span = SimpleSpan;
pub type Spanned<T> = (T, Span);

#[derive(Clone, Debug)]
pub enum Json {
    Invalid,
    Null,
    Bool(bool),
    Str(String),
    Num(f64),
    Array(Vec<Json>),
    Object(HashMap<String, Json>),
}

fn toto2<'a>() -> impl Parser<'a, &'a str, Json, extra::Err<Rich<'a, char>>> {
    recursive(|value| {
        let comment = just("#")
            .then(any().and_is(just('\n').not()).repeated())
            .padded();

        let digits = text::digits(10).to_slice();

        let frac = just('.').then(digits);

        let exp = just('e')
            .or(just('E'))
            .then(one_of("+-").or_not())
            .then(digits);

        let number = just('-')
            .or_not()
            .then(text::int(10))
            .then(frac.or_not())
            .then(exp.or_not())
            .to_slice()
            .map(|s: &str| s.parse().unwrap())
            .boxed();

        let escape = just('\\')
            .then(choice((
                just('\\'),
                just('/'),
                just('"'),
                just('b').to('\x08'),
                just('f').to('\x0C'),
                just('n').to('\n'),
                just('r').to('\r'),
                just('t').to('\t'),
                just('u').ignore_then(text::digits(16).exactly(4).to_slice().validate(
                    |digits, e, emitter| {
                        char::from_u32(u32::from_str_radix(digits, 16).unwrap()).unwrap_or_else(
                            || {
                                emitter.emit(Rich::custom(e.span(), "invalid unicode character"));
                                '\u{FFFD}' // unicode replacement character
                            },
                        )
                    },
                )),
            )))
            .ignored()
            .boxed();

        let string = none_of("\\\"")
            .ignored()
            .or(escape)
            .repeated()
            .to_slice()
            .map(ToString::to_string)
            .delimited_by(just('"'), just('"'))
            .boxed();

        let array = value
            .clone()
            .separated_by(just(',').padded().recover_with(skip_then_retry_until(
                any().ignored(),
                one_of(",]").ignored(),
            )))
            .allow_trailing()
            .collect()
            .padded()
            .delimited_by(
                just('['),
                just(']')
                    .ignored()
                    .recover_with(via_parser(end()))
                    .recover_with(skip_then_retry_until(any().ignored(), end())),
            )
            .boxed();

        choice((
            just("null").to(Json::Null),
            just("true").to(Json::Bool(true)),
            just("false").to(Json::Bool(false)),
            number.map(Json::Num),
            string.map(Json::Str),
            array.map(Json::Array),
        ))
        .padded_by(comment.repeated())
        .recover_with(via_parser(nested_delimiters(
            '{',
            '}',
            [('[', ']')],
            |_| Json::Invalid,
        )))
        .recover_with(via_parser(nested_delimiters(
            '[',
            ']',
            [('{', '}')],
            |_| Json::Invalid,
        )))
        .recover_with(skip_then_retry_until(
            any().ignored(),
            one_of(",]}").ignored(),
        ))
        .padded()
    })
}

// AST and parser

#[derive(Clone, Debug, PartialEq)]
pub enum Expr<'src> {
    /// A generic augeas command, not parsed.
    GenericAugeas(&'src str),
    /// Sets the value VALUE at location PATH
    Set(AugPath<'src>, crate::dsl::Value<'src>),
    /// Sets multiple nodes (matching SUB relative to PATH) to VALUE
    SetMultiple(AugPath<'src>, Sub<'src>, crate::dsl::Value<'src>),
    /// Removes the node at location PATH
    Remove(AugPath<'src>),
    /// Sets the node at PATH to NULL, creating it if needed
    Clear(AugPath<'src>),
    /// Sets multiple nodes (matching SUB relative to PATH) to NULL
    ClearMultiple(AugPath<'src>, Sub<'src>),
    /// Creates PATH with the value NULL if it does not exist
    Touch(AugPath<'src>),
    /// Inserts an empty node LABEL either before or after PATH.
    Insert(crate::dsl::Value<'src>, Position, AugPath<'src>),
    /// Moves a node at PATH to the new location OTHER PATH
    Move(AugPath<'src>, AugPath<'src>),
    /// Copies a node at PATH to the new location OTHER PATH
    Copy(AugPath<'src>, AugPath<'src>),
    /// Rename a node at PATH to a new LABEL
    Rename(AugPath<'src>, crate::dsl::Value<'src>),
    /// Sets Augeas variable $NAME to PATH
    DefineVar(crate::dsl::Value<'src>, AugPath<'src>),
    /// Sets Augeas variable $NAME to PATH, creating it with VALUE if needed
    DefineNode(
        crate::dsl::Value<'src>,
        AugPath<'src>,
        crate::dsl::Value<'src>,
    ),
    // Comparison contains both the typed value and the comparator
    Compare(AugPath<'src>, Comparison),
    ValuesInclude(AugPath<'src>, &'src str),
    ValuesNotInclude(AugPath<'src>, &'src str),
    ValuesEqual(AugPath<'src>, Vec<&'src str>),
    ValuesNotEqual(AugPath<'src>, Vec<&'src str>),
    MatchSize(AugPath<'src>, NumComparator, usize),
    MatchInclude(AugPath<'src>, &'src str),
    MatchNotInclude(AugPath<'src>, &'src str),
    MatchEqual(AugPath<'src>, Vec<&'src str>),
    MatchNotEqual(AugPath<'src>, Vec<&'src str>),
    /// Check the value at the path has a given type
    ///
    /// Uses the "is" keyword.
    HasType(AugPath<'src>, ValueType),
    /// String length
    ///
    /// Warning: do no use for passwords as the value will be displayed.
    StrLen(AugPath<'src>, NumComparator, usize),
    /// Minimal score
    PasswordScore(AugPath<'src>, Score),
    /// Minimal LUDS values
    PasswordLUDS(AugPath<'src>, u8, u8, u8, u8, u8),
    /// Save the changes to the tree.
    Save,
    /// Quit the script.
    Quit,
    /// (Re)load the tree.
    Load,
}

fn parser<'a>() -> impl Parser<'a, &'a str, Vec<Spanned<Expr<'a>>>, extra::Err<Rich<'a, char>>> {
    let comment = just("#")
        .then(any().and_is(just('\n').not()).repeated())
        .padded();

    // TODO: escape_names = "\"abtnvfr\\";
    let escape = just('\\')
        .then(choice((
            just('\\'),
            just('/'),
            just('"'),
            just('b').to('\x08'),
            just('f').to('\x0C'),
            just('n').to('\n'),
            just('r').to('\r'),
            just('t').to('\t'),
        )))
        .ignored()
        .boxed();

    let quoted_string = none_of("\\\"")
        .ignored()
        .or(escape)
        .repeated()
        .to_slice()
        .delimited_by(just('"'), just('"'));

    let alpha = one_of("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ")
        .repeated()
        .to_slice();
    let string = quoted_string.clone().or(alpha).padded();

    let set = just("set")
        .padded()
        .ignore_then(string.clone())
        .then(string.clone())
        .map(|(path, value): (&str, &str)| Expr::Set(path.into(), value))
        .boxed();
    let setm = just("setm")
        .padded()
        .ignore_then(string.clone())
        .then(string.clone())
        .then(string.clone())
        .map(|((path, sub), value): ((&str, &str), &str)| {
            Expr::SetMultiple(path.into(), sub, value)
        })
        .boxed();
    let defvar = just("defvar")
        .padded()
        .ignore_then(string.clone())
        .then(string.clone())
        .map(|(name, path): (&str, &str)| Expr::DefineVar(name, path.into()))
        .boxed();
    let defnode = just("defnode")
        .padded()
        .ignore_then(string.clone())
        .then(string.clone())
        .then(string.clone())
        .map(|((name, path), value): ((&str, &str), &str)| {
            Expr::DefineNode(name, path.into(), value)
        })
        .boxed();
    let remove = just("remove")
        .padded()
        .ignore_then(string.clone())
        .map(|path: &str| Expr::Remove(path.into()))
        .boxed();
    let clear = just("clear")
        .padded()
        .ignore_then(string.clone())
        .map(|path: &str| Expr::Clear(path.into()))
        .boxed();
    let clearm = just("clearm")
        .padded()
        .ignore_then(string.clone())
        .then(string.clone())
        .map(|(path, sub): (&str, &str)| Expr::ClearMultiple(path.into(), sub))
        .boxed();
    let touch = just("touch")
        .padded()
        .ignore_then(string.clone())
        .map(|path: &str| Expr::Touch(path.into()))
        .boxed();
    let insert = just("insert")
        .padded()
        .ignore_then(string.clone())
        .then(string.clone())
        .then(string.clone())
        .map(|((label, pos), path): ((&str, Position), &str)| Expr::Insert(label, pos, path.into()))
        .boxed();
    let move_ = just("move")
        .padded()
        .ignore_then(string.clone())
        .then(string.clone())
        .map(|(path, other): (&str, &str)| Expr::Move(path.into(), other.into()))
        .boxed();
    let copy = just("copy")
        .padded()
        .ignore_then(string.clone())
        .then(string.clone())
        .map(|(path, other): (&str, &str)| Expr::Copy(path.into(), other.into()))
        .boxed();
    let rename = just("rename")
        .padded()
        .ignore_then(string.clone())
        .then(string.clone())
        .map(|(path, label): (&str, &str)| Expr::Rename(path.into(), label))
        .boxed();
    let compare = just("compare")
        .padded()
        .ignore_then(string.clone())
        .then(NumComparator::parser())
        .map(|(path, comp): (&str, NumComparator)| Expr::Compare(path.into(), comp))
        .boxed();
    let values_include = just("values_include")
        .padded()
        .ignore_then(string.clone())
        .then(string.clone())
        .map(|(path, value): (&str, &str)| Expr::ValuesInclude(path.into(), value))
        .boxed();
    let values_not_include = just("values_not_include")
        .padded()
        .ignore_then(string.clone())
        .then(string.clone())
        .map(|(path, value): (&str, &str)| Expr::ValuesNotInclude(path.into(), value))
        .boxed();
    let values_equal = just("values_equal")
        .padded()
        .ignore_then(string.clone())
        .then(string.clone().repeated())
        .map(|(path, values): (&str, Vec<&str>)| Expr::ValuesEqual(path.into(), values))
        .boxed();
    let values_not_equal = just("values_not_equal")
        .padded()
        .ignore_then(string.clone())
        .then(string.clone().repeated())
        .map(|(path, values): (&str, Vec<&str>)| Expr::ValuesNotEqual(path.into(), values))
        .boxed();
    let match_include = just("match_include")
        .padded()
        .ignore_then(string.clone())
        .then(string.clone())
        .map(|(path, value): (&str, &str)| Expr::MatchInclude(path.into(), value))
        .boxed();
    let match_not_include = just("match_not_include")
        .padded()
        .ignore_then(string.clone())
        .then(string.clone())
        .map(|(path, value): (&str, &str)| Expr::MatchNotInclude(path.into(), value))
        .boxed();
    let match_equal = just("match_equal")
        .padded()
        .ignore_then(string.clone())
        .then(string.clone().repeated().to_slice().collect())
        .map(|(path, values): (&str, Vec<&str>)| Expr::MatchEqual(path.into(), values))
        .boxed();
    let match_not_equal = just("match_not_equal")
        .padded()
        .ignore_then(string.clone())
        .then(string.clone().repeated())
        .map(|(path, values): (&str, Vec<&str>)| Expr::MatchNotEqual(path.into(), values))
        .boxed();
    let match_size = just("match_size")
        .padded()
        .ignore_then(string.clone())
        .then(NumComparator::parser())
        .then(
            text::digits(10)
                .to_slice()
                .map(|s: &str| s.parse().unwrap()),
        )
        .map(|((path, comp), size): (&str, NumComparator, usize)| {
            Expr::MatchSize(path.into(), comp, size)
        })
        .boxed();
    let has_type = just("has_type")
        .padded()
        .ignore_then(string.clone())
        .then(ValueType::parser())
        .map(|(path, ty): (&str, ValueType)| Expr::HasType(path.into(), ty))
        .boxed();
    let str_len = just("str_len")
        .padded()
        .ignore_then(string.clone())
        .then(NumComparator::parser())
        .then(
            text::digits(10)
                .to_slice()
                .map(|s: &str| s.parse().unwrap()),
        )
        .map(|(path, comp, size): (&str, NumComparator, usize)| {
            Expr::StrLen(path.into(), comp, size)
        })
        .boxed();
    let password_score = just("password_score")
        .padded()
        .ignore_then(string.clone())
        .then(
            text::digits(10)
                .to_slice()
                .map(|s: &str| s.parse().unwrap()),
        )
        .map(|(path, score): (&str, Score)| Expr::PasswordScore(path.into(), score))
        .boxed();
    let password_luds = just("password_luds")
        .padded()
        .ignore_then(string.clone())
        .then(
            text::digits(10)
                .to_slice()
                .map(|s: &str| s.parse().unwrap()),
        )
        .then(
            text::digits(10)
                .to_slice()
                .map(|s: &str| s.parse().unwrap()),
        )
        .then(
            text::digits(10)
                .to_slice()
                .map(|s: &str| s.parse().unwrap()),
        )
        .then(
            text::digits(10)
                .to_slice()
                .map(|s: &str| s.parse().unwrap()),
        )
        .then(
            text::digits(10)
                .to_slice()
                .map(|s: &str| s.parse().unwrap()),
        )
        .map(|(((path, l), u), d, s, n): (&str, u8, u8, u8, u8, u8)| {
            Expr::PasswordLUDS(path.into(), l, u, d, s, n)
        })
        .boxed();

    let cmd = text::ascii::ident().map(|ident: &str| match ident {
        "save" => Expr::Save,
        "quit" => Expr::Quit,
        "load" => Expr::Load,
        _ => todo!(),
    });

    choice((
        set, setm, defnode, defvar, remove, clear, clearm, touch, cmd,
    ))
    .map_with(|expr, e| (expr, e.span()))
    .padded_by(comment.repeated())
    .padded()
    .repeated()
    .collect()
}

fn failure(
    msg: String,
    label: (String, SimpleSpan),
    extra_labels: impl IntoIterator<Item = (String, SimpleSpan)>,
    src: &str,
) -> ! {
    let fname = "example";
    Report::build(ReportKind::Error, fname, label.1.start)
        .with_message(&msg)
        .with_label(
            Label::new((fname, label.1.into_range()))
                .with_message(label.0)
                .with_color(Color::Red),
        )
        .with_labels(extra_labels.into_iter().map(|label2| {
            Label::new((fname, label2.1.into_range()))
                .with_message(label2.0)
                .with_color(Color::Yellow)
        }))
        .finish()
        .print(sources([(fname, src)]))
        .unwrap();
    std::process::exit(1)
}

fn parse_failure(err: &Rich<impl fmt::Display>, src: &str) -> ! {
    failure(
        err.reason().to_string(),
        (
            err.found()
                .map(|c| c.to_string())
                .unwrap_or_else(|| "end of input".to_string()),
            *err.span(),
        ),
        err.contexts()
            .map(|(l, s)| (format!("while parsing this {l}"), *s)),
        src,
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parser() {
        let src = r#"set "path" "value""#;
        assert_eq!(
            parser()
                .parse(src)
                .into_result()
                .unwrap_or_else(|errs| parse_failure(&errs[0], src))[0]
                .0,
            Expr::Set("path".into(), "value")
        );

        assert_eq!(parser().parse("save").unwrap()[0].0, Expr::Save);
        assert_eq!(parser().parse("save ").unwrap()[0].0, Expr::Save);
        assert_eq!(parser().parse("  save ").unwrap()[0].0, Expr::Save);
        assert_eq!(parser().parse("\n\nsave ").unwrap()[0].0, Expr::Save);
        assert_eq!(parser().parse("save\n ").unwrap()[0].0, Expr::Save);
        assert_eq!(parser().parse("save # stuff").unwrap()[0].0, Expr::Save);
    }
}
