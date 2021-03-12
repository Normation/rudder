module JsonDecoder exposing (..)

import DataTypes exposing (Secret)
import Json.Decode as D exposing (Decoder)
import Json.Decode.Pipeline exposing (required)

decodeGetAllSecrets : Decoder (List Secret)
decodeGetAllSecrets =
  D.at [ "data" ] ( D.at [ "secrets" ] (D.list <| decodeSecret) )

decodeOneSecret : Decoder Secret
decodeOneSecret =
  D.at [ "data" ] ( D.at [ "secret" ] decodeSecret)

decodeDeleteSecret : Decoder String
decodeDeleteSecret =
  D.at [ "data" ] ( D.at [ "secretName" ] D.string )

decodeSecret : Decoder Secret
decodeSecret =
  D.succeed Secret
    |> required "name" D.string
    |> required "value" D.string