// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use super::*;
use maplit::hashmap;
use nom::Err;
use pretty_assertions::assert_eq;

//    type Result<'src, O> = std::Result< (PInput<'src>,O), Err<PError<PInput<'src>>> >;

// Adapter to simplify running test (remove indirections and replace tokens with strings)
// - create input from string
// - convert output to string
// - convert errors to ErrorKing with string parameter
fn map_res<'src, F, O>(
    f: F,
    i: &'src str,
) -> std::result::Result<(&'src str, O), (&'src str, PErrorKind<&'src str>)>
where
    F: Fn(PInput<'src>) -> PResult<'src, O>,
{
    match f(PInput::new_extra(i, "")) {
        Ok((x, y)) => Ok((x.fragment(), y)),
        Err(Err::Failure(e)) => Err(map_err(e)),
        Err(Err::Error(e)) => Err(map_err(e)),
        Err(Err::Incomplete(_)) => panic!("Incomplete should never happen"),
    }
}

// Adapter to simplify error testing (convert all inputs to string)
fn map_err(err: PError<PInput>) -> (&str, PErrorKind<&str>) {
    let kind = match err.kind {
        PErrorKind::Nom(e) => PErrorKind::NomTest(format!("{:?}", e)),
        PErrorKind::NomTest(e) => PErrorKind::NomTest(e),
        PErrorKind::ExpectedKeyword(i) => PErrorKind::ExpectedKeyword(i),
        PErrorKind::ExpectedToken(i) => PErrorKind::ExpectedToken(i),
        PErrorKind::InvalidEnumExpression => PErrorKind::InvalidEnumExpression,
        PErrorKind::InvalidEscapeSequence => PErrorKind::InvalidEscapeSequence,
        PErrorKind::InvalidFormat => PErrorKind::InvalidFormat,
        PErrorKind::InvalidName(i) => PErrorKind::InvalidName(*i.fragment()),
        PErrorKind::InvalidVariableReference => PErrorKind::InvalidVariableReference,
        PErrorKind::NoMetadata => PErrorKind::NoMetadata,
        PErrorKind::TomlError(i, e) => PErrorKind::TomlError(*i.fragment(), e),
        PErrorKind::UnsupportedMetadata(i) => PErrorKind::UnsupportedMetadata(*i.fragment()),
        PErrorKind::UnterminatedDelimiter(i) => PErrorKind::UnterminatedDelimiter(*i.fragment()),
        PErrorKind::UnterminatedOrInvalid(i) => PErrorKind::UnterminatedOrInvalid(*i.fragment()),
        PErrorKind::Unparsed(i) => PErrorKind::Unparsed(*i.fragment()),
    };
    match err.context {
        Some(context) => (
            (context.extractor)(context.text, context.token).fragment(),
            kind,
        ),
        None => ("", kind),
    }
}

#[test]
fn test_spaces_and_comment() {
    assert_eq!(map_res(strip_spaces_and_comment, ""), Ok(("", ())));
    assert_eq!(map_res(strip_spaces_and_comment, "  \t\n"), Ok(("", ())));
    assert_eq!(
        map_res(strip_spaces_and_comment, "  \nhello "),
        Ok(("hello ", ()))
    );
    assert_eq!(
        map_res(
            strip_spaces_and_comment,
            "  \n#comment1 \n # comment2\n\n#comment3\n youpi"
        ),
        Ok(("youpi", ()))
    );
    assert_eq!(
        map_res(strip_spaces_and_comment, " #lastline\n#"),
        Ok(("", ()))
    );
}

#[test]
fn test_sp() {
    assert_eq!(
        map_res(pair(sp(pidentifier), pidentifier), "hello world"),
        Ok(("", ("hello".into(), "world".into())))
    );
    assert_eq!(
        map_res(
            pair(pidentifier, sp(pidentifier)),
            "hello \n#pouet\n world2"
        ),
        Ok(("", ("hello".into(), "world2".into())))
    );
    assert_eq!(
        map_res(
            pair(pidentifier, sp(pidentifier)),
            "hello  world3 #comment\n"
        ),
        Ok(("", ("hello".into(), "world3".into())))
    );
    assert_eq!(
        map_res(tuple((sp(pidentifier), pidentifier)), "hello world"),
        Ok(("", ("hello".into(), "world".into())))
    );
}

#[test]
fn test_wsequence() {
    assert_eq!(
        map_res(
            wsequence!( {
                    id1: pidentifier;
                    id2: pidentifier;
                    _x: pidentifier;
                } => (id1,id2)
            ),
            "hello  world end"
        ),
        Ok(("", ("hello".into(), "world".into())))
    );
    assert_eq!(
        map_res(
            wsequence!( {
                     id1: pidentifier;
                     id2: pidentifier;
                     _x: pidentifier;
                 } => (id1,id2)
            ),
            "hello world #end\nend"
        ),
        Ok(("", ("hello".into(), "world".into())))
    );
    assert!(map_res(
        wsequence!( {
                  id1: pidentifier;
                  id2: pidentifier;
                  _x: pidentifier;
              } => (id1,id2)
        ),
        "hello world"
    )
    .is_err());
}

#[test]
fn test_pheader() {
    assert_eq!(
        map_res(pheader, "@format=21\n"),
        Ok(("", PHeader { version: 21 }))
    );
    assert_eq!(
        map_res(pheader, "#!/bin/bash\n@format=1\n"),
        Ok(("", PHeader { version: 1 }))
    );
    assert_eq!(
        map_res(pheader, "@format=21.5\n"),
        Err(("@format=21.5", PErrorKind::InvalidFormat))
    );
}

#[test]
fn test_pcomment() {
    assert_eq!(
        map_res(pcomment, "##hello Herman1\n"),
        Ok((
            "",
            PMetadata {
                source: "##hello Herman1\n".into(),
                values: toml::de::from_str("comment=\"hello Herman1\"").unwrap(),
            }
        ))
    );
    assert_eq!(
        map_res(pcomment, "##hello Herman2\nHola"),
        Ok((
            "Hola",
            PMetadata {
                source: "##hello Herman2\n".into(),
                values: toml::de::from_str("comment=\"hello Herman2\"").unwrap(),
            }
        ))
    );
    assert_eq!(
        map_res(pcomment, "##hello Herman3!"),
        Ok((
            "",
            PMetadata {
                source: "##hello Herman3!".into(),
                values: toml::de::from_str("comment=\"hello Herman3!\"").unwrap(),
            }
        ))
    );
    assert!(map_res(pcomment, "hello\nHerman\n").is_err());
}

#[test]
fn test_pidentifier() {
    assert_eq!(map_res(pidentifier, "simple "), Ok((" ", "simple".into())));
    assert_eq!(map_res(pidentifier, "simple?"), Ok(("?", "simple".into())));
    assert_eq!(map_res(pidentifier, "simpl3 "), Ok((" ", "simpl3".into())));
    assert_eq!(map_res(pidentifier, "5imple "), Ok((" ", "5imple".into())));
    assert_eq!(map_res(pidentifier, "héllo "), Ok((" ", "héllo".into())));
    assert_eq!(
        map_res(pidentifier, "simple_word "),
        Ok((" ", "simple_word".into()))
    );
    assert!(map_res(pidentifier, "%imple ").is_err());
}

#[test]
fn test_pvariable_identifier() {
    assert_eq!(
        map_res(pvariable_identifier, "simple.value "),
        Ok((" ", "simple.value".into()))
    );
}

#[test]
fn test_penum() {
    assert_eq!(
        map_res(penum, "enum abc1 { a, b, c }"),
        Ok((
            "",
            PEnum {
                global: false,
                metadata: Vec::new(),
                name: "abc1".into(),
                items: vec![
                    (Vec::new(), "a".into()),
                    (Vec::new(), "b".into()),
                    (Vec::new(), "c".into())
                ]
            }
        ))
    );
    assert_eq!(
        map_res(penum, "global enum abc2 { a, b, c }"),
        Ok((
            "",
            PEnum {
                global: true,
                metadata: Vec::new(),
                name: "abc2".into(),
                items: vec![
                    (Vec::new(), "a".into()),
                    (Vec::new(), "b".into()),
                    (Vec::new(), "c".into())
                ]
            }
        ))
    );
    assert_eq!(
        map_res(penum, "enum abc3 { a, b, }"),
        Ok((
            "",
            PEnum {
                global: false,
                metadata: Vec::new(),
                name: "abc3".into(),
                items: vec![(Vec::new(), "a".into()), (Vec::new(), "b".into())]
            }
        ))
    );
    assert_eq!(
        map_res(
            penum,
            "@meta=\"hello\"\nenum abc3 { @metadata=\"value\"\n a, b, }"
        ),
        Ok((
            "",
            PEnum {
                global: false,
                metadata: vec![PMetadata {
                    source: "@meta=\"hello\"\n".into(),
                    values: toml::de::from_str("meta=\"hello\"").unwrap(),
                }],
                name: "abc3".into(),
                items: vec![
                    (
                        vec![PMetadata {
                            source: "@metadata=\"value\"\n".into(),
                            values: toml::de::from_str("metadata=\"value\"").unwrap(),
                        }],
                        "a".into()
                    ),
                    (Vec::new(), "b".into())
                ]
            }
        ))
    );
    assert_eq!(
        map_res(penum, "enum .abc { a, b, }"),
        Err(("enum .abc { a, b, }", PErrorKind::InvalidName("enum")))
    );
    assert_eq!(
        map_res(penum, "enum abc { a, b, "),
        Err(("{ a, b, ", PErrorKind::UnterminatedOrInvalid("{")))
    );
}

#[test]
fn test_psub_enum() {
    assert_eq!(
        map_res(psub_enum, "items in def { d, e, f}"),
        Ok((
            "",
            PSubEnum {
                name: "def".into(),
                enum_name: None,
                items: vec![
                    (Vec::new(), "d".into()),
                    (Vec::new(), "e".into()),
                    (Vec::new(), "f".into())
                ]
            }
        ))
    );
    assert_eq!(
        map_res(psub_enum, "items in T.def { d, e, f}"),
        Ok((
            "",
            PSubEnum {
                name: "def".into(),
                enum_name: Some("T".into()),
                items: vec![
                    (Vec::new(), "d".into()),
                    (Vec::new(), "e".into()),
                    (Vec::new(), "f".into())
                ]
            }
        ))
    );
}

#[test]
fn test_penum_alias() {
    assert_eq!(
        map_res(penum_alias, "enum alias a = b"),
        Ok((
            "",
            PEnumAlias {
                name: "a".into(),
                enum_name: None,
                item: "b".into(),
            }
        ))
    );
    assert_eq!(
        map_res(penum_alias, "enum alias a = T.b"),
        Ok((
            "",
            PEnumAlias {
                name: "a".into(),
                enum_name: Some("T".into()),
                item: "b".into(),
            }
        ))
    );
}

#[test]
fn test_penum_expression() {
    assert_eq!(
        map_res(penum_expression, "a=~bc"),
        Ok((
            "",
            PEnumExpression {
                source: "a=~bc".into(),
                expression: PEnumExpressionPart::Compare(Some("a".into()), None, "bc".into())
            }
        ))
    );
    assert_eq!(
        map_res(penum_expression, "a=~T.bc"),
        Ok((
            "",
            PEnumExpression {
                source: "a=~T.bc".into(),
                expression: PEnumExpressionPart::Compare(
                    Some("a".into()),
                    Some("T".into()),
                    "bc".into()
                )
            }
        ))
    );
    assert_eq!(
        map_res(penum_expression, "a=~bc..de"),
        Ok((
            "",
            PEnumExpression {
                source: "a=~bc..de".into(),
                expression: PEnumExpressionPart::RangeCompare(
                    Some("a".into()),
                    None,
                    Some("bc".into()),
                    Some("de".into()),
                    "..".into()
                )
            }
        ))
    );
    assert_eq!(
        map_res(penum_expression, "a=~T.bc..de"),
        Ok((
            "",
            PEnumExpression {
                source: "a=~T.bc..de".into(),
                expression: PEnumExpressionPart::RangeCompare(
                    Some("a".into()),
                    Some("T".into()),
                    Some("bc".into()),
                    Some("de".into()),
                    "..".into()
                )
            }
        ))
    );
    assert_eq!(
        map_res(penum_expression, "bc"),
        Ok((
            "",
            PEnumExpression {
                source: "bc".into(),
                expression: PEnumExpressionPart::Compare(None, None, "bc".into())
            }
        ))
    );
    assert_eq!(
        map_res(penum_expression, "bc..de"),
        Ok((
            "",
            PEnumExpression {
                source: "bc..de".into(),
                expression: PEnumExpressionPart::RangeCompare(
                    None,
                    None,
                    Some("bc".into()),
                    Some("de".into()),
                    "..".into()
                )
            }
        ))
    );
    assert_eq!(
        map_res(penum_expression, "..de"),
        Ok((
            "",
            PEnumExpression {
                source: "..de".into(),
                expression: PEnumExpressionPart::RangeCompare(
                    None,
                    None,
                    None,
                    Some("de".into()),
                    "..".into()
                )
            }
        ))
    );
    assert_eq!(
        map_res(penum_expression, "..T.de"),
        Ok((
            "",
            PEnumExpression {
                source: "..T.de".into(),
                expression: PEnumExpressionPart::RangeCompare(
                    None,
                    Some("T".into()),
                    None,
                    Some("de".into()),
                    "..".into()
                )
            }
        ))
    );
    assert_eq!(
        map_res(penum_expression, "bc.."),
        Ok((
            "",
            PEnumExpression {
                source: "bc..".into(),
                expression: PEnumExpressionPart::RangeCompare(
                    None,
                    None,
                    Some("bc".into()),
                    None,
                    "..".into()
                )
            }
        ))
    );
    assert_eq!(
        map_res(penum_expression, "T.bc.."),
        Ok((
            "",
            PEnumExpression {
                source: "T.bc..".into(),
                expression: PEnumExpressionPart::RangeCompare(
                    None,
                    Some("T".into()),
                    Some("bc".into()),
                    None,
                    "..".into()
                )
            }
        ))
    );
    assert_eq!(
        map_res(penum_expression, "(a =~ hello)"),
        Ok((
            "",
            PEnumExpression {
                source: "(a =~ hello)".into(),
                expression: PEnumExpressionPart::Compare(Some("a".into()), None, "hello".into())
            }
        ))
    );
    assert_eq!(
        map_res(penum_expression, "( a !~ hello) "),
        Ok((
            "",
            PEnumExpression {
                source: "( a !~ hello) ".into(),
                expression: PEnumExpressionPart::Not(Box::new(PEnumExpressionPart::Compare(
                    Some("a".into()),
                    None,
                    "hello".into()
                )))
            }
        ))
    );
    assert_eq!(
        map_res(penum_expression, "bc&(a|b=~g)"),
        Ok((
            "",
            PEnumExpression {
                source: "bc&(a|b=~g)".into(),
                expression: PEnumExpressionPart::And(
                    Box::new(PEnumExpressionPart::Compare(None, None, "bc".into())),
                    Box::new(PEnumExpressionPart::Or(
                        Box::new(PEnumExpressionPart::Compare(None, None, "a".into())),
                        Box::new(PEnumExpressionPart::Compare(
                            Some("b".into()),
                            None,
                            "g".into()
                        ))
                    )),
                )
            }
        ))
    );
    assert_eq!(
        map_res(penum_expression, "! a =~ hello var = x"),
        Ok((
            "var = x",
            PEnumExpression {
                source: "! a =~ hello ".into(),
                expression: PEnumExpressionPart::Not(Box::new(PEnumExpressionPart::Compare(
                    Some("a".into()),
                    None,
                    "hello".into()
                )))
            }
        ))
    );
    assert_eq!(
        map_res(penum_expression, "a=~"),
        Err(("a=~", PErrorKind::InvalidEnumExpression))
    );
    assert_eq!(
        map_res(penum_expression, "a=~b|(c=~d"),
        Err(("(c=~d", PErrorKind::UnterminatedOrInvalid("(")))
    );
}

#[test]
fn test_pescaped_strings() {
    assert_eq!(
        map_res(pescaped_string, "\"\""),
        Ok(("", ("\"".into(), "".to_string())))
    );
    assert_eq!(
        map_res(pescaped_string, "\"0hello\nHerman\""),
        Ok(("", ("\"".into(), "0hello\nHerman".to_string())))
    );
    assert_eq!(
        map_res(pescaped_string, r#""1hello\n\"Herman\"""#),
        Ok(("", ("\"".into(), "1hello\n\"Herman\"".to_string())))
    );
    assert_eq!(
        map_res(pescaped_string, r#""2hello\xHerman""#),
        Err((r#""2hello\xHerman""#, PErrorKind::InvalidEscapeSequence))
    );
    assert_eq!(
        map_res(pescaped_string, r#""3hello"#),
        Err(("\"3hello", PErrorKind::UnterminatedDelimiter("\"")))
    );
    assert_eq!(
        map_res(pescaped_string, r#""4hello\n\"Herman\""#),
        Err((
            r#""4hello\n\"Herman\""#,
            PErrorKind::UnterminatedDelimiter("\"")
        ))
    );
}

#[test]
fn test_punescaped_strings() {
    assert_eq!(
        map_res(punescaped_string, "\"\"\"\"\"\""),
        Ok(("", ("\"\"\"".into(), "".to_string())))
    );
    assert_eq!(
        map_res(punescaped_string, "\"\"\"0hello\nHerman\"\"\""),
        Ok(("", ("\"\"\"".into(), "0hello\nHerman".to_string())))
    );
    assert_eq!(
        map_res(punescaped_string, r#""""1hello\n\"Herman\""""#),
        Ok(("", ("\"\"\"".into(), r#"1hello\n\"Herman\"#.to_string())))
    );
    assert_eq!(
        map_res(punescaped_string, r#""""2hello"""#),
        Err((
            r#""""2hello"""#,
            PErrorKind::UnterminatedDelimiter("\"\"\"")
        ))
    );
}

#[test]
fn test_pinterpolated_string() {
    assert_eq!(
        map_res(pinterpolated_string, ""),
        Ok(("", vec![PInterpolatedElement::Static("".into())]))
    );
    assert_eq!(
        map_res(pinterpolated_string, "hello herman"),
        Ok((
            "",
            vec![PInterpolatedElement::Static("hello herman".into())]
        ))
    );
    assert_eq!(
        map_res(pinterpolated_string, "hello herman 10$$"),
        Ok((
            "",
            (vec![
                PInterpolatedElement::Static("hello herman 10".into()),
                PInterpolatedElement::Static("$".into()),
            ])
        ))
    );
    assert_eq!(
        map_res(pinterpolated_string, "hello herman 10$x"),
        Err(("$x", PErrorKind::InvalidVariableReference))
    );
    assert_eq!(
        map_res(pinterpolated_string, "hello herman ${variable1} ${var2}"),
        Ok((
            "",
            (vec![
                PInterpolatedElement::Static("hello herman ".into()),
                PInterpolatedElement::Variable("variable1".into()),
                PInterpolatedElement::Static(" ".into()),
                PInterpolatedElement::Variable("var2".into()),
            ])
        ))
    );
    assert_eq!(
        map_res(pinterpolated_string, "hello${pouet "),
        Err(("${pouet ", PErrorKind::UnterminatedDelimiter("${")))
    );
    assert_eq!(
        map_res(pinterpolated_string, "hello${}"),
        Err(("${}", PErrorKind::InvalidVariableReference))
    );
    assert_eq!(
        map_res(pinterpolated_string, "hello${"),
        Err(("${", PErrorKind::InvalidVariableReference))
    );
}

#[test]
fn test_pvalue() {
    assert_eq!(
        map_res(pvalue, "\"\"\"This is a string\"\"\""),
        Ok((
            "",
            PValue::String("\"\"\"".into(), "This is a string".to_string())
        ))
    );
    assert_eq!(
        map_res(pvalue, "\"This is a string bis\""),
        Ok((
            "",
            PValue::String("\"".into(), "This is a string bis".to_string())
        ))
    );
    assert_eq!(
        map_res(pvalue, "\"\"\"hello\"\""),
        Err((
            "\"\"\"hello\"\"",
            PErrorKind::UnterminatedDelimiter("\"\"\"")
        ))
    );
    assert_eq!(
        map_res(pvalue, "\"hello1\\x\""),
        Err(("\"hello1\\x\"", PErrorKind::InvalidEscapeSequence))
    );
    assert_eq!(
        map_res(pvalue, "\"hello2\\"),
        Err(("\"hello2\\", PErrorKind::InvalidEscapeSequence))
    );
    assert_eq!(
        map_res(pvalue, "12.5"),
        Ok(("", PValue::Number("12.5".into(), 12.5)))
    );
    assert_eq!(
        map_res(pvalue, r#"[ "hello", 12 ]"#),
        Ok((
            "",
            PValue::List(vec![
                PValue::String("\"".into(), "hello".into()),
                PValue::Number("12".into(), 12.0),
            ])
        ))
    );
    assert_eq!(
        map_res(pvalue, r#"[ "hello", 13"#),
        Err(("[ \"hello\", 13", PErrorKind::UnterminatedOrInvalid("[")))
    );
    assert_eq!(
        map_res(pvalue, r#"{"key":"value"}"#),
        Ok((
            "",
            PValue::Struct(hashmap! {
                "key".into() => PValue::String("\"".into(), "value".into()),
            })
        ))
    );
    assert_eq!(
        map_res(
            pvalue,
            r#"{ "key": "value", "number": 12, "list": [ 12 ] }"#
        ),
        Ok((
            "",
            PValue::Struct(hashmap! {
                "key".into() => PValue::String("\"".into(), "value".into()),
                "number".into() => PValue::Number("12".into(), 12.0),
                "list".into() => PValue::List(vec![PValue::Number("12".into(), 12.0)]),
            })
        ))
    );
    assert_eq!(
        map_res(pvalue, r#"{"key":"value""#),
        Err((r#"{"key":"value""#, PErrorKind::UnterminatedOrInvalid("{")))
    );
}

#[test]
fn test_pmetadata() {
    // Test with internal serde_toml structure to make sure we agree on this
    let test1 = "@key=\"value\"\n";
    let mut res = toml::map::Map::new();
    res.insert("key".into(), TomlValue::String("value".into()));
    assert_eq!(
        map_res(pmetadata, test1),
        Ok((
            "",
            PMetadata {
                source: test1.into(),
                values: TomlValue::Table(res)
            }
        ))
    );
    // use serde_toml for other tests since it should already be tested
    let test2 = "@key1 = {\"key2\"=\"value\"}\n@key3 = 1234\n";
    assert_eq!(
        map_res(pmetadata, test2),
        Ok((
            "",
            PMetadata {
                source: test2.into(),
                values: toml::de::from_str("key1 = {\"key2\"=\"value\"}\nkey3 = 1234\n").unwrap()
            }
        ))
    );
    let test3 = "@key1 = {\"key2\"=\"value\"}\n@key3 = 1234\n";
    assert_eq!(
        map_res(pmetadata, &("  ".to_owned() + test3)),
        Ok((
            "",
            PMetadata {
                source: test3.into(),
                values: toml::de::from_str("key1 = {\"key2\"=\"value\"}\nkey3 = 1234\n").unwrap()
            }
        ))
    );
    assert!(pmetadata(PInput::new_extra("@key value\n", "")).is_err());
    assert!(pmetadata(PInput::new_extra("\n", "")).is_err());
}

#[test]
fn test_pmetadata_list() {
    assert_eq!(
        map_res(
            pmetadata_list,
            "@key=\"value\"\n##hello\n##Herman\n@key=123\n"
        ),
        Ok((
            "",
            vec![
                PMetadata {
                    source: "@key=\"value\"\n".into(),
                    values: toml::de::from_str("key=\"value\"").unwrap()
                },
                PMetadata {
                    source: "##hello\n##Herman\n".into(),
                    values: toml::de::from_str("comment=\"hello\\nHerman\"").unwrap(),
                },
                PMetadata {
                    source: "@key=123\n".into(),
                    values: toml::de::from_str("key=123").unwrap()
                },
            ]
        ))
    );
}

#[test]
fn test_pparameter() {
    assert_eq!(
        map_res(pparameter, "hello "),
        Ok((
            "",
            (
                PParameter {
                    name: "hello".into(),
                    ptype: None,
                },
                None
            )
        ))
    );
    assert_eq!(
        map_res(pparameter, "hello:string "),
        Ok((
            "",
            (
                PParameter {
                    name: "hello".into(),
                    ptype: Some(PType::String)
                },
                None
            )
        ))
    );
    assert_eq!(
        map_res(pparameter, "hello:"),
        Err((":", PErrorKind::ExpectedKeyword("type")))
    );
    assert_eq!(
        map_res(pparameter, "hello : string "),
        Ok((
            "",
            (
                PParameter {
                    name: "hello".into(),
                    ptype: Some(PType::String),
                },
                None
            )
        ))
    );
    assert_eq!(
        map_res(pparameter, r#"hello : string="default""#),
        Ok((
            "",
            (
                PParameter {
                    name: "hello".into(),
                    ptype: Some(PType::String),
                },
                Some(PValue::String("\"".into(), "default".to_string()))
            )
        ))
    );
    assert_eq!(
        map_res(pparameter, "hello="),
        Err(("=", PErrorKind::ExpectedKeyword("value")))
    );
}

#[test]
fn test_presource_def() {
    assert_eq!(
        map_res(presource_def, "resource hello()"),
        Ok((
            "",
            (
                PResourceDef {
                    metadata: Vec::new(),
                    name: "hello".into(),
                    parameters: vec![],
                },
                vec![],
                None
            )
        ))
    );
    assert_eq!(
        map_res(presource_def, "resource  hello2 ( )"),
        Ok((
            "",
            (
                PResourceDef {
                    metadata: Vec::new(),
                    name: "hello2".into(),
                    parameters: vec![],
                },
                vec![],
                None
            )
        ))
    );
    assert_eq!(
        map_res(presource_def, "resource  hello2 ( ): hello3"),
        Ok((
            "",
            (
                PResourceDef {
                    metadata: Vec::new(),
                    name: "hello2".into(),
                    parameters: vec![],
                },
                vec![],
                Some("hello3".into())
            )
        ))
    );
    assert_eq!(
        map_res(presource_def, "resource hello (p1: string, p2)"),
        Ok((
            "",
            (
                PResourceDef {
                    metadata: Vec::new(),
                    name: "hello".into(),
                    parameters: vec![
                        PParameter {
                            name: "p1".into(),
                            ptype: Some(PType::String),
                        },
                        PParameter {
                            name: "p2".into(),
                            ptype: None,
                        }
                    ],
                },
                vec![None, None],
                None
            )
        ))
    );
}

#[test]
fn test_presource_ref() {
    assert_eq!(
        map_res(presource_ref, "hello()"),
        Ok(("", ("hello".into(), vec![])))
    );
    assert_eq!(
        map_res(presource_ref, "hello3 "),
        Ok(("", ("hello3".into(), vec![])))
    );
    assert_eq!(
        map_res(presource_ref, "hello ( 12, 14 )"),
        Ok((
            "",
            (
                "hello".into(),
                vec![
                    PValue::Number("12".into(), 12.0),
                    PValue::Number("14".into(), 14.0),
                ]
            )
        ))
    );
    assert_eq!(
        map_res(presource_ref, "hello ( \"p1\", \"p2\" )"),
        Ok((
            "",
            (
                "hello".into(),
                vec![
                    PValue::String("\"".into(), "p1".to_string()),
                    PValue::String("\"".into(), "p2".to_string())
                ]
            )
        ))
    );
    assert_eq!(
        map_res(presource_ref, r#"hello ( "12", "14", )"#),
        Ok((
            "",
            (
                "hello".into(),
                vec![
                    PValue::String("\"".into(), "12".to_string()),
                    PValue::String("\"".into(), "14".to_string())
                ]
            )
        ))
    );
    assert_eq!(
        map_res(presource_ref, "hello2 ( )"),
        Ok(("", ("hello2".into(), vec![])))
    );
}

#[test]
fn test_variable_definition() {
    assert_eq!(
        map_res(pvariable_definition, r#"let my_var="value""#),
        Ok((
            "",
            PVariableDef {
                metadata: Vec::new(),
                name: "my_var".into(),
                value: PValue::String("\"".into(), "value".to_string())
            }
        ))
    );
    assert_eq!(
        map_res(pvariable_definition, r#"let my_var = "value" "#),
        Ok((
            "",
            PVariableDef {
                metadata: Vec::new(),
                name: "my_var".into(),
                value: PValue::String("\"".into(), "value".to_string())
            }
        ))
    );
    assert_eq!(
        map_res(pvariable_definition, "let my_var=\"val\nue\"\n"),
        Ok((
            "",
            PVariableDef {
                metadata: Vec::new(),
                name: "my_var".into(),
                value: PValue::String("\"".into(), "val\nue".to_string())
            }
        ))
    );
    assert_eq!(
        map_res(pvariable_definition, "let my_var = :\n"),
        Err(("let my_var = :", PErrorKind::ExpectedKeyword("value")))
    );
    assert_eq!(
        map_res(pvariable_definition, "my_var = :\n"),
        Err(("my_var = :", PErrorKind::ExpectedKeyword("let")))
    );
}

#[test]
fn test_variable_declaration() {
    assert_eq!(
        map_res(pvariable_declaration, "let my_var1"),
        Ok((
            "",
            PVariableDecl {
                metadata: Vec::new(),
                name: "my_var1".into(),
                sub_elts: Vec::new(),
                type_: None
            }
        ))
    );
    assert_eq!(
        map_res(pvariable_declaration, "let my_var2: string"),
        Ok((
            "",
            PVariableDecl {
                metadata: Vec::new(),
                name: "my_var2".into(),
                sub_elts: Vec::new(),
                type_: Some(PType::String)
            }
        ))
    );
    assert_eq!(
        map_res(pvariable_declaration, "let my_var3.sub.element.x"),
        Ok((
            "",
            PVariableDecl {
                metadata: Vec::new(),
                name: "my_var3".into(),
                sub_elts: vec!["sub".into(), "element".into(), "x".into()],
                type_: None
            }
        ))
    );
    assert_eq!(
        map_res(pvariable_declaration, "let my_var4.sub.element.x: string"),
        Ok((
            "",
            PVariableDecl {
                metadata: Vec::new(),
                name: "my_var4".into(),
                sub_elts: vec!["sub".into(), "element".into(), "x".into()],
                type_: Some(PType::String)
            }
        ))
    );
}

#[test]
fn test_pstatement() {
    assert_eq!(
        map_res(pstatement, "resource().state()"),
        Ok((
            "",
            PStatement::StateDeclaration(PStateDeclaration {
                source: "resource().state()".into(),
                metadata: Vec::new(),
                mode: PCallMode::Enforce,
                resource: "resource".into(),
                resource_params: vec![],
                state: "state".into(),
                state_params: Vec::new(),
                outcome: None,
            })
        ))
    );
    assert_eq!(
        map_res(
            pstatement,
            r#"resource().state( "p1", "p2") as resource_state"#
        ),
        Ok((
            "",
            PStatement::StateDeclaration(PStateDeclaration {
                source: r#"resource().state( "p1", "p2") as resource_state"#.into(),
                metadata: Vec::new(),
                mode: PCallMode::Enforce,
                resource: "resource".into(),
                resource_params: vec![],
                state: "state".into(),
                state_params: vec![
                    PValue::String("\"".into(), "p1".to_string()),
                    PValue::String("\"".into(), "p2".to_string())
                ],
                outcome: Some("resource_state".into()),
            })
        ))
    );
    assert_eq!(
        map_res(pstatement, "let my_var=\"string\"\n"),
        Ok((
            "",
            PStatement::VariableDefinition(PVariableDef {
                metadata: Vec::new(),
                name: "my_var".into(),
                value: PValue::String("\"".into(), "string".into())
            })
        ))
    );

    assert_eq!(
        map_res(pstatement, "let my_var= a=~bc\n"),
        Ok((
            "",
            PStatement::VariableDefinition(PVariableDef {
                metadata: Vec::new(),
                name: "my_var".into(),
                value: PValue::EnumExpression(map_res(penum_expression, "a=~bc\n").unwrap().1)
            })
        ))
    );
    let st = "case { ubuntu => f().g(), debian => a().b() }";
    assert_eq!(
        map_res(pstatement, st),
        Ok((
            "",
            PStatement::Case(
                "case".into(),
                vec![
                    (
                        map_res(penum_expression, "ubuntu ").unwrap().1,
                        vec![map_res(pstatement, "f().g()").unwrap().1]
                    ),
                    (
                        map_res(penum_expression, "debian ").unwrap().1,
                        vec![map_res(pstatement, "a().b() ").unwrap().1]
                    ),
                ]
            )
        ))
    );
}

#[test]
fn test_pstate_def() {
    assert_eq!(
        map_res(pstate_def, "resource state configuration() {noop}"),
        Ok((
            "",
            (
                PStateDef {
                    metadata: Vec::new(),
                    name: "configuration".into(),
                    resource_name: "resource".into(),
                    parameters: vec![],
                    statements: vec![PStatement::Noop]
                },
                vec![]
            )
        ))
    );
}

#[test]
fn test_palias_def() {
    assert_eq!(
        map_res(
            palias_def,
            "alias File(path).keyvalue(key,value) = FileContentKey(path).xml_keyvalue(key,value)"
        ),
        Ok((
            "",
            PAliasDef {
                metadata: Vec::new(),
                resource_alias: "File".into(),
                resource_alias_parameters: vec!["path".into()],
                state_alias: "keyvalue".into(),
                state_alias_parameters: vec!["key".into(), "value".into()],
                resource: "FileContentKey".into(),
                resource_parameters: vec!["path".into()],
                state: "xml_keyvalue".into(),
                state_parameters: vec!["key".into(), "value".into()],
            }
        ))
    );
}

#[test]
fn test_pdeclaration() {
    assert_eq!(
        map_res(pdeclaration,"ntp state configuration ()\n{\n  file(\"/tmp\").permissions(\"root\", \"root\", \"g+w\")\n}\n"),
        Ok(("",
            PDeclaration::State((PStateDef {
                metadata: Vec::new(),
                name: "configuration".into(),
                resource_name: "ntp".into(),
                parameters: vec![],
                statements: vec![
                    PStatement::StateDeclaration(PStateDeclaration{
                        source: "file(\"/tmp\").permissions(\"root\", \"root\", \"g+w\")\n".into(),
                        metadata: Vec::new(),
                        mode: PCallMode::Enforce,
                        resource: "file".into(),
                        resource_params: vec![PValue::String("\"".into(), "/tmp".to_string())],
                        state: "permissions".into(),
                        state_params: vec![PValue::String("\"".into(), "root".to_string()), PValue::String("\"".into(), "root".to_string()), PValue::String("\"".into(), "g+w".to_string())],
                        outcome: None,
                    })
                ]
            },vec![]))
        )));
}

// ===== Functions used by other test modules =====

// run a parser and expect it to work
fn test_t<'a, F, X>(f: F, input: &'a str) -> X
where
    F: Fn(PInput<'a>) -> PResult<X>,
    X: 'a,
{
    let (i, out) =
        f(PInput::new_extra(input, "")).unwrap_or_else(|_| panic!("Syntax error in {}", input));
    if !i.fragment().is_empty() {
        panic!("Input not terminated in {}", input)
    }
    out
}
pub fn penum_t<'a>(input: &'a str) -> PEnum<'a> {
    test_t(penum, input)
}

pub fn psub_enum_t(input: &str) -> PSubEnum {
    test_t(psub_enum, input)
}

pub fn penum_expression_t(input: &str) -> PEnumExpression {
    test_t(penum_expression, input)
}

pub fn pidentifier_t(input: &str) -> Token {
    test_t(pidentifier, input)
}

pub fn pvariable_declaration_t(input: &str) -> PVariableDecl {
    test_t(pvariable_declaration, input)
}
