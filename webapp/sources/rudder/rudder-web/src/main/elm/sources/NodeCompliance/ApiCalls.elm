module NodeCompliance.ApiCalls exposing (..)

import Http exposing (..)
import Url.Builder exposing (QueryParameter)

import NodeCompliance.DataTypes exposing (..)
import NodeCompliance.JsonDecoder exposing (..)


--
-- This files contains all API calls for the Directive compliance UI
--

getUrl: Model -> List String -> List QueryParameter -> String
getUrl m url p=
  Url.Builder.relative (m.contextPath :: "secure" :: "api"  :: url) p

getPolicyMode : Model -> Cmd Msg
getPolicyMode model =
  let
    req =
      request
        { method  = "GET"
        , headers = []
        , url     = getUrl model [ "settings", "global_policy_mode" ] []
        , body    = emptyBody
        , expect  = expectJson GetPolicyModeResult decodeGetPolicyMode
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req

getNodeCompliance : Model -> Cmd Msg
getNodeCompliance model =
  let
    req =
      request
        { method  = "GET"
        , headers = []
        , url     = getUrl model [ "compliance", "nodes", model.nodeId.value ] []
        , body    = emptyBody
        , expect  = expectJson GetNodeComplianceResult decodeGetNodeCompliance
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req