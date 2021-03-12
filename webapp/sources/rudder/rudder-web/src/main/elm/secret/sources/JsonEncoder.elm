module JsonEncoder exposing (..)

import DataTypes exposing (Secret)
import Json.Encode exposing (Value, object, string)

encodeSecret: Secret -> Value
encodeSecret secret =
  object
  [ ("name", string secret.name)
  , ("value", string secret.value)
  ]