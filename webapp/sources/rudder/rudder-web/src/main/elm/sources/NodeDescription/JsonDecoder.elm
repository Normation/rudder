module NodeDescription.JsonDecoder exposing (decodeGetDescription, decodeSetDescription)

import Json.Decode exposing (Decoder, at, field, index, string, succeed)
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
