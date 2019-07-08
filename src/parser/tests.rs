use super::*;

//    type Result<'src, O> = std::Result< (PInput<'src>,O), Err<PError<PInput<'src>>> >;

// Adapter to simplify running test
fn map_res<'src, F, O>(f: F, i: &'src str) -> std::result::Result<(&'src str, O), PError<&'src str>>
where
    F: Fn(PInput<'src>) -> Result<'src, O>,
{
    match f(pinput(i, "")) {
        Ok((x, y)) => Ok((x.fragment, y)),
        Err(Err::Failure(e)) => Err(map_err(e)),
        Err(Err::Error(e)) => Err(map_err(e)),
        Err(Err::Incomplete(_)) => panic!("Incomplete should never happen"),
    }
}

// Adapter to simplify error testing
fn map_err(err: PError<PInput>) -> PError<&str> {
    match err {
        PError::Nom(e) => PError::NomTest(format!("{:?}", e)),
        PError::NomTest(e) => PError::NomTest(e),
        PError::InvalidFormat => PError::InvalidFormat,
        PError::InvalidName(i1, i2) => PError::InvalidName(i1.fragment, i2.fragment),
        PError::UnexpectedToken(i1, i2) => PError::UnexpectedToken(i1, i2.fragment),
        PError::UnterminatedDelimiter(i1, i2) => {
            PError::UnterminatedDelimiter(i1.fragment, i2.fragment)
        }
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
        map_res(sp!(pair(pidentifier, pidentifier)), "hello world"),
        Ok(("", ("hello".into(), "world".into())))
    );
    assert_eq!(
        map_res(
            sp!(pair(pidentifier, pidentifier)),
            "hello \n#pouet\n world2"
        ),
        Ok(("", ("hello".into(), "world2".into())))
    );
    assert_eq!(
        map_res(
            sp!(pair(pidentifier, pidentifier)),
            "hello  world3 #comment\n"
        ),
        Ok(("", ("hello".into(), "world3".into())))
    );
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
        Err(PError::InvalidFormat)
    );
}

#[test]
fn test_pcomment() {
    assert_eq!(
        map_res(pcomment, "##hello Herman1\n"),
        Ok((
            "",
            PComment {
                lines: vec!["hello Herman1".into()]
            }
        ))
    );
    assert_eq!(
        map_res(pcomment, "##hello Herman2\nHola"),
        Ok((
            "Hola",
            PComment {
                lines: vec!["hello Herman2".into()]
            }
        ))
    );
    assert_eq!(
        map_res(pcomment, "##hello Herman3!"),
        Ok((
            "",
            PComment {
                lines: vec!["hello Herman3!".into()]
            }
        ))
    );
    assert_eq!(
        map_res(pcomment, "##hello1\nHerman\n"),
        Ok((
            "Herman\n",
            PComment {
                lines: vec!["hello1".into()]
            }
        ))
    );
    assert_eq!(
        map_res(pcomment, "##hello2\nHerman\n## 2nd line"),
        Ok((
            "Herman\n## 2nd line",
            PComment {
                lines: vec!["hello2".into()]
            }
        ))
    );
    assert_eq!(
        map_res(pcomment, "##hello\n##Herman\n"),
        Ok((
            "",
            PComment {
                lines: vec!["hello".into(), "Herman".into()]
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
fn test_penum() {
    assert_eq!(
        map_res(penum, "enum abc { a, b, c }"),
        Ok((
            "",
            PEnum {
                global: false,
                name: "abc".into(),
                items: vec!["a".into(), "b".into(), "c".into()]
            }
        ))
    );
    assert_eq!(
        map_res(penum, "global enum abc { a, b, c }"),
        Ok((
            "",
            PEnum {
                global: true,
                name: "abc".into(),
                items: vec!["a".into(), "b".into(), "c".into()]
            }
        ))
    );
    assert_eq!(
        map_res(penum, "enum abc { a, b, }"),
        Ok((
            "",
            PEnum {
                global: false,
                name: "abc".into(),
                items: vec!["a".into(), "b".into()]
            }
        ))
    );
    assert_eq!(
        map_res(penum, "enum .abc { a, b, }"),
        Err(PError::InvalidName("enum", ".abc { a, b, }"))
    );
    assert_eq!(
        map_res(penum, "enum abc a, b, }"),
        Err(PError::UnexpectedToken("{", "a, b, }"))
    );
    assert_eq!(
        map_res(penum, "enum abc { a, b, "),
        Err(PError::UnterminatedDelimiter("{", ""))
    );
}
//    assert_eq!(
//        map_res(penum_mapping,"enum abc ~> def { a -> d, b -> e, * -> f}"),
//        Ok((
//            "",
//            PEnumMapping {
//                from: "abc".into(),
//                to: "def".into(),
//                mapping: vec![
//                    ("a".into(), "d".into()),
//                    ("b".into(), "e".into()),
//                    ("*".into(), "f".into()),
//                ]
//            }
//        ))
//    );
//    assert_eq!(
//        map_res(penum_mapping,
//            "enum outcome ~> okerr { kept->ok, repaired->ok, error->error }"
//        ),
//        Ok((
//            "",
//            PEnumMapping {
//                from: "outcome".into(),
//                to: "okerr".into(),
//                mapping: vec![
//                    ("kept".into(), "ok".into()),
//                    ("repaired".into(), "ok".into()),
//                    ("error".into(), "error".into()),
//                ]
//            }
//        ))
//    );
//}
