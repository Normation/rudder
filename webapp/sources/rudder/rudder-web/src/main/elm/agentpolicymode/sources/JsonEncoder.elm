module  JsonEncoder exposing (..)

import DataTypes exposing (..)
import Json.Encode exposing (..)

policyModeToString : PolicyMode -> String
policyModeToString policyMode =
  case policyMode of
    Default -> "default"
    Audit   -> "audit"
    Enforce -> "enforce"
    None    -> ""

encodeGlobalSettings : Settings -> Value
encodeGlobalSettings settings =
  let
    overridableReason = if settings.overridable then "overridable" else "not overridable"
    policyMode = policyModeToString settings.policyMode
    reason = "Change global policy mode to '" ++ policyMode ++ "' (" ++ overridableReason ++ ")"
  in
    object (
      [ ( "global_policy_mode" , string policyMode )
      , ( "global_policy_mode_overridable" , bool settings.overridable )
      , ( "reason" , string reason )
      ]
    )

encodeNodeSettings : Settings -> Value
encodeNodeSettings settings =
  let
    policyMode = policyModeToString settings.policyMode
  in
    object ( [ ( "policyMode" , string policyMode ) ] )