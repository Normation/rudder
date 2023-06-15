module NodeProperties.JsonDecoder exposing (..)

import Json.Decode exposing (..)
import Json.Decode.Pipeline exposing (..)

import NodeProperties.DataTypes exposing (..)


-- GENERAL
decodeGetProperties =
  at [ "data" ] (index 0 decodeProperties)

decodeSaveProperties =
  at [ "data" ] decodeProperties

decodeProperties =
  at [ "properties" ] (list decodeProperty)

decodePropertyValue : Decoder JsonValue
decodePropertyValue =
  oneOf
  [ map JsonString string
  , map JsonInt int
  , map JsonFloat float
  , map JsonBoolean bool
  , list (lazy (\_ -> decodePropertyValue)) |> map JsonArray
  , dict (lazy (\_ -> decodePropertyValue)) |> map JsonObject
  , null JsonNull
  ]

decodePropertyValueTest : Decoder JsonValue
decodePropertyValueTest =
  oneOf
  [ map JsonString string
  , map JsonInt int
  , map JsonFloat float
  , map JsonBoolean bool
  , list (lazy (\_ -> decodePropertyValue)) |> map JsonArray
  , dict (lazy (\_ -> decodePropertyValue)) |> map JsonObject
  , null JsonNull
  ]

decodeProperty : Decoder Property
decodeProperty =
  succeed Property
    |> required "name"      string
    |> required "value"     decodePropertyValue
    |> optional "provider"  (map Just string) Nothing
    |> optional "hierarchy" (map Just string) Nothing
    |> optional "origval"   (map Just decodePropertyValue) Nothing