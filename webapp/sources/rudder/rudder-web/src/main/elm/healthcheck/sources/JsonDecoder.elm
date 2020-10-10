module JsonDecoder exposing (..)

import DataTypes exposing (Check, SeverityLevel(..))
import Json.Decode as D exposing (Decoder, andThen, fail, string, succeed)
import Json.Decode.Pipeline exposing (required)
import String exposing (toLower)

decodeGetRoleApiResult : Decoder (List Check)
decodeGetRoleApiResult =
    D.at [ "data" ] (D.list <| decodeCheck)

decodeCheck : Decoder Check
decodeCheck =
    D.succeed Check
        |> required "name" D.string
        |> required "msg" D.string
        |> required "status" decodeSeverityLevel

stringToSeverityLevel : String -> Decoder SeverityLevel
stringToSeverityLevel str =
    case toLower str of
        "ok"       -> succeed CheckPassed
        "warning"  -> succeed Warning
        "critical" -> succeed Critical
        _          -> fail ("Value `" ++ str ++ "` is not a SeverityLevel")

decodeSeverityLevel : Decoder SeverityLevel
decodeSeverityLevel = string|> andThen stringToSeverityLevel
