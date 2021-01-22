module JsonDecoder exposing (..)

import DataTypes exposing (..)
import Json.Decode as D exposing (Decoder, andThen, fail, string, succeed, index, bool, oneOf, map2, float)
import Json.Decode.Pipeline exposing (required, hardcoded)
import String exposing (toLower)


-- GENERAL
decodeGetPolicyMode : Decoder String
decodeGetPolicyMode =
  D.at ["data", "settings", "global_policy_mode" ] D.string

decodeCategory : Decoder RulesTreeItem
decodeCategory =
  D.succeed Category
    |> required "id"          D.string
    |> required "name"        D.string
    |> required "categories" (D.list (D.lazy (\_ -> decodeCategory)))
    |> required "rules"      (D.list decodeRule)

decodeRule : Decoder RulesTreeItem
decodeRule =
  D.succeed Rule
    |> required "id"          D.string
    |> required "displayName" D.string
    |> required "categoryId"  D.string
    |> required "enabled"     D.bool

decodeGetRulesTree : Decoder RulesTreeItem
decodeGetRulesTree =
  D.at [ "data" , "ruleCategories" ] decodeCategory

decodeGetRuleDetails : Decoder RuleDetails
decodeGetRuleDetails =
  D.at [ "data" , "rules" ] (index 0 decodeRuleDetails)

decodeRuleDetails : Decoder RuleDetails
decodeRuleDetails =
  D.succeed RuleDetails
    |> required "id"               D.string
    |> required "displayName"      D.string
    |> required "categoryId"       D.string
    |> required "shortDescription" D.string
    |> required "longDescription"  D.string
    |> required "enabled"          D.bool
    |> required "system"           D.bool
    |> required "directives"      (D.list D.string)
    |> required "targets"         (index 0 decodeTargets)

-- DIRECTIVES TAB
decodeGetTechniques : Decoder (List Technique)
decodeGetTechniques =
  D.at ["data", "techniques" ] (D.list decodeTechnique)

decodeTechnique : Decoder Technique
decodeTechnique =
  succeed Technique
    |> required "name"      D.string
    |> required "versions" (D.list D.string)

decodeGetDirectives : Decoder (List Directive)
decodeGetDirectives =
  D.at ["data", "directives" ] (D.list decodeDirective)

decodeDirective : Decoder Directive
decodeDirective =
  succeed Directive
    |> required "id"               D.string
    |> required "displayName"      D.string
    |> required "longDescription"  D.string
    |> required "techniqueName"    D.string
    |> required "techniqueVersion" D.string
    |> required "enabled"          D.bool
    |> required "system"           D.bool
    |> required "policyMode"       D.string


-- GROUPS TAB
decodeGetGroupsTree : Decoder GroupsTreeItem
decodeGetGroupsTree =
  D.at ["data", "groupCategories"] decodeGroupCat

decodeGroupCat : Decoder GroupsTreeItem
decodeGroupCat =
  succeed GroupCat
    |> required "id"          D.string
    |> required "name"        D.string
    |> required "parent"      D.string
    |> required "description" D.string
    |> required "categories" (D.list (D.lazy (\_ -> decodeGroupCat)))
    |> required "groups"     (D.list decodeGroup)

decodeGroup : Decoder GroupsTreeItem
decodeGroup =
  succeed Group
    |> required "id"          D.string
    |> required "displayName" D.string
    |> required "description" D.string
    |> required "nodeIds"    (D.list D.string)
    |> required "dynamic"     D.bool
    |> required "enabled"     D.bool

decodeTargets : Decoder Targets
decodeTargets =
  D.map2 Targets
    (D.at ["include","or"] (D.list D.string))
    (D.at ["exclude","or"] (D.list D.string))
