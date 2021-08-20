{-
  This is a copy of Blocktype module from https://github.com/jxxcarlson/elm-markdown
  Once https://github.com/jxxcarlson/elm-markdown/pull/24 we can remove everything
-}
module BlockType exposing
    ( BalancedType(..)
    , BlockType(..)
    , Language(..)
    , Level
    , Line
    , MarkdownType(..)
    , deleteLangPrefix
    , extensionBlock
    , get
    , isBalanced
    , isCode
    , isMarkDown
    , isOListItem
    , level
    , parse
    , parseExtended
    , parseStandard
    , prefixOfBlockType
    , stringOfBlockType
    , stringOfLanguage
    )

{-| The BlockType module provides a parser that
inspects lines and determines the kind of block
they belong to (its BlockType). This parser is not
used directly, but rather is called by the function

    get : Option -> Line -> ( Level, Maybe BlockType )

which computes a tuple consisting of the Level and
BlockType of a Line. A Line is an alias for a Sting
and Level, which is an alias for Int, is a measure
of indentation: the number of leading spaces divided
by 4, where division is integer division.

-}

import Markdown.Option exposing (MarkdownOption(..))
import Parser.Advanced exposing ((|.), (|=), Step(..), Token(..), backtrackable, chompIf, chompUntil, chompWhile, getChompedString, map, oneOf, run, spaces, succeed, symbol)


levelIndentation : Int
levelIndentation =
    4


type alias Parser a =
    Parser.Advanced.Parser String Problem a


type Problem
    = Expecting String


type alias Level =
    Int


type alias Line =
    String


type BlockType
    = BalancedBlock BalancedType
    | MarkdownBlock MarkdownType


type BalancedType
    = DisplayCode Language
    | Verbatim
    | DisplayMath


type Language
    = ElmLang
    | CssLang
    | JavascriptLang
    | JsonLang
    | PythonLang
    | SqlLang
    | XmlLang
    | NoLang


stringOfLanguage : Language -> String
stringOfLanguage lang_ =
    case lang_ of
        ElmLang ->
            "elm"

        CssLang ->
            "css"

        JavascriptLang ->
            "javascript"

        JsonLang ->
            "json"

        PythonLang ->
            "python"

        SqlLang ->
            "sql"

        XmlLang ->
            "xml"

        NoLang ->
            "nolang"


type MarkdownType
    = Root
    | UListItem
    | OListItem Int
    | Heading Int
    | HorizontalRule
    | Quotation
    | ExtensionBlock String
    | Poetry
    | Plain
    | Image
    | Blank
    | TableCell
    | TableRow
    | Table


get : MarkdownOption -> Line -> ( Level, Maybe BlockType )
get option str =
    if str == "\n" then
        ( 0, Just (MarkdownBlock Blank) )

    else
        case run (parse option) (dropLeadingBlanks str) of
            Ok result ->
                ( level str, Just result )

            Err _ ->
                ( level str, Just (MarkdownBlock Plain) )



-- PARSER BLOCKTYPE --


{-| The top-level parse function dispatches
work to parserStandard or parseExtended
according to the given option
-}
parse : MarkdownOption -> Parser BlockType
parse option =
    case option of
        Standard ->
            parseStandard

        _ ->
            parseExtended


parseStandard : Parser BlockType
parseStandard =
    oneOf
        [ tableBlock
        , imageBlock
        , unorderedListItemBlock
        , orderedListItemBlock
        , quotationBlock
        , codeBlock
        , headingBlock
        , horizontalRuleBlock
        ]


parseExtended : Parser BlockType
parseExtended =
    oneOf
        [ imageBlock
        , mathBlock
        , unorderedListItemBlock
        , orderedListItemBlock
        , extensionBlock
        , quotationBlock
        , poetryBlock
        , backtrackable verbatimBlock
        , codeBlock
        , tableBlock
        , headingBlock
        , horizontalRuleBlock
        ]



-- PARSERS --


tableBlock : Parser BlockType
tableBlock =
    (succeed ()
        |. symbol (Token "| " (Expecting "expecting '| ' to begin poetry block"))
    )
        |> map (\_ -> MarkdownBlock TableRow)


poetryBlock : Parser BlockType
poetryBlock =
    (succeed ()
        |. symbol (Token ">> " (Expecting "expecting '>> ' to begin poetry block"))
    )
        |> map (\_ -> MarkdownBlock Poetry)


quotationBlock : Parser BlockType
quotationBlock =
    (succeed ()
        |. symbol (Token "> " (Expecting "expecting '> ' to begin quotation"))
    )
        |> map (\_ -> MarkdownBlock Quotation)


