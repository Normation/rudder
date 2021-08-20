{-
  This is a copy of MDInline module from https://github.com/jxxcarlson/elm-markdown
  Once https://github.com/jxxcarlson/elm-markdown/pull/24 we can remove everything
-}
module MDInline exposing
    ( MDInline(..), parse, string, stringContent
    , Problem(..), extension, ordinaryTextExtended, ordinaryTextExtendedMath, ordinaryTextStandard, parseWhile, string2
    )

{-| Module MDInline provides one type and two functions. The
type is MDInline, which is the type of inline Markdown elements
such as italic and bold. The `parse` function parses a string
into an MMinline value, a custom type with parts such as
Paragraph, Line, Italic, Bold, Ordered and Unnumbered lists, etc.

The MDInline.parse function is used in the second of the two
parsing operations. A string is first parsed into a hierarchical
list -- a list of strings paired with an integer level.
The hierarchical list is converted to tree. The parser in This
module is mapped over the nodes of the tree to form a new tree.

The MDInline.string function is used to give a string representation
of the BlockMMTree values.

@docs MDInline, parse, string, stringContent

-}

import Markdown.Option exposing (MarkdownOption(..))
import Parser.Advanced exposing ((|.), (|=), DeadEnd, Step(..), Token(..), backtrackable, chompIf, chompUntil, chompWhile, getChompedString, loop, map, mapChompedString, oneOf, run, spaces, succeed, symbol)


type alias Parser a =
    Parser.Advanced.Parser String Problem a


type Problem
    = Expecting String


{-| The type for inline Markdown elements
-}
type MDInline
    = OrdinaryText String
    | ItalicText String
    | BoldText String
    | Code String
    | InlineMath String
    | StrikeThroughText String
    | BracketedText String
    | HtmlEntity String
    | HtmlEntities (List MDInline)
    | ExtensionInline String String
    | Link String String
    | Image String String
    | Line (List MDInline)
    | Paragraph (List MDInline)
    | Stanza String
    | Error (List MDInline)


{-| String content of MDInline value. Used in ElmWithId.searchAST
-}
stringContent : MDInline -> String
stringContent mmInline =
    case mmInline of
        OrdinaryText str ->
            str

        ItalicText str ->
            str

        BoldText str ->
            str

        Code str ->
            str

        InlineMath str ->
            str

        StrikeThroughText str ->
            str

        HtmlEntity str ->
            str

        HtmlEntities _ ->
            "HtmlEntities: unimplemented"

        BracketedText str ->
            str

        Link _ b ->
            b

        Image a _ ->
            a

        Line arg ->
            List.map string2 arg |> String.join " "

        Paragraph arg ->
            List.map string arg |> List.map indentLine |> String.join "\n"

        Stanza arg ->
            arg

        ExtensionInline op arg ->
            op ++ ": " ++ arg

        Error arg ->
            List.map string arg |> String.join " "


string2 : MDInline -> String
string2 mmInline =
    case mmInline of
        OrdinaryText str ->
            str

        ItalicText str ->
            str

        BoldText str ->
            str

        Code str ->
            str

        InlineMath str ->
            str

        StrikeThroughText str ->
            str

        HtmlEntity str ->
            str

        HtmlEntities _ ->
            "HtmlEntities: unimplemented"

        BracketedText str ->
            str

        Link a b ->
            a ++ " " ++ b

        Image a b ->
            a ++ " " ++ b

        Line arg ->
            List.map string2 arg |> String.join " "

        Paragraph arg ->
            List.map string2 arg |> String.join "\n"

        Stanza arg ->
            arg

        ExtensionInline op arg ->
            op ++ ": " ++ arg

        Error arg ->
            "Error [" ++ (List.map string arg |> String.join " ") ++ "]"


