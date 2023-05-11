module ApiCalls exposing (..)

import DataTypes exposing (..)
import Http exposing (..)
import JsonDecoder exposing (..)
import JsonEncoder exposing (..)
import Url.Builder exposing (QueryParameter)


--
-- This files contains all API calls for the Directive compliance UI
--

getUrl: DataTypes.Model -> List String -> List QueryParameter -> String
getUrl m url p=
  Url.Builder.relative (m.contextPath :: "secure" :: "api"  :: url) p

getGlobalPolicyMode : Model -> Cmd Msg
getGlobalPolicyMode model =
  let
    req =
      request
        { method  = "GET"
        , headers = []
        , url     = getUrl model [ "settings", "global_policy_mode" ] []
        , body    = emptyBody
        , expect  = expectJson GetGlobalPolicyModeResult decodeGetPolicyMode
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req

getPolicyModeOverridable : Model -> Cmd Msg
getPolicyModeOverridable model =
  let
    req =
      request
        { method  = "GET"
        , headers = []
        , url     = getUrl model [ "settings", "global_policy_mode_overridable" ] []
        , body    = emptyBody
        , expect  = expectJson GetPolicyModeOverridableResult decodeGetPolicyModeOverridable
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req

getNodePolicyMode : Model -> String -> Cmd Msg
getNodePolicyMode model nodeId =
  let
    req =
      request
        { method  = "GET"
        , headers = []
        , url     = getUrl model [ "nodes", nodeId ] []
        , body    = emptyBody
        , expect  = expectJson GetNodePolicyModeResult decodeGetNodeDetails
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req

saveChanges : Model -> Cmd Msg
saveChanges model =
  let
    (url, encodeSettings, decoder) = case model.ui.form of
      GlobalForm  -> ([ "settings" ], encodeGlobalSettings, decodeGetGlobalSettings)
      NodeForm id -> ([ "nodes", id ], encodeNodeSettings, decodeSaveNodeDetails)
    req =
      request
        { method  = "POST"
        , headers = []
        , url     = getUrl model url []
        , body    = encodeSettings model.selectedSettings |> jsonBody
        , expect  = expectJson SaveChanges decoder
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req