extensionBlock : Parser BlockType
extensionBlock =
    succeed (\s -> MarkdownBlock (ExtensionBlock s))
        |. symbol (Token "@@" (Expecting "expecting '@@' to begin extended block"))
        |= restOfLine



--|= chompWhile (\c -> c /= \n)


restOfLine : Parser String
restOfLine =
    getChompedString <|
        succeed ()
            -- |. chompIf (\c -> c /= '\n') (Expecting "whatever")
            |. chompWhile (\c -> c /= '\n')
            |. symbol (Token "\n" (Expecting "expecting newline"))


orderedListItemBlock : Parser BlockType
orderedListItemBlock =
    (succeed ()
        |. parseWhile (\c -> c == ' ')
        |. chompIf (\c -> Char.isDigit c) (Expecting "Expecting digit to begin ordered list item")
        |. chompWhile (\c -> Char.isDigit c)
        |. symbol (Token ". " (Expecting "expecting period"))
    )
        |> map (\_ -> MarkdownBlock (OListItem 0))


horizontalRuleBlock : Parser BlockType
horizontalRuleBlock =
    (succeed ()
        |. spaces
        |. symbol (Token "___" (Expecting "Expecting at least three underscores to begin thematic break"))
    )
        |> map (\_ -> MarkdownBlock HorizontalRule)


headingBlock : Parser BlockType
headingBlock =
    (succeed identity
        |. spaces
        |. symbol (Token "#" (Expecting "Expecting '#' to begin heading block"))
        |= parseWhile (\c -> c == '#')
    )
        |> map (\s -> MarkdownBlock (Heading (String.length s + 1)))


codeBlock : Parser BlockType
codeBlock =
    (succeed identity
        |. symbol (Token "```" (Expecting "Expecting three ticks to begin code block"))
        |= oneOf [ cssLang, elmLang, javascriptLang, jsonLang, pythonLang, sqlLang, xmlLang, noLang ]
    )
        |> map (\lang -> BalancedBlock (DisplayCode lang))


deleteLangPrefix : Language -> String -> String
deleteLangPrefix lang str =
    String.dropLeft (String.length (stringOfLanguage lang) + 1) str


cssLang : Parser Language
cssLang =
    succeed CssLang
        |. symbol (Token "css" (Expecting "Expecting string for language, use ```nolang if language is unknown or unsupported"))


elmLang : Parser Language
elmLang =
    succeed ElmLang
        |. symbol (Token "elm" (Expecting "Expecting string for language, use ```nolang if language is unknown or unsupported"))


javascriptLang : Parser Language
javascriptLang =
    succeed JavascriptLang
        |. symbol (Token "javascript" (Expecting "Expecting string for language, use ```nolang if language is unknown or unsupported"))


jsonLang : Parser Language
jsonLang =
    succeed JsonLang
        |. symbol (Token "json" (Expecting "Expecting string for language, use ```nolang if language is unknown or unsupported"))


pythonLang : Parser Language
pythonLang =
    succeed PythonLang
        |. symbol (Token "python" (Expecting "Expecting string for language, use ```nolang if language is unknown or unsupported"))


sqlLang : Parser Language
sqlLang =
    succeed SqlLang
        |. symbol (Token "sql" (Expecting "Expecting string for language, use ```nolang if language is unknown or unsupported"))


xmlLang : Parser Language
xmlLang =
    succeed XmlLang
        |. symbol (Token "xml" (Expecting "Expecting string for language, use ```nolang if language is unknown or unsupported"))


noLang : Parser Language
noLang =
    succeed NoLang
        |. symbol (Token "nolang" (Expecting "Expecting string for language, use ```nolang if language is unknown or unsupported"))


verbatimBlock : Parser BlockType
verbatimBlock =
    succeed (BalancedBlock Verbatim)
        |. symbol (Token "````" (Expecting "Expecting four ticks to begin verbatim block"))


mathBlock : Parser BlockType
mathBlock =
    succeed (BalancedBlock DisplayMath)
        |. symbol (Token "$$" (Expecting "Expecting four ticks to begin verbatim block"))


imageBlock : Parser BlockType
imageBlock =
    succeed (MarkdownBlock Image)
        |. symbol (Token "![" (Expecting "Expecting '![' to begin image block"))


unorderedListItemBlock : Parser BlockType
unorderedListItemBlock =
    succeed (MarkdownBlock UListItem)
        |. symbol (Token "- " (Expecting "Expecting '-' to begin list item"))


parseWhile : (Char -> Bool) -> Parser String
parseWhile accepting =
    chompWhile accepting |> getChompedString



