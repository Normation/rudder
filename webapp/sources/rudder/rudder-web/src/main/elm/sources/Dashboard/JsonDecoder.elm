module Dashboard.JsonDecoder exposing (..)

import Dashboard.DataTypes exposing (..)
import Json.Decode exposing (..)
import Json.Decode.Pipeline exposing (..)
import List exposing (drop, head)
import String exposing (join, split)
import Iso8601
import Time exposing (Posix, Zone)


decodeGetActivities : Decoder (List Activity)
decodeGetActivities =
    at [ "data" ] (list decodeActivity)

decodeActivity : Decoder Activity
decodeActivity =
    succeed Activity
        |> required "id" int
        |> required "actor" string
        |> required "description" string
        |> required "type" string
        |> required "date" ( string |> andThen (\s -> toPosix s) )

toPosix : String -> Decoder (Maybe Posix)
toPosix str =
    let
      newFormat = String.replace " " "T" str
    in
        succeed ( case Iso8601.toTime newFormat of
            Ok p -> Just p
            Err _ -> Nothing
        )

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