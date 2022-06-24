module  JsonEncoder exposing (..)

import DataTypes exposing (..)
import Json.Encode exposing (..)

encodeHook : Hook -> Value
encodeHook hook =
    object (
      [ ( "id" , string "" )
      ]
    )