{-| String representation of an MDInline value
-}
string : MDInline -> String
string mmInline =
    case mmInline of
        OrdinaryText str ->
            "Text [" ++ str ++ "]"

        ItalicText str ->
            "Italic [" ++ str ++ "]"

        BoldText str ->
            "Bold [" ++ str ++ "]"

        Code str ->
            "Code [" ++ str ++ "]"

        InlineMath str ->
            "InlineMath [" ++ str ++ "]"

        StrikeThroughText str ->
            "StrikeThroughText [" ++ str ++ "]"

        HtmlEntity str ->
            "HtmlEntity [" ++ str ++ "]"

        HtmlEntities _ ->
            "HtmlEntity [" ++ "Unimplemented HtmlEntities" ++ "]"

        BracketedText str ->
            "Bracketed [" ++ str ++ "]"

        Link a b ->
            "Link [" ++ a ++ "](" ++ b ++ ")"

        Image a b ->
            "Image [" ++ a ++ "](" ++ b ++ ")"

        Line arg ->
            "Line [" ++ (List.map string arg |> String.join " ") ++ "]"

        Paragraph arg ->
            "Paragraph [" ++ (List.map string arg |> List.map indentLine |> String.join "\n") ++ "]"

        Stanza arg ->
            "Stanza [\n" ++ arg ++ "\n]"

        ExtensionInline op arg ->
            "ExtensionInline: " ++ op ++ "[" ++ arg ++ "]"

        Error arg ->
            "Ordinary [" ++ (List.map string arg |> String.join " ") ++ "]"


indentLine : String -> String
indentLine s =
    "  " ++ s


type alias PrefixedString =
    { prefix : String
    , text : String
    }


{-| MDInline parser

    > > parse ExtendedMath "@class[red stuff]"
      Paragraph [Line [ExtensionInline "class" ["red","stuff"]]]

-}
parse : MarkdownOption -> String -> MDInline
parse option str =
    let
        res =
            str
                |> String.split "\n"
                |> List.filter (\strElt -> not <| String.isEmpty strElt)
                |> wrap
                |> List.map (parseLine option)
                |> Paragraph
    in
    str
        |> String.split "\n"
        |> List.filter (\strElt -> not <| String.isEmpty strElt)
        |> wrap
        |> List.map (parseLine option)
        |> Paragraph


wrap : List String -> List String
wrap strList =
    List.foldl wrapper { currentString = "", lst = [] } strList
        |> (\acc -> acc.currentString :: acc.lst)
        |> List.reverse


type alias WrapAccumulator =
    { currentString : String
    , lst : List String
    }


wrapper : String -> WrapAccumulator -> WrapAccumulator
wrapper str acc =
    if acc.currentString == "" then
        { currentString = str, lst = [] }

    else if endsWithPunctuation acc.currentString then
        { currentString = str, lst = acc.currentString :: acc.lst }

    else
        { acc | currentString = acc.currentString ++ " " ++ str }


endsWithPunctuation : String -> Bool
endsWithPunctuation str =
    String.right 1 str == "."


parseLine : MarkdownOption -> String -> MDInline
parseLine option str =
    run (inlineList option) str
        |> resolveInlineResult


{-| This is the dispatcher for the inline element parsers
for the different flavors of Markdown.
-}
inline : MarkdownOption -> Parser MDInline
inline option =
    case option of
        Standard ->
            inlineStandard

        Extended ->
            inlineExtended

        ExtendedMath ->
            inlineExtendedMath



-- TODO: Make the extension parser work as intended


{-|

    > run extension "@class[red stuff]"
    --> Ok (ExtensionInline "class" ["red","stuff"])

-}
extension_ : Parser.Advanced.Parser String Problem MDInline
extension_ =
    succeed (\cmd args -> ExtensionInline cmd args)
        |. symbol (Token "@" (Expecting "Expecting '@' to begin extension element"))
        |= parseUntil "["
        |. symbol (Token "[" (Expecting "Expecting '[' to continue extension element"))
        |= parseWhile (\c -> c /= ']')
        |. symbol (Token "]" (Expecting "Expecting ']' to end extension element"))
        |. spaces


extension : Parser.Advanced.Parser String Problem MDInline
extension =
    oneOf [ backtrackable extension_, emailTail ]


emailTail : Parser.Advanced.Parser String Problem MDInline
emailTail =
    succeed (\s -> ExtensionInline "noOp" ("@" ++ s))
        |. symbol (Token "@" (Expecting "Expecting '@' to begin tail of email address"))
        |= parseWhile (\c -> c /= ' ')
        |. spaces


