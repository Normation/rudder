module ComplianceMode.JsonEncoder exposing (..)

import ComplianceMode.DataTypes exposing (..)
import Json.Encode exposing (..)


encodeMode : String -> Value
encodeMode mode =
    object
        [ ( "name", string mode )
        , ( "heartbeatPeriod", int 1 )
        ]
