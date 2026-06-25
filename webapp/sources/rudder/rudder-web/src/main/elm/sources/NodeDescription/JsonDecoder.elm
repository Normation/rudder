module NodeDescription.JsonDecoder exposing (decodeGetDescription, decodeNodeWriteAuth, decodeSetDescription)

import Json.Decode exposing (Decoder, andThen, at, field, index, list, string, succeed)
import Json.Decode.Field exposing (optional)


decodeGetDescription : Decoder String
decodeGetDescription =
    let
        continuation : Maybe String -> Decoder String
        continuation =
            Maybe.withDefault "" >> succeed
    in
    at [ "data", "nodes" ] (index 0 (optional "description" string continuation))


decodeSetDescription : Decoder String
decodeSetDescription =
    at [ "data" ] (field "documentation" string)


decodeNodeWriteAuth : Decoder Bool
decodeNodeWriteAuth =
    let
        hasNodeWriteAuth authList =
            succeed (List.member "node_write" authList)
    in
    at [ "data", "coverage" ] (field "custom" (list string) |> andThen hasNodeWriteAuth)
