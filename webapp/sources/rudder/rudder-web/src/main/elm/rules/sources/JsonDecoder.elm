module JsonDecoder exposing (..)

import DataTypes exposing (..)
import Json.Decode as D exposing (Decoder, andThen, fail, string, succeed, index, bool, oneOf, map, map2, float)
import Json.Decode.Pipeline exposing (..)
import String exposing (toLower)
import Dict exposing (Dict)
import Tuple

-- GENERAL
decodeGetPolicyMode : Decoder String
decodeGetPolicyMode =
  D.at ["data", "settings", "global_policy_mode" ] D.string

decodeCategory : Decoder a -> Decoder (Category a)
decodeCategory elemDecoder =
  succeed Category
    |> required "id"          D.string
    |> required "name"        D.string
    |> required "categories" (D.map SubCategories  (D.list (D.lazy (\_ -> (decodeCategory elemDecoder)))))
    |> required "rules"      (D.list elemDecoder)


decodeGetRulesTree =
  D.at [ "data" , "ruleCategories" ] (decodeCategory decodeRule)

decodeGetRuleDetails : Decoder Rule
decodeGetRuleDetails =
  D.at [ "data" , "rules" ] (index 0 decodeRule)

decodeRule : Decoder Rule
decodeRule =
  D.succeed Rule
    |> required "id"          (D.map RuleId D.string)
    |> required "displayName"      D.string
    |> required "categoryId"       D.string
    |> required "shortDescription" D.string
    |> required "longDescription"  D.string
    |> required "enabled"          D.bool
    |> required "system"           D.bool
    |> required "directives"      (D.list (D.map DirectiveId D.string))
    |> required "targets"         (D.list decodeTargets)
    |> required "tags"            (D.list (D.keyValuePairs D.string) |> andThen toTags)

toTags : List (List ( String, String )) -> Decoder (List Tag)
toTags lst =
  let
    concatList = List.concat lst
  in
    D.succeed
      ( List.map (\t -> Tag (Tuple.first t) (Tuple.second t)) concatList )

decodeGetRulesCompliance : Decoder (List RuleCompliance)
decodeGetRulesCompliance =
  D.at [ "data" , "rules" ] (D.list decodeRuleCompliance)

decodeRuleCompliance : Decoder RuleCompliance
decodeRuleCompliance =
  succeed RuleCompliance
    |> required "id"         (D.map RuleId D.string)
    |> required "mode"       D.string
    |> required "compliance" D.float
    |> required "complianceDetails" decodeComplianceDetails
    |> required "directives" (D.list decodeDirectiveCompliance)
decodeDirectiveCompliance : Decoder DirectiveCompliance
decodeDirectiveCompliance =
  succeed DirectiveCompliance
    |> required "id"         (D.map DirectiveId D.string)
    |> required "compliance" D.float
    |> required "complianceDetails" decodeComplianceDetails
    |> required "components" (D.list decodeComponentCompliance)
decodeComponentCompliance : Decoder ComponentCompliance
decodeComponentCompliance =
  succeed ComponentCompliance
    |> required "name"       D.string
    |> required "compliance" D.float
    |> required "complianceDetails" decodeComplianceDetails
    |> required "nodes" (D.list decodeNodeCompliance)
decodeValueCompliance : Decoder ValueCompliance
decodeValueCompliance =
  succeed ValueCompliance
    |> required "value"      D.string
    |> required "reports" (D.list decodeReport)
decodeReport : Decoder Report
decodeReport =
  succeed Report
    |> required "status"      D.string
    |> optional "message"    (D.maybe  D.string) Nothing

decodeNodeCompliance : Decoder NodeCompliance
decodeNodeCompliance =
  succeed NodeCompliance
    |> required "id"         (D.map NodeId D.string)
    |> required "values" (D.list decodeValueCompliance)

decodeComplianceDetails : Decoder ComplianceDetails
decodeComplianceDetails =
  succeed ComplianceDetails
    |> optional "successNotApplicable"       (map Just D.float) Nothing
    |> optional "successAlreadyOK"           (map Just D.float) Nothing
    |> optional "successRepaired"            (map Just D.float) Nothing
    |> optional "error"                      (map Just D.float) Nothing
    |> optional "auditCompliant"             (map Just D.float) Nothing
    |> optional "auditNonCompliant"          (map Just D.float) Nothing
    |> optional "auditError"                 (map Just D.float) Nothing
    |> optional "auditNotApplicable"         (map Just D.float) Nothing
    |> optional "unexpectedUnknownComponent" (map Just D.float) Nothing
    |> optional "unexpectedMissingComponent" (map Just D.float) Nothing
    |> optional "noReport"                   (map Just D.float) Nothing
    |> optional "reportsDisabled"            (map Just D.float) Nothing
    |> optional "applying"                   (map Just D.float) Nothing
    |> optional "badPolicyMode"              (map Just D.float) Nothing

-- DIRECTIVES TAB
decodeGetTechniques : Decoder (List Technique)
decodeGetTechniques =
  D.at ["data", "techniques" ] (D.list decodeTechnique)



decodeGetDirectives : Decoder (List Directive)
decodeGetDirectives =
  D.at ["data", "directives" ] (D.list decodeDirective)

decodeDirective : Decoder Directive
decodeDirective =
  succeed Directive
    |> required "id"               (D.map DirectiveId D.string)
    |> required "displayName"      D.string
    |> required "longDescription"  D.string
    |> required "techniqueName"    D.string
    |> required "techniqueVersion" D.string
    |> required "enabled"          D.bool
    |> required "system"           D.bool
    |> required "policyMode"       D.string

decodeGetTechniquesTree : Decoder (Category Technique)
decodeGetTechniquesTree =
  D.at ["data", "directives"] (index 0 (decodeCategory decodeTechnique))

decodeTechnique : Decoder Technique
decodeTechnique =
  succeed Technique
    |> required "name"          D.string
    |> hardcoded []

-- GROUPS TAB
decodeGetGroupsTree : Decoder (Category Group)
decodeGetGroupsTree =
  D.at ["data", "groupCategories"] (decodeCategory decodeGroup)


decodeGroup : Decoder Group
decodeGroup =
  succeed Group
    |> required "id"          D.string
    |> required "displayName" D.string
    |> required "description" D.string
    |> required "nodeIds"    (D.list D.string)
    |> required "dynamic"     D.bool
    |> required "enabled"     D.bool

decodeTargets : Decoder RuleTarget
decodeTargets =

  D.oneOf [
    map Special string
  ]