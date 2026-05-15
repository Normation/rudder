module Activity.HtmlParserAdapter exposing (..)

import Html exposing (Html, div)
import Html.Parser exposing (Node)
import Html.Parser.Util
import Parser


parseHtml : String -> Result (List Parser.DeadEnd) (List Node)
parseHtml s =
    Html.Parser.run s


toHtml : List Node -> Html msg
toHtml nodes =
    div [] (Html.Parser.Util.toVirtualDom nodes)


toString : List Node -> String
toString nodes =
    nodes
        |> List.map Html.Parser.nodeToString
        |> String.join ""
