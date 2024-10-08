module Tags.JsonDecoder exposing (..)

import Json.Decode exposing (..)
import Json.Decode.Pipeline exposing (..)

import Tags.DataTypes exposing (..)

decodeCompletionTags =
  at [ "data" ] (list decodeCompletionValue)

decodeCompletionValue : Decoder CompletionValue
decodeCompletionValue =
  succeed CompletionValue
    |> required "value" string

decodeTag : Decoder Tag
decodeTag =
  succeed Tag
    |> required "key" string
    |> required "value" string
