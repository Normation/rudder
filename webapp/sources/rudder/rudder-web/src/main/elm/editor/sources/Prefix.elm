{-
  This is a copy of Prefix module from https://github.com/jxxcarlson/elm-markdown
  Once https://github.com/jxxcarlson/elm-markdown/pull/24 we can remove everything
-}
module Prefix exposing (drop, get, truncate)

import Parser.Advanced exposing (getChompedString, (|=), (|.), symbol, spaces, run, succeed, chompUntil, chompWhile, Token(..), oneOf, map)


type alias Parser a =
    Parser.Advanced.Parser String Problem a


type Problem
    = Expecting String


get : String -> String
get str =
    case run parsePrefix str of
        Ok str_ ->
            str_

        Err _ ->
            ""


drop : String -> String -> String
drop prefix str =
    String.dropLeft (String.length prefix) str


truncate : String -> String
truncate str_ =
    let
        str =
            String.trimLeft str_
    in
    drop (get str) str
        |> getGoodPrefix
        |> String.trimLeft


parsePrefix : Parser String
parsePrefix =
    oneOf [ heading, unorderedListItem, oListPrefix ]


oListPrefix : Parser String
oListPrefix =
    (getChompedString <|
        succeed identity
            |= chompUntil (Token "." (Expecting "expecting '.' to begin OListItem block"))
    )
        |> map (\x -> x ++ ". ")


headingBlock1 : Parser String
headingBlock1 =
    getChompedString <|
        (succeed identity
            |. spaces
            |. symbol (Token "#" (Expecting "Expecting '#' to begin heading block"))
            |= parseWhile (\c -> c == '#')
        )


headingBlock2 : Parser String
headingBlock2 =
    getChompedString <|
        (succeed identity
            |. spaces
            |. symbol (Token "##" (Expecting "Expecting '##' to begin level 2 heading block"))
            |= parseWhile (\c -> c == '#')
        )


headingBlock3 : Parser String
headingBlock3 =
    getChompedString <|
        (succeed identity
            |. spaces
            |. symbol (Token "###" (Expecting "Expecting '###' to begin level 3 heading block"))
            |= parseWhile (\c -> c == '#')
        )


heading : Parser String
heading =
    oneOf [ headingBlock1, headingBlock2, headingBlock3 ]


unorderedListItem : Parser String
unorderedListItem =
    getChompedString <|
        (succeed identity
            |. spaces
            |. symbol (Token "-" (Expecting "Expecting '-' to begin item"))
        )


parseGoodChars : Parser String
parseGoodChars =
    getChompedString <|
        succeed identity
            |= parseWhile (\c -> c /= '*' && c /= '$' && c /= '~' && c /= '!')


getGoodPrefix : String -> String
getGoodPrefix str =
    case run parseGoodChars str of
        Ok str_ ->
            str_

        Err _ ->
            "xyx@xyx!!"


parseWhile : (Char -> Bool) -> Parser String
parseWhile accepting =
    chompWhile accepting |> getChompedString
