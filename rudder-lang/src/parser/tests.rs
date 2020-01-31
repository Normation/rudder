// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use super::*;
use maplit::hashmap;
use nom::Err;

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
        Ok((x, y)) => Ok((x.fragment, y)),
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
        // PErrorKind::ExpectedReservedWord(i) => PErrorKind::ExpectedReservedWord(i),
        PErrorKind::ExpectedToken(i) => PErrorKind::ExpectedToken(i),
        PErrorKind::InvalidEnumExpression => PErrorKind::InvalidEnumExpression,
        PErrorKind::InvalidEscapeSequence => PErrorKind::InvalidEscapeSequence,
        PErrorKind::InvalidFormat => PErrorKind::InvalidFormat,
        PErrorKind::InvalidName(i) => PErrorKind::InvalidName(i.fragment),
        PErrorKind::InvalidVariableReference => PErrorKind::InvalidVariableReference,
        PErrorKind::UnsupportedMetadata(i) => PErrorKind::UnsupportedMetadata(i.fragment),
        PErrorKind::UnterminatedDelimiter(i) => PErrorKind::UnterminatedDelimiter(i.fragment),
        PErrorKind::Unparsed(i) => PErrorKind::Unparsed(i.fragment),
    };
    match err.context {
        Some(context) => (context.fragment, kind),
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
        Err(("21.5", PErrorKind::InvalidFormat))
    );
}

#[test]
fn test_pcomment() {
    assert_eq!(
        map_res(pcomment, "##hello Herman1\n"),
        Ok((
            "",
            PMetadata {
                key: "comment".into(),
                value: PValue::String("##".into(), "hello Herman1".into()),
            }
        ))
    );
    assert_eq!(
        map_res(pcomment, "##hello Herman2\nHola"),
        Ok((
            "Hola",
            PMetadata {
                key: "comment".into(),
                value: PValue::String("##".into(), "hello Herman2".into()),
            }
        ))
    );
    assert_eq!(
        map_res(pcomment, "##hello Herman3!"),
        Ok((
            "",
            PMetadata {
                key: "comment".into(),
                value: PValue::String("##".into(), "hello Herman3!".into()),
            }
        ))
    );
    assert_eq!(
        map_res(pcomment, "##hello1\nHerman\n"),
        Ok((
            "Herman\n",
            PMetadata {
                key: "comment".into(),
                value: PValue::String("##".into(), "hello1".into()),
            }
        ))
    );
    assert_eq!(
        map_res(pcomment, "##hello2\nHerman\n## 2nd line"),
        Ok((
            "Herman\n## 2nd line",
            PMetadata {
                key: "comment".into(),
                value: PValue::String("##".into(), "hello2".into()),
            }
        ))
    );
    assert_eq!(
        map_res(pcomment, "##hello\n##Herman\n"),
        Ok((
            "",
            PMetadata {
                key: "comment".into(),
                value: PValue::String("##".into(), "hello\nHerman".into()),
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
                name: "abc1".into(),
                items: vec!["a".into(), "b".into(), "c".into()]
            }
        ))
    );
    assert_eq!(
        map_res(penum, "global enum abc2 { a, b, c }"),
        Ok((
            "",
            PEnum {
                global: true,
                name: "abc2".into(),
                items: vec!["a".into(), "b".into(), "c".into()]
            }
        ))
    );
    assert_eq!(
        map_res(penum, "enum abc3 { a, b, }"),
        Ok((
            "",
            PEnum {
                global: false,
                name: "abc3".into(),
                items: vec!["a".into(), "b".into()]
            }
        ))
    );
    assert_eq!(
        map_res(penum, "enum .abc { a, b, }"),
        Err((".abc { a, b, }", PErrorKind::InvalidName("enum")))
    );
    assert_eq!(
        map_res(penum, "enum abc { a, b, "),
        Err(("", PErrorKind::UnterminatedDelimiter("{")))
    );
}

#[test]
fn test_penum_mapping() {
    assert_eq!(
        map_res(penum_mapping, "enum abc ~> def { a -> d, b -> e, * -> f}"),
        Ok((
            "",
            PEnumMapping {
                from: "abc".into(),
                to: "def".into(),
                mapping: vec![
                    ("a".into(), "d".into()),
                    ("b".into(), "e".into()),
                    ("*".into(), "f".into()),
                ]
            }
        ))
    );
    assert_eq!(
        map_res(
            penum_mapping,
            "enum outcome ~> okerr { kept->ok, repaired->ok, error->error }"
        ),
        Ok((
            "",
            PEnumMapping {
                from: "outcome".into(),
                to: "okerr".into(),
                mapping: vec![
                    ("kept".into(), "ok".into()),
                    ("repaired".into(), "ok".into()),
                    ("error".into(), "error".into()),
                ]
            }
        ))
    );
}

