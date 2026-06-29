module Hooks.JsonEncoder exposing (..)

import Hooks.DataTypes exposing (..)
import Json.Encode exposing (..)


encodeHook : Hook -> Value
encodeHook hook =
    object
        [ ( "id", string "" )
        ]
