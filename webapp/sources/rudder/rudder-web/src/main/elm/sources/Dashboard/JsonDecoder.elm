module Dashboard.JsonDecoder exposing (..)

import Dashboard.DataTypes exposing (..)
import Json.Decode exposing (..)
import Json.Decode.Pipeline exposing (..)
import List exposing (drop, head)
import String exposing (join, split)

-- GENERAL


decodeGetActivities : Decoder ApiResult
decodeGetActivities =
    at [ "data" ] decodeResult


decodeActivity : Decoder Activity
decodeActivity =
    succeed Activity
        |> required "id" string
        |> required "name" string

decodeResult : Decoder ApiResult
decodeResult =
    succeed ApiResult
        |> required "activities" (list decodeActivity)

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