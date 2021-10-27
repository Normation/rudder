module ViewRulesTable exposing (..)

import DataTypes exposing (..)
import Html exposing (Html, text,  tr, td, i)
import Html.Attributes exposing (class, colspan)
import Html.Events exposing (onClick)
import List.Extra
import List
import String
import NaturalOrdering exposing (compareOn)
import ViewUtils exposing (getCategoryName, getListRules, filterSearch, searchFieldRules, buildTagsTree, badgePolicyMode)
import ComplianceUtils exposing (buildComplianceBar, getAllComplianceValues, getRuleCompliance)

--
-- This file contains all methods to display the Rules table
--

getSortFunction : Model -> Rule -> Rule -> Order
getSortFunction model r1 r2 =
  let
    order = case model.ui.ruleFilters.tableFilters.sortBy of
      Name       -> NaturalOrdering.compare r1.name r2.name
      Parent     ->
        let
          o = NaturalOrdering.compare (getCategoryName model r1.categoryId) (getCategoryName model r2.categoryId)
        in
          case o of
            EQ -> NaturalOrdering.compare r1.name r2.name
            _  -> o

      Status     ->
        let
          r1Status = if r1.enabled then 0 else 1
          r2Status = if r2.enabled then 0 else 1
        in
          compare r1Status r2Status
      Compliance ->
        let
          getCompliance : Maybe RuleCompliance -> Float
          getCompliance rc =
            case rc of
              Just c  ->
                let
                  allComplianceValues = getAllComplianceValues c.complianceDetails
                in
                  if ( allComplianceValues.okStatus + allComplianceValues.nonCompliant + allComplianceValues.error + allComplianceValues.unexpected + allComplianceValues.pending + allComplianceValues.reportsDisabled + allComplianceValues.noReport == 0 ) then
                    -1.0
                  else
                    c.compliance
              Nothing -> -2.0
          r1Compliance = getCompliance (getRuleCompliance model r1.id)
          r2Compliance = getCompliance (getRuleCompliance model r2.id)
        in
          compare r1Compliance r2Compliance
  in
    if model.ui.ruleFilters.tableFilters.sortOrder then
      order
    else
      case order of
        LT -> GT
        EQ -> EQ
        GT -> LT

buildRulesTable : Model -> List(Html Msg)
buildRulesTable model =
  let
    rulesList       = getListRules model.rulesTree
    sortedRulesList = rulesList
      |> List.filter (\r -> filterSearch model.ui.ruleFilters.treeFilters.filter (searchFieldRules r model))
      |> List.sortWith (getSortFunction model)

    rowTable : Rule -> Html Msg
    rowTable r =
      let
        compliance =
            case getRuleCompliance model r.id of
              Just co ->
                buildComplianceBar co.complianceDetails

              Nothing -> text "No report"
      in
            tr[onClick (OpenRuleDetails r.id True)]
            [ td[]
              [ badgePolicyMode model.policyMode r.policyMode
              , text r.name
              , buildTagsTree r.tags
              ]
            , td[][ text (getCategoryName model r.categoryId) ]
            , td[][ text (if r.enabled then "Enabled" else "Disabled") ]
            , td[][ compliance ]
            , td[][ text ""   ]
            ]
  in
    if List.length sortedRulesList > 0 then
      List.map rowTable sortedRulesList
    else
      [ tr[][td [class "empty", colspan 5][i [class "fa fa-exclamation-triangle"][], text "No rules match your filters."]]]
