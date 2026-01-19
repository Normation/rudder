module Rules.JsonDecoder exposing (..)

import Dict exposing (Dict)
import Json.Decode exposing (..)
import Json.Decode.Pipeline exposing (..)
import Json.Decode.Field exposing (require)
import Time.Iso8601
import Time.Iso8601ErrorMsg
import Time.TimeZones exposing (utc)
import Time.ZonedDateTime exposing (ZonedDateTime)
import Tuple

import Rules.DataTypes exposing (..)
import Compliance.DataTypes exposing (..)
import Compliance.JsonDecoder exposing (decodeComplianceDetails, decodeSkippedDirectiveDetails)
import Ui.Datatable exposing (Category, SubCategories(..))

-- GENERAL
decodeGetPolicyMode : Decoder String
decodeGetPolicyMode =
  at ["data", "settings", "global_policy_mode" ] string

decodeCategoryWithLeaves : String -> String -> String -> Decoder a -> Decoder ((Category a), List a)
decodeCategoryWithLeaves idIdentifier categoryIdentifier elemIdentifier elemDecoder =
  map5 (\id name description subCats elems -> (Category id name description (SubCategories (List.map Tuple.first subCats)) elems, List.concat [ elems, List.concatMap Tuple.second subCats ] ))
    (field idIdentifier  string)
    (field "name"        string)
    (field "description" string)
    (field categoryIdentifier (list (lazy (\_ -> (decodeCategoryWithLeaves idIdentifier categoryIdentifier elemIdentifier elemDecoder)))))
    (field elemIdentifier (list elemDecoder))

decodeCategory : String -> String -> String -> Decoder a -> Decoder (Category a)
decodeCategory idIdentifier categoryIdentifier elemIdentifier elemDecoder =
  succeed Category
    |> required idIdentifier  string
    |> required "name"        string
    |> required "description" string
    |> required categoryIdentifier (map SubCategories  (list (lazy (\_ -> (decodeCategory idIdentifier categoryIdentifier elemIdentifier elemDecoder)))))
    |> required elemIdentifier      (list elemDecoder)

decodeCategoryGroupTarget : Decoder (Category Group)
decodeCategoryGroupTarget =
  succeed ( \id name description categories groups targets ->
    let
      elems = if id == "GroupRoot" then groups else (List.append groups targets)
    in
      Category id name description categories elems
    )
    |> required "id"          string
    |> required "name"        string
    |> required "description" string
    |> required "categories"  (map SubCategories  (list (lazy (\_ -> decodeCategoryGroupTarget))))
    |> required "groups"      (list decodeGroup)
    |> required "targets"     (list decodeTarget)

decodeCategoryDetails : Decoder (Category Rule)
decodeCategoryDetails =
  succeed Category
    |> required "id"          string
    |> required "name"        string
    |> required "description" string
    |> hardcoded (SubCategories [])
    |> hardcoded []

decodeGetRulesTree =
  at [ "data" , "ruleCategories" ] (decodeCategory "id" "categories" "rules" decodeRule)

decodeGetRuleDetails : Decoder Rule
decodeGetRuleDetails =
  at [ "data" , "rules" ] (index 0 decodeRule)

decodeGetCategoryDetails : Decoder (Category Rule)
decodeGetCategoryDetails =
  at [ "data" , "ruleCategories" ] decodeCategoryDetails

decodeRule : Decoder Rule
decodeRule =
  succeed Rule
    |> required "id"              (map RuleId string)
    |> required "displayName"      string
    |> required "categoryId"       string
    |> required "shortDescription" string
    |> required "longDescription"  string
    |> required "enabled"          bool
    |> required "system"           bool
    |> required "directives"      (list (map DirectiveId string))
    |> required "targets"         (list decodeTargets)
    |> required "policyMode"       string
    |> required "status"           decodeStatus
    |> required "tags"            (list (keyValuePairs string) |> andThen toTags)
    |> optional "changeRequestId" (map Just string) Nothing

toTags : List (List ( String, String )) -> Decoder (List Tag)
toTags lst =
  let
    concatList = List.concat lst
  in
    succeed
      ( List.map (\t -> Tag (Tuple.first t) (Tuple.second t)) concatList )

decodeDeleteRuleResponse : Decoder (RuleId,String)
decodeDeleteRuleResponse =
  at ["data", "rules" ](index 0
  ( succeed Tuple.pair
     |> required "id" (map RuleId string)
     |> required "displayName" string
  ))

