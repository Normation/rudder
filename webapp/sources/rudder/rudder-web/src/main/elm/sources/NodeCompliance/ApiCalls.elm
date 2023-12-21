module NodeCompliance.ApiCalls exposing (..)

import Http exposing (..)
import Url.Builder exposing (QueryParameter)

import NodeCompliance.DataTypes exposing (..)
import NodeCompliance.JsonDecoder exposing (..)
import Rules.JsonDecoder exposing (decodeGetRules, decodeGetDirectives)


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

getRulesInfo : Model -> Cmd Msg
getRulesInfo model =
  let
    req =
      request
        { method  = "GET"
        , headers = []
        , url     = getUrl model [ "rules" ] []
        , body    = emptyBody
        , expect  = expectJson GetRulesInfo decodeGetRules
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req

getDirectivesInfo : Model -> Cmd Msg
getDirectivesInfo model =
  let
    req =
      request
        { method  = "GET"
        , headers = []
        , url     = getUrl model [ "directives" ] []
        , body    = emptyBody
        , expect  = expectJson GetDirectivesInfo decodeGetDirectives
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req

getNodeCompliance : Model -> Cmd Msg
getNodeCompliance model =
  let
    url = if model.onlySystem then [ "compliance", "nodes", model.nodeId.value, "system" ] else [ "compliance", "nodes", model.nodeId.value ]
    req =
      request
        { method  = "GET"
        , headers = []
        , url     = getUrl model url []
        , body    = emptyBody
        , expect  = expectJson GetNodeComplianceResult decodeGetNodeCompliance
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req