{-|

> run inline "$a^5 = 1$"
> Ok (InlineMath ("a^5 = 1"))

> run inline "_abc_"
> Ok (ItalicText "abc")

> run inline "hahaha"
> Ok (OrdinaryText "hahaha")

-}
inlineExtendedMath : Parser MDInline
inlineExtendedMath =
    oneOf [ extension, code, image, link, boldText, italicText, strikeThroughText, htmlEntityText, inlineMath, ordinaryTextExtendedMath ]


inlineExtended : Parser MDInline
inlineExtended =
    oneOf [ extension, code, image, link, boldText, italicText, strikeThroughText, htmlEntityText, ordinaryTextExtended ]


inlineStandard : Parser MDInline
inlineStandard =
    oneOf [ code, image, link, boldText, italicText, ordinaryTextStandard ]



-- THE GUTS --


{-|

> run (parseUntil ";;") "a b c;;"
> Ok ("a b c") : Result (List P.DeadEnd) String

-}
parseUntil : String -> Parser String
parseUntil end =
    chompUntil (Token end (Expecting <| "Expecting '" ++ end ++ "' in parseUntil")) |> getChompedString


parseWhile : (Char -> Bool) -> Parser String
parseWhile accepting =
    chompWhile accepting |> getChompedString



--
-- INLINE
--


{-| Characters that have a special meaning in standard markdown

omits the closing square bracket `]` because on its own it is a regular character.
It only gets special meaning when it closes a corresponding opening square bracket

NOTE: implementation changed to use 'case .. of' instead of '==' for performance reasons.

-}
isSpecialCharacter : Char -> Bool
isSpecialCharacter c =
    case c of
        '`' ->
            True

        '[' ->
            True

        '*' ->
            True

        '&' ->
            True

        '@' ->
            True

        '\n' ->
            True

        _ ->
            False


ordinaryTextParser : (Char -> Bool) -> Parser MDInline
ordinaryTextParser validStart =
    let
        -- a regular character must not be a ']' and must be a valid starting character
        isRegular c =
            not (c == ']') && validStart c
    in
    chompIf validStart (Expecting "expecting regular character to begin ordinary text line")
        |. chompWhile isRegular
        |> mapChompedString (\s _ -> OrdinaryText s)


{-|

> run ordinaryText "abc"
> Ok (OrdinaryText "abc")

-}
ordinaryTextExtendedMath : Parser MDInline
ordinaryTextExtendedMath =
    let
        validStart c =
            not (c == '~' || c == '$' || isSpecialCharacter c)
    in
    ordinaryTextParser validStart


ordinaryTextExtended : Parser MDInline
ordinaryTextExtended =
    let
        validStart c =
            not (c == '~' || isSpecialCharacter c)
    in
    ordinaryTextParser validStart


ordinaryTextStandard : Parser MDInline
ordinaryTextStandard =
    let
        validStart =
            not << isSpecialCharacter
    in
    ordinaryTextParser validStart


image : Parser MDInline
image =
    (succeed PrefixedString
        |. symbol (Token "![" (Expecting "Expecting '![' to begin image block"))
        |= parseWhile (\c -> c /= ']')
        |. symbol (Token "](" (Expecting "Expecting '](' in image block"))
        |= parseWhile (\c -> c /= ')')
        |. symbol (Token ")" (Expecting "Expecting ')' to end image block"))
        |. chompWhile (\c -> c == '\n')
    )
        -- xxx
        |> map (\ps -> Image ps.prefix ps.text)


{-|

> run italicText "_abc_"
> Ok (ItalicText "abc")

-}
link : Parser MDInline
link =
    (succeed PrefixedString
        |. symbol (Token "[" (Expecting "expecting '[' to begin label"))
        |= parseWhile (\c -> c /= ']')
        |. symbol (Token "]" (Expecting "expecting ']' to end first part of label"))
        |= oneOf [ linkUrl, terminateBracket ]
        |. spaces
    )
        |> map (\ps -> linkOrBracket ps)


linkOrBracket : PrefixedString -> MDInline
linkOrBracket ps =
    case ps.text of
        " " ->
            BracketedText ps.prefix

        _ ->
            Link ps.text ps.prefix


linkUrl : Parser String
linkUrl =
    succeed identity
        |. symbol (Token "(" (Expecting "expecting '(' to begin link url"))
        |= parseWhile (\c -> c /= ')')
        |. symbol (Token ")" (Expecting "expecting ')' to end link url"))
        |. spaces


