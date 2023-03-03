module JsonDecoder exposing (..)

import DataTypes exposing (..)
import Json.Decode exposing (..)
import Json.Decode.Pipeline exposing (..)
import Json.Decode.Field exposing (..)


toPolicyMode : String -> Decoder PolicyMode
toPolicyMode str =
  succeed ( case str of
    "default" -> Default
    "audit"   -> Audit
    "enforce" -> Enforce
    _         -> None
  )

decodeGetPolicyMode : Decoder PolicyMode
decodeGetPolicyMode =
  at ["data", "settings", "global_policy_mode" ] ( string |> andThen (\s -> toPolicyMode s) )

decodeGetPolicyModeOverridable : Decoder Bool
decodeGetPolicyModeOverridable =
  at ["data", "settings", "global_policy_mode_overridable" ] bool

decodeGetNodeDetails : Decoder Settings
decodeGetNodeDetails =
  at [ "data" , "nodes"] (index 0 decodeNodePolicyMode)

decodeSaveNodeDetails : Decoder Settings
decodeSaveNodeDetails =
  at [ "data" ] decodeNodePolicyMode

decodeGetGlobalSettings : Decoder Settings
decodeGetGlobalSettings =
  at [ "data" , "settings" ] decodeGlobalSettings

decodeGlobalSettings : Decoder Settings
decodeGlobalSettings =
  succeed Settings
    |> required "global_policy_mode"  ( string |> andThen (\s -> toPolicyMode s) )
    |> required "global_policy_mode_overridable" bool

decodeNodePolicyMode : Decoder Settings
decodeNodePolicyMode =
  succeed Settings
    |> required "policyMode"  ( string |> andThen (\s -> toPolicyMode s) )
    |> hardcoded False
