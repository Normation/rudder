module Hooks.JsonEncoder exposing (..)

import Json.Encode exposing (..)

import Hooks.DataTypes exposing (..)


encodeHook : Hook -> Value
encodeHook hook =
    object (
      [ ( "id" , string "" )
      ]
    )