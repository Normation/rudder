module Agentpolicymode.JsonDecoder exposing (..)

import Json.Decode exposing (..)
import Json.Decode.Pipeline exposing (..)
import Json.Decode.Field exposing (..)
import List exposing (drop, head)
import String exposing (join, split)

import Agentpolicymode.DataTypes exposing (..)


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


-- Same as in Rules
decodeGetEnableChangeMsg : Decoder Bool
decodeGetEnableChangeMsg =
  at ["data", "settings", "enable_change_message" ] bool

decodeGetMandatoryMsg : Decoder Bool
decodeGetMandatoryMsg =
  at ["data", "settings", "mandatory_change_message" ] bool

decodeGetMsgPrompt : Decoder String
decodeGetMsgPrompt =
  at ["data", "settings", "change_message_prompt" ] string



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

decodeErrorDetails : String -> (String, String)
decodeErrorDetails json =
  let
    errorMsg = decodeString (Json.Decode.at ["errorDetails"] string) json
    msg = case errorMsg of
      Ok s -> s
      Err e -> "fail to process errorDetails"
    errors = split "<-" msg
    title = head errors
  in
  case title of
    Nothing -> ("" , "")
    Just s -> (s , (join " \n " (drop 1 (List.map (\err -> "\t ‣ " ++ err) errors))))
