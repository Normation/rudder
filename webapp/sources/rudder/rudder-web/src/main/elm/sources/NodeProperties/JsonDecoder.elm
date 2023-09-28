module NodeProperties.JsonDecoder exposing (..)

import Json.Decode exposing (..)
import Json.Decode.Pipeline exposing (..)

import NodeProperties.DataTypes exposing (..)


-- GENERAL
decodeGetProperties =
  at [ "data" ] (index 0 decodeProperties)

decodeGetGroupProperties =
  at [ "data", "groups" ] (index 0 decodeProperties)

decodeSaveProperties =
  at [ "data" ] decodeProperties

decodeSaveGroupProperties =
  at [ "data", "groups" ] (index 0 decodeProperties)

decodeProperties =
  at [ "properties" ] (list decodeProperty)

decodeProperty : Decoder Property
decodeProperty =
  succeed Property
    |> required "name"      string
    |> required "value"     value
    |> optional "provider"  (map Just string) Nothing
    |> optional "hierarchy" (map Just string) Nothing
    |> optional "origval"   (map Just value) Nothing

