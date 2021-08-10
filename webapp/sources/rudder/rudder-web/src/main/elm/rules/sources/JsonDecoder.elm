module JsonDecoder exposing (..)

import DataTypes exposing (..)
import Json.Decode as D exposing (Decoder, andThen, bool, field, float, index, map, map2, map4, oneOf, string, succeed)
import Json.Decode.Pipeline exposing (..)
import Tuple

-- GENERAL
decodeGetPolicyMode : Decoder String
decodeGetPolicyMode =
  D.at ["data", "settings", "global_policy_mode" ] D.string


decodeCategoryWithLeaves : String -> String -> String -> Decoder a -> Decoder ((Category a), List a)
decodeCategoryWithLeaves idIdentifier categoryIdentifier elemIdentifier elemDecoder =
  D.map5 (\id name description subCats elems -> (Category id name description (SubCategories (List.map Tuple.first subCats)) elems, List.concat [ elems, List.concatMap Tuple.second subCats ] ))
     (field idIdentifier  D.string)
     (field "name"        D.string)
     (field "description" D.string)
     (field categoryIdentifier   (D.list (D.lazy (\_ -> (decodeCategoryWithLeaves idIdentifier categoryIdentifier elemIdentifier elemDecoder)))))
     (field elemIdentifier      (D.list elemDecoder))

decodeCategory : String -> String -> String -> Decoder a -> Decoder (Category a)
decodeCategory idIdentifier categoryIdentifier elemIdentifier elemDecoder =
  succeed Category
    |> required idIdentifier  D.string
    |> required "name"        D.string
    |> required "description" D.string
    |> required categoryIdentifier (D.map SubCategories  (D.list (D.lazy (\_ -> (decodeCategory idIdentifier categoryIdentifier elemIdentifier elemDecoder)))))
    |> required elemIdentifier      (D.list elemDecoder)


decodeCategoryDetails : Decoder (Category Rule)
decodeCategoryDetails =
  succeed Category
    |> required "id"          D.string
    |> required "name"        D.string
    |> required "description" D.string
    |> hardcoded (SubCategories [])
    |> hardcoded []

decodeGetRulesTree =
  D.at [ "data" , "ruleCategories" ] (decodeCategory "id" "categories" "rules" decodeRule)

decodeGetRuleDetails : Decoder Rule
decodeGetRuleDetails =
  D.at [ "data" , "rules" ] (index 0 decodeRule)

decodeGetCategoryDetails : Decoder (Category Rule)
decodeGetCategoryDetails =
  D.at [ "data" , "ruleCategories" ] decodeCategoryDetails

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

decodeDeleteRuleResponse : Decoder (RuleId,String)
decodeDeleteRuleResponse =
  D.at ["data", "rules" ](index 0
  ( succeed Tuple.pair
     |> required "id" (map RuleId string)
     |> required "displayName" string
  ))


-- COMPLIANCE
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

decodeGetTechniquesTree : Decoder (Category Technique, List Technique)
decodeGetTechniquesTree =
  D.at ["data", "directives"] (index 0 (decodeCategoryWithLeaves "name" "subCategories" "techniques" decodeTechnique))

decodeTechnique : Decoder Technique
decodeTechnique =
  succeed Technique
    |> required "name"          D.string
    |> required "directives" (D.list decodeDirective)

-- GROUPS TAB
decodeGetGroupsTree : Decoder (Category Group)
decodeGetGroupsTree =
  D.at ["data", "groupCategories"] (decodeCategory "id" "categories" "groups" decodeGroup)


decodeGroup : Decoder Group
decodeGroup =
  succeed Group
    |> required "id"          D.string
    |> required "displayName" D.string
    |> required "description" D.string
    |> required "nodeIds"    (D.list D.string)
    |> required "dynamic"     D.bool
    |> required "enabled"     D.bool



decodeComposition : Decoder RuleTarget
decodeComposition =
  succeed Composition
    |> required "include" (D.lazy (\_ -> decodeTargets))
    |> required "exclude" (D.lazy (\_ -> decodeTargets))

decodeAnd: Decoder RuleTarget
decodeAnd =
  succeed And
    |> required "and" (D.list (D.lazy (\_ -> decodeTargets)))

decodeOr: Decoder RuleTarget
decodeOr =
  succeed Or
    |> required "or" (D.list (D.lazy (\_ -> decodeTargets)))

decodeTargets : Decoder RuleTarget
decodeTargets =

  D.oneOf [
     D.lazy (\_ -> decodeComposition)
   , D.lazy (\_ -> decodeAnd)
   , D.lazy (\_ -> decodeOr)
   , map (\s ->
           if String.startsWith "group:" s then
             NodeGroupId s
           else if String.startsWith "node:" s then
                             Node s
           else Special s
         )

    string

  ]