terminateBracket : Parser String
terminateBracket =
    succeed ()
        -- |. symbol (Token " " DummyExpectation)
        |> map (\_ -> " ")


strikeThroughText : Parser MDInline
strikeThroughText =
    (succeed ()
        |. symbol (Token "~~" (Expecting "expecting '~~' to begin strikethrough"))
        |. chompWhile (\c -> c /= '~')
        |. symbol (Token "~~" (Expecting "expecting '~~' to end strikethrough"))
        |. spaces
    )
        |> getChompedString
        |> map (String.dropLeft 2)
        |> map (String.replace "~~" "")
        |> map StrikeThroughText


boldText : Parser MDInline
boldText =
    (succeed ()
        |. symbol (Token "**" (Expecting "expecting '**' to begin bold text"))
        |. chompWhile (\c -> c /= '*')
        |. symbol (Token "**" (Expecting "expecting '**' to end bold text"))
        |. spaces
    )
        |> getChompedString
        |> map (String.dropLeft 2)
        |> map (String.replace "**" "")
        |> map BoldText


italicText : Parser MDInline
italicText =
    (succeed ()
        |. symbol (Token "*" (Expecting "Expecting '*' to begin italic text"))
        |. chompWhile (\c -> c /= '*')
        |. symbol (Token "*" (Expecting "Expecting '*' to end italic text"))
        |. spaces
    )
        |> getChompedString
        |> map (String.replace "*" "")
        |> map ItalicText


htmlEntityText : Parser MDInline
htmlEntityText =
    (succeed ()
        |. symbol (Token "&" (Expecting "Expecting '&' to begin Html entity"))
        |. chompWhile (\c -> c /= ';')
        |. symbol (Token ";" (Expecting "Expecting ';' to end  Html entity"))
    )
        |> getChompedString
        |> map (String.replace "&" "" >> String.replace ";" "" >> String.replace " " "")
        |> map HtmlEntity


{-|

> run inlineMath "$a^5 = 3$"
> Ok (InlineMath ("a^5 = 3"))

-}
inlineMath : Parser MDInline
inlineMath =
    (succeed ()
        |. symbol (Token "$" (Expecting "Expecting '$' to begin inline math"))
        |. chompWhile (\c -> c /= '$')
        |. symbol (Token "$" (Expecting "Expecting '$' to end inline math"))
    )
        |> getChompedString
        |> map String.trim
        |> map (String.dropLeft 1)
        |> map (String.dropRight 1)
        |> map InlineMath


code : Parser MDInline
code =
    (succeed ()
        |. symbol (Token "`" (Expecting "Expecting '`' to begin inline code"))
        |. chompWhile (\c -> c /= '`')
        |. symbol (Token "`" (Expecting "Expecting '`' to end inline code"))
    )
        |> getChompedString
        |> map String.trim
        --|> map (String.dropLeft 1)
        --|> map (String.dropRight 1)
        |> map (String.replace "`" "")
        |> map Code


{-|

    > MDInline.parse "*foo* hahaha: hohoho, $a^6 + 2$"
    MMInlineList [ItalicText ("foo "),OrdinaryText ("hahaha: hohoho, "),InlineMath ("a^6 + 2")]

-}
inlineList : MarkdownOption -> Parser (List MDInline)
inlineList option =
    many (inline option)


resolveInlineResult : Result (List (DeadEnd String Problem)) (List MDInline) -> MDInline
resolveInlineResult result =
    case result of
        Ok res_ ->
            res_ |> Line

        Err list ->
            decodeInlineError list


decodeInlineError : List (DeadEnd String Problem) -> MDInline
decodeInlineError errorList =
    let
        errorMessage =
            List.map displayDeadEnd errorList
                |> String.join ";;\n\n"
    in
    OrdinaryText errorMessage


displayDeadEnd : DeadEnd String Problem -> String
displayDeadEnd deadend =
    case deadend.problem of
        Expecting error ->
            error



---
-- HELPERS
--


many : Parser a -> Parser (List a)
many p =
    loop [] (manyHelp p)


manyHelp : Parser a -> List a -> Parser (Step (List a) (List a))
manyHelp p vs =
    oneOf
        [ succeed (\v -> Loop (v :: vs))
            |= p
        , succeed ()
            |> map (\_ -> Done (List.reverse vs))
        ]
