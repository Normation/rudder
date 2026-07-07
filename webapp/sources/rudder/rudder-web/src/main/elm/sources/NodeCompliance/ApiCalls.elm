module NodeCompliance.ApiCalls exposing (..)

import Http exposing (..)
import NodeCompliance.DataTypes exposing (..)
import NodeCompliance.JsonDecoder exposing (..)
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


getNodeCompliance : Model -> Cmd Msg
getNodeCompliance model =
    let
        url =
            if model.onlySystem then
                [ "nodes", model.nodeId.value, "compliance", "system" ]

            else
                [ "nodes", model.nodeId.value, "compliance" ]

        req =
            request
                { method = "GET"
                , headers = [ header "X-Requested-With" "XMLHttpRequest" ]
                , url = getUrl model url []
                , body = emptyBody
                , expect = expectJson GetNodeComplianceResult decodeGetNodeCompliance
                , timeout = Nothing
                , tracker = Nothing
                }
    in
    req


getNodeComplianceCsv : String -> Model -> Cmd Msg
getNodeComplianceCsv filename model =
    let
        url =
            if model.onlySystem then
                [ "nodes", model.nodeId.value, "compliance", "system" ]

            else
                [ "nodes", model.nodeId.value, "compliance" ]

        req =
            request
                { method = "GET"
                , headers = [ header "X-Requested-With" "XMLHttpRequest" ]
                , url = getUrl model url [ Url.Builder.string "format" "csv" ]
                , body = emptyBody
                , expect = expectString (NodeComplianceCsvExported filename)
                , timeout = Nothing
                , tracker = Nothing
                }
    in
    req