#[test]
fn test_penum_expression() {
    assert_eq!(
        map_res(penum_expression, "a=~b:c"),
        Ok((
            "",
            PEnumExpression::Compare(Some("a".into()), Some("b".into()), "c".into())
        ))
    );
    assert_eq!(
        map_res(penum_expression, "a=~bc"),
        Ok((
            "",
            PEnumExpression::Compare(Some("a".into()), None, "bc".into())
        ))
    );
    assert_eq!(
        map_res(penum_expression, "bc"),
        Ok(("", PEnumExpression::Compare(None, None, "bc".into())))
    );
    assert_eq!(
        map_res(penum_expression, "(a =~ b:hello)"),
        Ok((
            "",
            PEnumExpression::Compare(Some("a".into()), Some("b".into()), "hello".into())
        ))
    );
    assert_eq!(
        map_res(penum_expression, "( a !~ b : hello) "),
        Ok((
            "",
            PEnumExpression::Not(Box::new(PEnumExpression::Compare(
                Some("a".into()),
                Some("b".into()),
                "hello".into()
            )))
        ))
    );
    assert_eq!(
        map_res(penum_expression, "bc&&(a||b=~hello:g)"),
        Ok((
            "",
            PEnumExpression::And(
                Box::new(PEnumExpression::Compare(None, None, "bc".into())),
                Box::new(PEnumExpression::Or(
                    Box::new(PEnumExpression::Compare(None, None, "a".into())),
                    Box::new(PEnumExpression::Compare(
                        Some("b".into()),
                        Some("hello".into()),
                        "g".into()
                    ))
                )),
            )
        ))
    );
    assert_eq!(
        map_res(penum_expression, "! a =~ hello var = x"),
        Ok((
            "var = x",
            PEnumExpression::Not(Box::new(PEnumExpression::Compare(
                Some("a".into()),
                None,
                "hello".into()
            )))
        ))
    );
    assert_eq!(
        map_res(penum_expression, "a=~b:"),
        Err(("", PErrorKind::InvalidEnumExpression))
    );
    assert_eq!(
        map_res(penum_expression, "a=~"),
        Err(("", PErrorKind::InvalidEnumExpression))
    );
    assert_eq!(
        map_res(penum_expression, "a=~b||(c=~d"),
        Err(("", PErrorKind::UnterminatedDelimiter("(")))
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
        Err((r#"2hello\xHerman""#, PErrorKind::InvalidEscapeSequence))
    );
    assert_eq!(
        map_res(pescaped_string, r#""3hello"#),
        Err(("", PErrorKind::UnterminatedDelimiter("\"")))
    );
    assert_eq!(
        map_res(pescaped_string, r#""4hello\n\"Herman\""#),
        Err(("", PErrorKind::UnterminatedDelimiter("\"")))
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
        Err((r#"2hello"""#, PErrorKind::UnterminatedDelimiter("\"\"\"")))
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
        Err(("x", PErrorKind::InvalidVariableReference))
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
        Err((" ", PErrorKind::UnterminatedDelimiter("${")))
    );
    assert_eq!(
        map_res(pinterpolated_string, "hello${}"),
        Err(("}", PErrorKind::InvalidVariableReference))
    );
    assert_eq!(
        map_res(pinterpolated_string, "hello${"),
        Err(("", PErrorKind::InvalidVariableReference))
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
        Err(("hello\"\"", PErrorKind::UnterminatedDelimiter("\"\"\"")))
    );
    assert_eq!(
        map_res(pvalue, "\"hello\\x\""),
        Err(("hello\\x\"", PErrorKind::InvalidEscapeSequence))
    );
    assert_eq!(
        map_res(pvalue, "\"hello\\"),
        Err(("hello\\", PErrorKind::InvalidEscapeSequence))
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
        map_res(pvalue, r#"[ "hello", 12"#),
        Err(("[ \"hello\", 12", PErrorKind::UnterminatedDelimiter("[")))
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
        Err(("", PErrorKind::UnterminatedDelimiter("{")))
    );
}

#[test]
fn test_pmetadata() {
    assert_eq!(
        map_res(pmetadata, r#"@key="value""#),
        Ok((
            "",
            PMetadata {
                key: "key".into(),
                value: PValue::String("\"".into(), "value".to_string())
            }
        ))
    );
    assert_eq!(
        map_res(pmetadata, r#"@key = "value""#),
        Ok((
            "",
            PMetadata {
                key: "key".into(),
                value: PValue::String("\"".into(), "value".to_string())
            }
        ))
    );
    assert_eq!(
        map_res(pmetadata, r#"@key = {"key":"value"}"#),
        Ok((
            "",
            PMetadata {
                key: "key".into(),
                value: PValue::Struct(hashmap! {
                "key".into() => PValue::String("\"".into(), "value".into())})
            }
        ))
    );
    assert_eq!(
        map_res(pmetadata, "@key value"),
        Err(("value", PErrorKind::ExpectedToken("=")))
    );
}

#[test]
fn test_pmetadata_list() {
    assert_eq!(
        map_res(
            pmetadata_list,
            "@key=\"value\"\n##hello\n##Herman\n@key=123"
        ),
        Ok((
            "",
            vec![
                PMetadata {
                    key: "key".into(),
                    value: PValue::String("\"".into(), "value".to_string())
                },
                PMetadata {
                    key: "comment".into(),
                    value: PValue::String("##".into(), "hello\nHerman".to_string())
                },
                PMetadata {
                    key: "key".into(),
                    value: PValue::Number("123".into(), 123.0)
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
        Err(("", PErrorKind::ExpectedKeyword("type")))
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
        Err(("", PErrorKind::ExpectedKeyword("value")))
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
        map_res(pvariable_definition, r#"var="value""#),
        Ok((
            "",
            (
                "var".into(),
                PValue::String("\"".into(), "value".to_string())
            )
        ))
    );
    assert_eq!(
        map_res(pvariable_definition, r#"var = "value" "#),
        Ok((
            "",
            (
                "var".into(),
                PValue::String("\"".into(), "value".to_string())
            )
        ))
    );
    assert_eq!(
        map_res(pvariable_definition, "var=\"val\nue\"\n"),
        Ok((
            "",
            (
                "var".into(),
                PValue::String("\"".into(), "val\nue".to_string())
            )
        ))
    );
    assert_eq!(
        map_res(pvariable_definition, "var = :\n"),
        Err((":", PErrorKind::ExpectedKeyword("value")))
    );
}

#[test]
fn test_pstatement() {
    assert_eq!(
        map_res(pstatement, "resource().state()"),
        Ok((
            "",
            PStatement::StateDeclaration(PStateDeclaration {
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
        map_res(pstatement, "var=\"string\"\n"),
        Ok((
            "",
            PStatement::VariableDefinition(
                Vec::new(),
                "var".into(),
                PValue::String("\"".into(), "string".into())
            )
        ))
    );

    assert_eq!(
        map_res(pstatement, "var= a=~bc\n"),
        Ok((
            "",
            PStatement::VariableDefinition(
                Vec::new(),
                "var".into(),
                PValue::EnumExpression(map_res(penum_expression, "a=~bc").unwrap().1)
            )
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
                        map_res(penum_expression, "ubuntu").unwrap().1,
                        vec![map_res(pstatement, "f().g()").unwrap().1]
                    ),
                    (
                        map_res(penum_expression, "debian").unwrap().1,
                        vec![map_res(pstatement, "a().b()").unwrap().1]
                    ),
                ]
            )
        ))
    );
}

#[test]
fn test_pstate_def() {
    assert_eq!(
        map_res(pstate_def, "resource state configuration() {}"),
        Ok((
            "",
            (
                PStateDef {
                    metadata: Vec::new(),
                    name: "configuration".into(),
                    resource_name: "resource".into(),
                    parameters: vec![],
                    statements: vec![]
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

// ===== Functions used by other modules tests =====

fn test_t<'a, F, X>(f: F, input: &'a str) -> X
where
    F: Fn(PInput<'a>) -> PResult<X>,
    X: 'a,
{
    let (i, out) = f(PInput::new_extra(input, "")).expect(&format!("Syntax error in {}", input));
    if i.fragment.len() != 0 {
        panic!("Input not terminated in {}", input)
    }
    out
}
pub fn penum_t<'a>(input: &'a str) -> PEnum<'a> {
    test_t(penum, input)
}

pub fn penum_mapping_t(input: &str) -> PEnumMapping {
    test_t(penum_mapping, input)
}

pub fn penum_expression_t(input: &str) -> PEnumExpression {
    test_t(penum_expression, input)
}

pub fn pidentifier_t(input: &str) -> Token {
    test_t(pidentifier, input)
}
