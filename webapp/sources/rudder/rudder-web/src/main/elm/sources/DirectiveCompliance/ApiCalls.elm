module DirectiveCompliance.ApiCalls exposing (..)

import Http exposing (..)
import Url.Builder exposing (QueryParameter)

import DirectiveCompliance.DataTypes exposing (..)
import DirectiveCompliance.JsonDecoder exposing (..)


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
        , headers = [header "X-Requested-With" "XMLHttpRequest"]
        , url     = getUrl model [ "settings", "global_policy_mode" ] []
        , body    = emptyBody
        , expect  = expectJson GetPolicyModeResult decodeGetPolicyMode
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req

getDirectiveCompliance : Model -> Cmd Msg
getDirectiveCompliance model =
  let
    req =
      request
        { method  = "GET"
        , headers = [header "X-Requested-With" "XMLHttpRequest"]
        , url     = getUrl model [ "compliance", "directives", model.directiveId.value ] []
        , body    = emptyBody
        , expect  = expectJson GetDirectiveComplianceResult decodeGetDirectiveCompliance
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req

getCSVExport : Model -> Cmd Msg
getCSVExport model =
  let
    req =
      request
        { method  = "GET"
        , headers = [header "X-Requested-With" "XMLHttpRequest"]
        , url     = getUrl model [ "compliance", "directives", model.directiveId.value ] [ Url.Builder.string "format" "csv"]
        , body    = emptyBody
        , expect  = expectString Export
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req
