use super::*;

//    type Result<'src, O> = std::Result< (PInput<'src>,O), Err<PError<PInput<'src>>> >;

// Adapter to simplify running test
fn mapres<'src, F, O>(
    f: F,
    i: &'src str,
) -> std::result::Result<(&'src str, O), PError<PInput<'src>>>
where
    F: Fn(PInput<'src>) -> Result<'src, O>,
{
    match f(pinput(i, "")) {
        Ok((x, y)) => Ok((x.fragment, y)),
        Err(Err::Failure(e)) => Err(e),
        Err(Err::Error(e)) => Err(e),
        Err(Err::Incomplete(_)) => panic!("Incomplete should never happen"),
    }
}

#[test]
fn test_spaces_and_comment() {
    assert_eq!(mapres(strip_spaces_and_comment, ""), Ok(("", ())));
    assert_eq!(mapres(strip_spaces_and_comment, "  \t\n"), Ok(("", ())));
    assert_eq!(
        mapres(strip_spaces_and_comment, "  \nhello "),
        Ok(("hello ", ()))
    );
    assert_eq!(
        mapres(
            strip_spaces_and_comment,
            "  \n#comment1 \n # comment2\n\n#comment3\n youpi"
        ),
        Ok(("youpi", ()))
    );
    assert_eq!(
        mapres(
            strip_spaces_and_comment,
            " #lastline\n#"
        ),
        Ok(("", ()))
    );
}

#[test]
fn test_sp() {
    assert_eq!(
        mapres(sp!(pair(pidentifier,pidentifier)), "hello world"),
        Ok(("", ("hello".into(),"world".into())))
    );
    assert_eq!(
        mapres(sp!(pair(pidentifier,pidentifier)), "hello \n#pouet\n world2"),
        Ok(("", ("hello".into(),"world2".into())))
    );
    assert_eq!(
        mapres(sp!(pair(pidentifier,pidentifier)), "hello  world3 #comment\n"),
        Ok(("", ("hello".into(),"world3".into())))
    );
}

#[test]
fn test_pheader() {
    assert_eq!(
        mapres(pheader, "@format=21\n"),
        Ok(("", PHeader { version: 21 }))
    );
    assert_eq!(
        mapres(pheader, "#!/bin/bash\n@format=1\n"),
        Ok(("", PHeader { version: 1 }))
    );
    assert_eq!(mapres(pheader, "@format=21.5\n"), Err(PError::InvalidFormat));
}

#[test]
fn test_pcomment() {
    assert_eq!(
        mapres(pcomment,"##hello Herman1\n"),
        Ok(("", PComment { lines: vec!["hello Herman1".into()] } ))
    );
    assert_eq!(
        mapres(pcomment,"##hello Herman2\nHola"),
        Ok(("Hola", PComment { lines: vec!["hello Herman2".into()] } ))
    );
    assert_eq!(
        mapres(pcomment,"##hello Herman3!"),
        Ok(("", PComment { lines: vec!["hello Herman3!".into()] } ))
    );
    assert_eq!(
        mapres(pcomment,"##hello1\nHerman\n"),
        Ok(("Herman\n", PComment { lines: vec!["hello1".into()] } ))
    );
    assert_eq!(
        mapres(pcomment,"##hello2\nHerman\n## 2nd line"),
        Ok(("Herman\n## 2nd line", PComment { lines: vec!["hello2".into()] } ))
    );
    assert_eq!(
        mapres(pcomment,"##hello\n##Herman\n"),
        Ok(("", PComment { lines: vec!["hello".into(),"Herman".into()] } ))
    );
    assert!(
        mapres(pcomment,"hello\nHerman\n").is_err()
    );
}

#[test]
fn test_pidentifier() {
        assert_eq!(
            mapres(pidentifier,"simple "),
            Ok((" ", "simple".into()))
        );
        assert_eq!(
            mapres(pidentifier,"simple?"),
            Ok(("?", "simple".into()))
        );
        assert_eq!(
            mapres(pidentifier,"simpl3 "),
            Ok((" ", "simpl3".into()))
        );
        assert_eq!(
            mapres(pidentifier,"5imple "),
            Ok((" ", "5imple".into()))
        );
        assert_eq!(
            mapres(pidentifier,"héllo "),
            Ok((" ", "héllo".into()))
        );
        assert_eq!(
            mapres(pidentifier,"simple_word "),
            Ok((" ", "simple_word".into()))
        );
        assert!(mapres(pidentifier,"%imple ").is_err());
}


