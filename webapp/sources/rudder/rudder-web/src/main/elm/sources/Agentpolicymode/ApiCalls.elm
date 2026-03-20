module Agentpolicymode.ApiCalls exposing (..)

import Http exposing (..)
import Http.Detailed as Detailed
import Json.Decode exposing (Decoder)
import Url.Builder exposing (QueryParameter)

import Agentpolicymode.DataTypes exposing (..)
import Agentpolicymode.JsonDecoder exposing (..)
import Agentpolicymode.JsonEncoder exposing (..)


--
-- This files contains all API calls for the Directive compliance UI
--

getUrl: Model -> List String -> List QueryParameter -> String
getUrl m url p=
  Url.Builder.relative (m.contextPath :: "secure" :: "api"  :: url) p

getGlobalPolicyMode : Model -> Cmd Msg
getGlobalPolicyMode model =
  let
    req =
      request
        { method  = "GET"
        , headers = [header "X-Requested-With" "XMLHttpRequest"]
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
        , headers = [header "X-Requested-With" "XMLHttpRequest"]
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
        , headers = [header "X-Requested-With" "XMLHttpRequest"]
        , url     = getUrl model [ "nodes", nodeId ] []
        , body    = emptyBody
        , expect  = expectJson GetNodePolicyModeResult decodeGetNodeDetails
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req

saveChanges : (Maybe Reason) -> Model -> Cmd Msg
saveChanges reason model =
  let
    (url, encodeSettings, decoder) = case model.ui.form of
      GlobalForm  -> ([ "settings" ], encodeGlobalSettings, decodeGetGlobalSettings)
      NodeForm id -> ([ "nodes", id ], encodeNodeSettings reason, decodeSaveNodeDetails)
    req =
      request
        { method  = "POST"
        , headers = [header "X-Requested-With" "XMLHttpRequest"]
        , url     = getUrl model url []
        , body    = encodeSettings model.selectedSettings |> jsonBody
        , expect  = expectJson SaveChanges decoder
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req

getChangeMessageSettings : Model -> Cmd Msg
getChangeMessageSettings model =
  let
    req =
      request
        { method  = "GET"
        , headers = [header "X-Requested-With" "XMLHttpRequest"]
        , url     = getUrl model [ "settings" ] []
        , body    = emptyBody
        , expect  = expectJson GetChangeMessageSettings decodeGetChangeMessageSettings
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req

changeMessageReasonParams : String -> List Url.Builder.QueryParameter
changeMessageReasonParams reason =
  [ Url.Builder.string "reason" reason ]


expectJson : (Result (Detailed.Error String) b -> c) -> Decoder b -> Expect c
expectJson msg =
  Detailed.expectJson (Result.map Tuple.second >> msg)