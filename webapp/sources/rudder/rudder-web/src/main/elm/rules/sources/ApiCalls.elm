module ApiCalls exposing (..)

import DataTypes exposing (..)
import Dict
import Http exposing (..)
import JsonDecoder exposing (..)
import JsonEncoder exposing (..)
import Json.Decode

getUrl: DataTypes.Model -> String -> String
getUrl m url =
  m.contextPath ++ "/secure/api" ++ url

getRulesTree : Model -> Cmd Msg
getRulesTree model =
  let
    req =
      request
        { method          = "GET"
        , headers         = []
        , url             = getUrl model "/rules/tree"
        , body            = emptyBody
        , expect          = expectJson decodeGetRulesTree
        , timeout         = Nothing
        , withCredentials = False
        }
  in
    send GetRulesResult req

getTechniques : Model -> Cmd Msg
getTechniques model =
  let
    req =
      request
        { method          = "GET"
        , headers         = []
        , url             = getUrl model "/techniques"
        , body            = emptyBody
        , expect          = expectJson decodeGetTechniques
        , timeout         = Nothing
        , withCredentials = False
        }
  in
    send GetTechniquesResult req

getDirectives : Model -> Cmd Msg
getDirectives model =
  let
    req =
      request
        { method          = "GET"
        , headers         = []
        , url             = getUrl model "/directives"
        , body            = emptyBody
        , expect          = expectJson decodeGetDirectives
        , timeout         = Nothing
        , withCredentials = False
        }
  in
    send GetDirectivesResult req

getPolicyMode : Model -> Cmd Msg
getPolicyMode model =
  let
    req =
      request
        { method          = "GET"
        , headers         = []
        , url             = getUrl model "/settings/global_policy_mode"
        , body            = emptyBody
        , expect          = expectJson decodeGetPolicyMode
        , timeout         = Nothing
        , withCredentials = False
        }
  in
    send GetPolicyModeResult req

getGroupsTree : Model -> Cmd Msg
getGroupsTree model =
  let
    req =
      request
        { method          = "GET"
        , headers         = []
        , url             = getUrl model "/groups/tree"
        , body            = emptyBody
        , expect          = expectJson decodeGetGroupsTree
        , timeout         = Nothing
        , withCredentials = False
        }
  in
    send GetGroupsTreeResult req

getTechniquesTree : Model -> Cmd Msg
getTechniquesTree model =
  let
    req =
      request
        { method          = "GET"
        , headers         = []
        , url             = getUrl model "/directives/tree"
        , body            = emptyBody
        , expect          = expectJson decodeGetTechniquesTree
        , timeout         = Nothing
        , withCredentials = False
        }
  in
    send GetTechniquesTreeResult req

getRuleDetails : Model -> String -> Cmd Msg
getRuleDetails model ruleId =
  let
    req =
      request
        { method          = "GET"
        , headers         = []
        , url             = getUrl model ("/rules/" ++ ruleId)
        , body            = emptyBody
        , expect          = expectJson decodeGetRuleDetails
        , timeout         = Nothing
        , withCredentials = False
        }
  in
    send GetRuleDetailsResult req

getRulesCompliance : Model -> Cmd Msg
getRulesCompliance model =
  let
    req =
      request
        { method          = "GET"
        , headers         = []
        , url             = getUrl model "/compliance/rules?level=6"
        , body            = emptyBody
        , expect          = expectJson decodeGetRulesCompliance
        , timeout         = Nothing
        , withCredentials = False
        }
  in
    send GetRulesComplianceResult req

saveRuleDetails : RuleDetails -> Bool -> Model ->  Cmd Msg
saveRuleDetails ruleDetails creation model =
  let
    req =
      request
        { method  = if creation then "PUT" else "POST"
        , headers = []
        , url     = getUrl model ("/rules/"++ruleDetails.id)
        , body    = encodeRuleDetails ruleDetails |> jsonBody
        , expect  = expectJson decodeGetRuleDetails
        , timeout = Nothing
        , withCredentials = False
        }
  in
    send SaveRuleDetails req