module ComplianceMode.JsonEncoder exposing (..)

import Json.Encode exposing (..)

import ComplianceMode.DataTypes exposing (..)

encodeMode : ComplianceMode -> Value
encodeMode mode =
  let
    complianceMode = case mode of
       FullCompliance  -> "full-compliance"
       ChangesOnly     -> "changes-only"
       ReportsDisabled -> "reports-disabled"
       UnknownMode     -> ""
  in
    object (
      [ ( "name"          , string complianceMode )
      , ("heartbeatPeriod", int 1                 )
      ]
    )