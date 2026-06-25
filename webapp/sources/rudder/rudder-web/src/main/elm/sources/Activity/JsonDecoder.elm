module Activity.JsonDecoder exposing (..)

import Activity.DataTypes exposing (..)
import Activity.HtmlParserAdapter exposing (parseHtml)
import Html.Parser exposing (Node(..))
import Json.Decode exposing (..)
import Json.Decode.Extra
import Json.Decode.Pipeline exposing (..)
import List exposing (drop, head)
import String exposing (join, split)


decodeGetActivities : Decoder (List Activity)
decodeGetActivities =
    at [ "data" ] (list decodeActivity)


decodeActivity : Decoder Activity
decodeActivity =
    succeed Activity
        |> required "id" int
        |> required "actor" string
        |> required "description" descriptionDecoder
        |> required "date" Json.Decode.Extra.datetime


descriptionDecoder : Decoder (List Node)
descriptionDecoder =
    let
        s2Nodes : String -> Decoder (List Node)
        s2Nodes description =
            case parseHtml description of
                Ok nodes ->
                    succeed nodes

                Err _ ->
                    fail ("Error when decoding description: " ++ description ++ " to Html")
    in
    string |> andThen s2Nodes


decodeErrorDetails : String -> ( String, String )
decodeErrorDetails json =
    let
        errorMsg =
            decodeString (Json.Decode.at [ "errorDetails" ] string) json

        msg =
            case errorMsg of
                Ok s ->
                    s

                Err _ ->
                    "fail to process errorDetails"

        errors =
            split "<-" msg

        title =
            head errors
    in
    case title of
        Nothing ->
            ( "", "" )

        Just s ->
            ( s, join " \n " (drop 1 (List.map (\err -> "\t ‣ " ++ err) errors)) )
