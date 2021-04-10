module JsonDecoder exposing (..)

import DataTypes exposing (Secret, SecretInfo)
import Json.Decode as D exposing (Decoder)
import Json.Decode.Pipeline exposing (hardcoded, optional, required)

decodeSecretsApi : Decoder (List SecretInfo)
decodeSecretsApi =
  D.at [ "data" ] ( D.at [ "secrets" ] (D.list <| decodeSecretInfo) )

decodeSecretInfo : Decoder SecretInfo
decodeSecretInfo =
  D.succeed SecretInfo
    |> required "name" D.string
    |> required "description" D.string