decodeIdCategory: Decoder (String, String)
decodeIdCategory =
  at ["ruleCategories" ](
  ( succeed Tuple.pair
     |> required "id" string
     |> required "name" string
  ))

decodeDeleteCategoryResponse : Decoder (String, String)
decodeDeleteCategoryResponse =
  at ["data", "rulesCategories" ](index 0 decodeIdCategory)

decodeStatus : Decoder RuleStatus
decodeStatus =
  succeed RuleStatus
    |> required "value"    string
    |> optional "details" (maybe string) Nothing


decodeRuleNodesDirective : Decoder RuleNodesDirectives
decodeRuleNodesDirective =
  at [ "data" ](
      ( succeed RuleNodesDirectives
          |> required "id"                 string
          |> required "numberOfNodes"      int
          |> required "numberOfDirectives" int
      )
  )
-- COMPLIANCE
decodeGetRulesCompliance : Decoder (List RuleComplianceGlobal)
decodeGetRulesCompliance =
  at [ "data" , "rules" ] (list decodeRuleCompliance)


decodeGetRulesComplianceDetails : Decoder RuleCompliance
decodeGetRulesComplianceDetails =
  at [ "data" , "rules" ] (oneOrMore (\a _ -> a ) decodeRuleComplianceDetails)


decodeRuleCompliance : Decoder RuleComplianceGlobal
decodeRuleCompliance =
  succeed RuleComplianceGlobal
    |> required "id"         (map RuleId string)
    |> required "compliance" float
    |> required "complianceDetails" decodeComplianceDetails


decodeRuleComplianceDetails  : Decoder RuleCompliance
decodeRuleComplianceDetails =
  succeed RuleCompliance
    |> required "id"         (map RuleId string)
    |> required "mode"       string
    |> required "compliance" float
    |> required "complianceDetails" decodeComplianceDetails
    |> required "directives" (list (decodeDirectiveCompliance "nodes" (list decodeNodeCompliance)) )
    |> required "nodes" (list decodeRuleComplianceByNode )


decodeRuleComplianceByNode  : Decoder NodeCompliance
decodeRuleComplianceByNode =
  succeed NodeCompliance
    |> required "id"         (map NodeId string)
    |> required "name"       string
    |> required "compliance" float
    |> required "complianceDetails" decodeComplianceDetails
    |> required "directives" (list (decodeDirectiveCompliance "values" decodeValues ))


decodeBlockCompliance :  String -> Decoder (List a) -> () -> Decoder (BlockCompliance a)
decodeBlockCompliance elem decoder _ =
  require "name" string <| \name ->
  require "compliance" float <| \compliance ->
  require "complianceDetails" decodeComplianceDetails <| \details ->
  require "components" (list (decodeComponentCompliance elem decoder))   <| \components ->
    succeed ({ component = name, compliance = compliance, complianceDetails = details, components =  components } )


decodeComponentValueCompliance : String -> Decoder (List a) -> Decoder (ComponentValueCompliance a)
decodeComponentValueCompliance elem decoder =
  succeed ComponentValueCompliance
    |> required "name"       string
    |> required "compliance" float
    |> required "complianceDetails" decodeComplianceDetails
    |> required elem decoder

decodeComponentCompliance : String -> Decoder (List a) -> Decoder (ComponentCompliance a)
decodeComponentCompliance elem decoder =
  oneOf [
     map  (\b -> Block b) <| decodeBlockCompliance elem decoder ()
  ,  map  (\v -> Value v) <| decodeComponentValueCompliance elem decoder
  ]

decodeDirectiveCompliance  : String -> Decoder (List a) ->  Decoder (DirectiveCompliance a)
decodeDirectiveCompliance elem decoder =
  succeed DirectiveCompliance
    |> required "id"         (map DirectiveId string)
    |> required "name"       string
    |> required "compliance" float
    |> required "complianceDetails" decodeComplianceDetails
    |> optional "skippedDetails" (maybe decodeSkippedDirectiveDetails) Nothing
    |> required "components" (list (decodeComponentCompliance  elem decoder )  )

decodeRuleChanges: Decoder (Dict String (List Changes))
decodeRuleChanges =
  at [ "data" ] (dict (list decodeChanges))

decodeChanges : Decoder Changes
decodeChanges =
  succeed Changes
    |> required "start" decodeTime
    |> required "end" decodeTime
    |> required "changes" float

decodeTime : Decoder ZonedDateTime
decodeTime =
  andThen (\d ->
            case d of
              Ok r -> succeed r
              Err e -> fail (String.join "\n" <| List.map (Time.Iso8601ErrorMsg.renderText "" ) e)
          ) (map (Time.Iso8601.toZonedDateTime utc) string)


