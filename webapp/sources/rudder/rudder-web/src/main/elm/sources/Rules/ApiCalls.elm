module Rules.ApiCalls exposing (..)

import Http exposing (..)
import Time.Iso8601
import Time.ZonedDateTime exposing (ZonedDateTime)
import Url
import Url.Builder exposing (QueryParameter, int, string)

import Rules.DataTypes exposing (..)
import Rules.JsonDecoder exposing (..)
import Rules.JsonEncoder exposing (..)
import Rules.ChangeRequest exposing (changeRequestParameters, decodeGetChangeRequestSettings, decodePendingChangeRequests)

--
-- This files contains all API calls for the Rules UI
-- Summary:
-- GET    /rules/tree: get the rules tree
-- GET    /settings/global_policy_mode : Get the global policy mode settings
-- GET    /groups/tree: get the groups tree
-- GET    /directives/tree : get the directives tree
-- GET    /rules/${id} : get the details of the selected rules
-- GET    /compliance/rules?level=6 : get the compliance details of all rules
-- PUT    /rules : Create a new rule (error if existing)
-- POST   /rules/${id} : Update an existing rule (error if it doesn't exist yet)


getUrl: Model -> List String -> List QueryParameter -> String
getUrl m url p=
  Url.Builder.relative (m.contextPath :: "secure" :: "api"  :: url) p

getRulesTree : Model -> Cmd Msg
getRulesTree model =
  let
    req =
      request
        { method  = "GET"
        , headers = []
        , url     = getUrl model [ "rules" ,"tree" ] []
        , body    = emptyBody
        , expect  = expectJson GetRulesResult decodeGetRulesTree
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req

getRuleChanges : Model -> Cmd Msg
getRuleChanges model =
  let
    req =
      request
        { method  = "GET"
        , headers = []
        , url     = getUrl model [ "changes" ] []
        , body    = emptyBody
        , expect  = expectJson GetRuleChanges decodeRuleChanges
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req


getRepairedReports : Model -> RuleId -> ZonedDateTime -> ZonedDateTime -> Cmd Msg
getRepairedReports model ruleId start end =
  let
    req =
      request
        { method  = "GET"
        , headers = []
        , url     = getUrl model [ "changes", ruleId.value ] [string "start" (Time.Iso8601.fromZonedDateTime start), string "end" (Time.Iso8601.fromZonedDateTime end) ]
        , body    = emptyBody
        , expect  = expectJson (GetRepairedReportsResult ruleId start end) decodeRepairedReports
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req



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

getCrSettings : Model -> Cmd Msg
getCrSettings model =
  let
    req =
      request
        { method  = "GET"
        , headers = []
        , url     = getUrl model [ "settings" ] []
        , body    = emptyBody
        , expect  = expectJson GetChangeRequestSettings decodeGetChangeRequestSettings
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req

getPendingChangeRequests : Model -> RuleId -> Cmd Msg
getPendingChangeRequests model ruleId =
  let
    req =
      request
        { method  = "GET"
        , headers = []
        , url     = getUrl model [ "changeRequests" ] [string "status" "open", string "ruleId" ruleId.value]
        , body    = emptyBody
        , expect  = expectJson GetPendingChangeRequests decodePendingChangeRequests
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req

getGroupsTree : Model -> Cmd Msg
getGroupsTree model =
  let
    req =
      request
        { method  = "GET"
        , headers = []
        , url     = getUrl model ["groups", "tree"] []
        , body    = emptyBody
        , expect  = expectJson GetGroupsTreeResult decodeGetGroupsTree
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req

getTechniquesTree : Model -> Cmd Msg
getTechniquesTree model =
  let
    req =
      request
        { method  = "GET"
        , headers = []
        , url     = getUrl model [ "directives", "tree" ] []
        , body    = emptyBody
        , expect  = expectJson GetTechniquesTreeResult decodeGetTechniquesTree
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req

getRuleDetails : Model -> RuleId -> Cmd Msg
getRuleDetails model ruleId =
  let
    req =
      request
        { method  = "GET"
        , headers = []
        , url     = getUrl model [ "rules" , ruleId.value ] []
        , body    = emptyBody
        , expect  = expectJson GetRuleDetailsResult decodeGetRuleDetails
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req

-- Warning: This API doesn't get sub categories and sub elements
getRulesCategoryDetails : Model -> String -> Cmd Msg
getRulesCategoryDetails model catId =
  let
    req =
      request
        { method  = "GET"
        , headers = []
        , url     = getUrl model ["rules" , "categories", catId] []
        , body    = emptyBody
        , expect  = expectJson GetCategoryDetailsResult decodeGetCategoryDetails
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req

getRuleNodesDirectives : RuleId ->  Model -> Cmd Msg
getRuleNodesDirectives ruleId model =
  let
    req =
      request
        { method  = "GET"
        , headers = []
        , url     = getUrl model [ "rulesinternal", "nodesanddirectives", ruleId.value ] []
        , body    = emptyBody
        , expect  = expectJson ( GetRuleNodesDirectivesResult ruleId ) decodeRuleNodesDirective
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req

getRulesCompliance : Model -> Cmd Msg
getRulesCompliance model =
  let
    req =
      request
        { method  = "GET"
        , headers = []
        , url     = getUrl model [ "compliance", "rules"] [ int "level" 1 ]
        , body    = emptyBody
        , expect  = expectJson GetRulesComplianceResult decodeGetRulesCompliance
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req

getRulesComplianceDetails : RuleId -> Model -> Cmd Msg
getRulesComplianceDetails ruleId model =
  let
    req =
      request
        { method  = "GET"
        , headers = []
        , url     = getUrl model [ "compliance", "rules", ruleId.value ] [ int "level" 10 ]
        , body    = emptyBody
        , expect  = expectJson (GetRuleComplianceResult ruleId) decodeGetRulesComplianceDetails
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req


getNodesList : Model -> Cmd Msg
getNodesList model =
  let
    req =
      request
        { method  = "GET"
        , headers = []
        , url     = getUrl model ["nodes"] []
        , body    = emptyBody
        , expect  = expectJson GetNodesList decodeGetNodesList
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req

saveRuleDetails : Rule -> Bool -> Model -> Cmd Msg
saveRuleDetails ruleDetails creation model =
  let
    (method, url) = if creation then ("PUT",["rules"]) else ("POST", ["rules", ruleDetails.id.value])
    req =
      request
        { method  = method
        , headers = []
        , url     = getUrl model url  (changeRequestParameters model.ui.crSettings)
        , body    = encodeRuleDetails ruleDetails |> jsonBody
        , expect  = expectJson SaveRuleDetails decodeGetRuleDetails
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req

saveDisableAction : Rule -> Model ->  Cmd Msg
saveDisableAction ruleDetails model =
  let
    changeAction = "Disable "
    req =
      request
        { method  = "POST"
        , headers = []
        , url     = getUrl model ["rules", ruleDetails.id.value ] (changeRequestParameters model.ui.crSettings)
        , body    = encodeRuleDetails ruleDetails |> jsonBody
        , expect  = expectJson SaveDisableAction decodeGetRuleDetails
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req

saveCategoryDetails : (Category Rule) -> String -> Bool -> Model -> Cmd Msg
saveCategoryDetails category parentId creation model =
  let
    (method, url) = if creation then ("PUT",["rules","categories"]) else ("POST", ["rules","categories",category.id])
    req =
      request
        { method  = method
        , headers = []
        , url     = getUrl model url []
        , body    = encodeCategoryDetails parentId category |> jsonBody
        , expect  = expectJson SaveCategoryResult decodeGetCategoryDetails
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req

deleteRule : Rule -> Model -> Cmd Msg
deleteRule rule model =
  let
    req =
      request
        { method  = "DELETE"
        , headers = []
        , url     = getUrl model ["rules", rule.id.value ] []
        , body    = emptyBody
        , expect  = expectJson DeleteRule decodeDeleteRuleResponse
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req

deleteCategory : (Category Rule) -> Model -> Cmd Msg
deleteCategory category model =
  let
    req =
      request
        { method  = "DELETE"
        , headers = []
        , url     = getUrl model ["rules","categories", category.id] []
        , body    = emptyBody
        , expect  = expectJson DeleteCategory decodeDeleteCategoryResponse
        , timeout = Nothing
        , tracker = Nothing
        }
  in
   req
