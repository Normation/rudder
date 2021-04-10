module JsonEncoder exposing (..)

import DataTypes exposing (Secret)
import Json.Encode exposing (Value, object, string)

encodeSecret: Secret -> Value
encodeSecret secret =
  object
  [ ("name", string secret.info.name)
  , ("value", string secret.value)
  , ("description", string secret.info.description)
  ]