decodeValues : Decoder ( List ValueLine)
decodeValues =
    (list (field "value" string |> andThen (\v -> field "reports" (list (decodeValueCompliance v)))))
    |> map  (List.concatMap identity )

decodeValueCompliance : String -> Decoder ValueLine
decodeValueCompliance v =
  succeed (ValueLine v)
    |> optional "message" string ""
    |> required "status"  string


decodeNodeCompliance : Decoder NodeValueCompliance
decodeNodeCompliance =
  succeed NodeValueCompliance
    |> required "id"     (map NodeId string)
    |> required "name"    string
    |> required "compliance" float
    |> required "complianceDetails" decodeComplianceDetails
    |> required "values" decodeValues


-- DIRECTIVES TAB
decodeGetTechniques : Decoder (List Technique)
decodeGetTechniques =
  at ["data", "techniques" ] (list decodeTechnique)


decodeGetDirectives : Decoder (List Directive)
decodeGetDirectives =
  at ["data", "directives" ] (list decodeDirective)

decodeDirective : Decoder Directive
decodeDirective =
  succeed Directive
    |> required "id"              (map DirectiveId string)
    |> required "displayName"      string
    |> required "longDescription"  string
    |> required "techniqueName"    string
    |> required "techniqueVersion" string
    |> required "enabled"          bool
    |> required "system"           bool
    |> required "policyMode"       string
    |> required "tags"            (list (keyValuePairs string) |> andThen toTags)

decodeGetTechniquesTree : Decoder (Category Technique, List Technique)
decodeGetTechniquesTree =
  at ["data", "directives"] (index 0 (decodeCategoryWithLeaves "name" "subCategories" "techniques" decodeTechnique))

decodeTechnique : Decoder Technique
decodeTechnique =
  succeed Technique
    |> required "name"          string
    |> required "directives" (list decodeDirective)

-- GROUPS TAB
decodeGetGroupsTree : Decoder (Category Group)
decodeGetGroupsTree =
  at ["data", "groupCategories"] decodeCategoryGroupTarget


decodeGroup : Decoder Group
decodeGroup =
  succeed Group
    |> required "id"          string
    |> required "displayName" string
    |> required "description" string
    |> required "nodeIds"    (list string)
    |> required "dynamic"     bool
    |> required "enabled"     bool
    |> required "target"      string

decodeTarget : Decoder Group
decodeTarget =
  succeed Group
    |> required "id"          string
    |> required "displayName" string
    |> required "description" string
    |> hardcoded []
    |> hardcoded True
    |> required "enabled"     bool
    |> required "target"      string

decodeComposition : Decoder RuleTarget
decodeComposition =
  succeed Composition
    |> required "include" (lazy (\_ -> decodeTargets))
    |> required "exclude" (lazy (\_ -> decodeTargets))

decodeAnd: Decoder RuleTarget
decodeAnd =
  succeed And
    |> required "and" (list (lazy (\_ -> decodeTargets)))

decodeOr: Decoder RuleTarget
decodeOr =
  succeed Or
    |> required "or" (list (lazy (\_ -> decodeTargets)))

decodeTargets : Decoder RuleTarget
decodeTargets =
  oneOf
  [ lazy (\_ -> decodeComposition)
  , lazy (\_ -> decodeAnd)
  , lazy (\_ -> decodeOr)
  , map
    (\s ->
      if String.startsWith "group:" s then
        NodeGroupId (String.dropLeft 6 s)
      else if String.startsWith "node:" s then
        Node (String.dropLeft 5 s)
      else Special s
    ) string
  ]

decodeGetNodesList : Decoder (List NodeInfo)
decodeGetNodesList =
  at ["data", "nodes" ] (list decodeNodeInfo)

decodeNodeInfo : Decoder NodeInfo
decodeNodeInfo =
  succeed NodeInfo
    |> required "id"          string
    |> required "hostname"    string
    |> required "description" string
    |> required "policyMode"  string


decodeRepairedReports: Decoder (List RepairedReport)
decodeRepairedReports =
   at ["data"] (list decodeRepairedReport)

decodeRepairedReport: Decoder RepairedReport
decodeRepairedReport =
  succeed RepairedReport
    |> required "directiveId" (map DirectiveId string)
    |> required "nodeId"      (map NodeId string)
    |> required "component"   string
    |> required "value"   string
    |> required "executionTimestamp" decodeTime
    |> required "executionDate" decodeTime
    |> required "message"   string