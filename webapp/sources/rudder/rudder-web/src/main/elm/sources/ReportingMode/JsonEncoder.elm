module ReportingMode.JsonEncoder exposing (..)

import Json.Encode exposing (..)

encodeMode : String -> Value
encodeMode mode =
  object (
    [ ( "mode", string mode )
    ]
  )
