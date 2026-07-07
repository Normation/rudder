module GroupCompliance.ApiCalls exposing (..)

import GroupCompliance.DataTypes exposing (..)
import GroupCompliance.JsonDecoder exposing (..)
import Http exposing (..)
import Url.Builder exposing (QueryParameter)



--
-- This files contains all API calls for the Group compliance UI
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


getGlobalGroupCompliance : Model -> Cmd Msg
getGlobalGroupCompliance model =
    let
        req =
            request
                { method = "GET"
                , headers = [ header "X-Requested-With" "XMLHttpRequest" ]
                , url = getUrl model [ "compliance", "groups", model.groupId.value ] []
                , body = emptyBody
                , expect = expectJson GetGroupComplianceResult decodeGetGroupCompliance
                , timeout = Nothing
                , tracker = Nothing
                }
    in
    req


getTargetedGroupCompliance : Model -> Cmd Msg
getTargetedGroupCompliance model =
    let
        req =
            request
                { method = "GET"
                , headers = [ header "X-Requested-With" "XMLHttpRequest" ]
                , url = getUrl model [ "compliance", "groups", model.groupId.value, "target" ] []
                , body = emptyBody
                , expect = expectJson GetGroupComplianceResult decodeGetGroupCompliance
                , timeout = Nothing
                , tracker = Nothing
                }
    in
    req


getGlobalGroupComplianceByRuleCSV : String -> Model -> Cmd Msg
getGlobalGroupComplianceByRuleCSV filename model =
    request
        { method = "GET"
        , headers = [ header "X-Requested-With" "XMLHttpRequest" ]
        , url = getUrl model [ "groups", model.groupId.value, "compliance", "global", "byRule" ] [ Url.Builder.string "format" "csv" ]
        , body = emptyBody
        , expect = expectString (RuleComplianceCsvExported filename)
        , timeout = Nothing
        , tracker = Nothing
        }


getTargetedGroupComplianceByRuleCSV : String -> Model -> Cmd Msg
getTargetedGroupComplianceByRuleCSV filename model =
    request
        { method = "GET"
        , headers = [ header "X-Requested-With" "XMLHttpRequest" ]
        , url = getUrl model [ "groups", model.groupId.value, "compliance", "targeted", "byRule" ] [ Url.Builder.string "format" "csv" ]
        , body = emptyBody
        , expect = expectString (RuleComplianceCsvExported filename)
        , timeout = Nothing
        , tracker = Nothing
        }
