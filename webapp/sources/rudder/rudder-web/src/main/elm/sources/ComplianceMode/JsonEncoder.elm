module ComplianceMode.JsonEncoder exposing (..)

import Json.Encode exposing (..)

import ComplianceMode.DataTypes exposing (..)

encodeMode : String -> Value
encodeMode mode =
  object (
    [ ( "name"            , string mode )
    , ( "heartbeatPeriod" , int 1       )
    ]
  )