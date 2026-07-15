module DirectiveCompliance.ApiCalls exposing (..)

import DirectiveCompliance.DataTypes exposing (..)
import DirectiveCompliance.JsonDecoder exposing (..)
import Http exposing (..)
import Url.Builder exposing (QueryParameter)



--
-- This files contains all API calls for the Directive compliance UI
--


getUrl : Model -> List String -> List QueryParameter -> String
getUrl m url p =
    Url.Builder.relative (m.contextPath :: "secure" :: "api" :: url) p


getPolicyMode : Model -> Cmd Msg
getPolicyMode model =
    let
        req =
            request
                { method = "GET"
                , headers = [ header "X-Requested-With" "XMLHttpRequest" ]
                , url = getUrl model [ "settings", "global_policy_mode" ] []
                , body = emptyBody
                , expect = expectJson GetPolicyModeResult decodeGetPolicyMode
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
                { method = "GET"
                , headers = [ header "X-Requested-With" "XMLHttpRequest" ]
                , url = getUrl model [ "compliance", "directives", model.directiveId.value ] []
                , body = emptyBody
                , expect = expectJson GetDirectiveComplianceResult decodeGetDirectiveCompliance
                , timeout = Nothing
                , tracker = Nothing
                }
    in
    req


getCSVExportByRule : String -> Model -> Cmd Msg
getCSVExportByRule filename model =
    let
        req =
            request
                { method = "GET"
                , headers = [ header "X-Requested-With" "XMLHttpRequest" ]
                , url = getUrl model [ "directives", model.directiveId.value, "compliance", "byRule" ] [ Url.Builder.string "format" "csv" ]
                , body = emptyBody
                , expect = expectString (DirectiveComplianceCsvExported filename)
                , timeout = Nothing
                , tracker = Nothing
                }
    in
    req


getCSVExportByNode : String -> Model -> Cmd Msg
getCSVExportByNode filename model =
    let
        req =
            request
                { method = "GET"
                , headers = [ header "X-Requested-With" "XMLHttpRequest" ]
                , url = getUrl model [ "directives", model.directiveId.value, "compliance", "byNode" ] [ Url.Builder.string "format" "csv" ]
                , body = emptyBody
                , expect = expectString (DirectiveComplianceCsvExported filename)
                , timeout = Nothing
                , tracker = Nothing
                }
    in
    req