-- HELPER FUNCTIONS --


prefixOfBalancedType : BalancedType -> String
prefixOfBalancedType bt =
    case bt of
        DisplayCode _ ->
            "```"

        Verbatim ->
            "````"

        DisplayMath ->
            "$$"


prefixOfBlockType : BlockType -> String -> String
prefixOfBlockType bt line =
    case bt of
        BalancedBlock bb ->
            prefixOfBalancedType bb

        MarkdownBlock mdb ->
            prefixOfMarkdownType mdb line


{-| Return prefix of line, e.g. ,'## ' for
a level 2 heading
-}
prefixOfMarkdownType : MarkdownType -> String -> String
prefixOfMarkdownType mdt line =
    let
        runPrefix : Parser String -> String -> String
        runPrefix prefixParser str =
            case run prefixParser str of
                Ok prefix ->
                    prefix

                Err _ ->
                    ""
    in
    case mdt of
        Root ->
            ""

        UListItem ->
            runPrefix uListPrefix line

        OListItem _ ->
            runPrefix oListPrefix line

        Heading k ->
            String.repeat k "#" ++ " "

        ExtensionBlock _ ->
            -- TODO: is this correct?
            "|"

        HorizontalRule ->
            "___"

        Quotation ->
            "> "

        Poetry ->
            ">> "

        Plain ->
            ""

        Image ->
            ""

        TableCell ->
            ""

        TableRow ->
            ""

        Table ->
            ""

        Blank ->
            ""


oListPrefix : Parser String
oListPrefix =
    (getChompedString <|
        succeed identity
            |= chompUntil (Token "." (Expecting "expecting '.' to begin OListItem block"))
    )
        |> map (\x -> x ++ ". ")


uListPrefix : Parser String
uListPrefix =
    (getChompedString <|
        succeed identity
            |= chompUntil (Token "-" (Expecting "expecting '-' to begin UListItem block"))
    )
        |> map
            (\s -> s ++ "- ")


isBalanced : BlockType -> Bool
isBalanced bt =
    case bt of
        BalancedBlock _ ->
            True

        MarkdownBlock _ ->
            False


isCode : BlockType -> Bool
isCode bt =
    case bt of
        BalancedBlock (DisplayCode _) ->
            True

        _ ->
            False


isOListItem : BlockType -> Bool
isOListItem blockType =
    case blockType of
        MarkdownBlock (OListItem _) ->
            True

        _ ->
            False


isMarkDown : BlockType -> Bool
isMarkDown bt =
    case bt of
        BalancedBlock _ ->
            False

        MarkdownBlock _ ->
            True


numberOfLeadingBlanks : Parser Int
numberOfLeadingBlanks =
    (succeed ()
        |. chompWhile (\c -> c == ' ')
    )
        |> getChompedString
        |> map String.length



--|> map String.trim


getNumberOfLeadingBlanks : String -> Int
getNumberOfLeadingBlanks str =
    run numberOfLeadingBlanks str
        |> Result.toMaybe
        |> Maybe.withDefault 0


dropLeadingBlanks : String -> String
dropLeadingBlanks str =
    String.dropLeft (getNumberOfLeadingBlanks str) str


level : Line -> Int
level ln =
    run numberOfLeadingBlanks ln
        |> Result.toMaybe
        |> Maybe.map (\l -> l // levelIndentation)
        |> Maybe.withDefault 0



-- STRING REPRESENTATIONS --


stringOfBlockType : BlockType -> String
stringOfBlockType bt =
    case bt of
        BalancedBlock bt_ ->
            stringOfBalancedType bt_

        MarkdownBlock mt ->
            stringOfMarkDownType mt


stringOfBalancedType : BalancedType -> String
stringOfBalancedType bt =
    case bt of
        DisplayCode language ->
            stringOfLanguage language

        Verbatim ->
            "Verbatim"

        DisplayMath ->
            "DisplayMath"


stringOfMarkDownType : MarkdownType -> String
stringOfMarkDownType mt =
    case mt of
        Root ->
            "Root"

        UListItem ->
            "UListItem"

        OListItem _ ->
            "OListItem"

        Heading _ ->
            "Heading"

        HorizontalRule ->
            "HorizontalRule"

        Poetry ->
            "Poetry"

        Quotation ->
            "Quotation"

        ExtensionBlock _ ->
            -- TODO: is this correct?
            "Extended"

        Plain ->
            "Plain"

        Image ->
            "Image"

        TableCell ->
            "TableCell"

        TableRow ->
            "TableRow"

        Table ->
            "Table"

        Blank ->
            "Blank"
