module Rules.ViewRulesTable exposing (..)

import Html exposing (Html, text,  tr, td, i, span)
import Html.Attributes exposing (class, colspan, attribute, title)
import Html.Events exposing (onClick)
import List
import String
import NaturalOrdering

import Rules.DataTypes exposing (..)
import Rules.ViewUtils exposing (..)

import Compliance.Utils exposing (getAllComplianceValues, defaultComplianceFilter)
import Compliance.Html exposing (buildComplianceBar)

--
-- This file contains all methods to display the Rules table
--

getSortFunction : Model -> Rule -> Rule -> Order
getSortFunction model r1 r2 =
  let
    order = case model.ui.ruleFilters.tableFilters.sortBy of
      Name       -> NaturalOrdering.compare r1.name r2.name
      RuleChanges->
        let
          getChanges = \r -> countRecentChanges r.id model.changes
          r1Changes = getChanges r1
          r2Changes = getChanges r2
        in
          compare r1Changes r2Changes
      Parent     ->
        let
          o = NaturalOrdering.compare (getCategoryName model r1.categoryId) (getCategoryName model r2.categoryId)
        in
          case o of
            EQ -> NaturalOrdering.compare r1.name r2.name
            _  -> o

      Status -> NaturalOrdering.compare r1.status.value r2.status.value
      Compliance ->
        let
          getCompliance : Maybe RuleComplianceGlobal -> Float
          getCompliance rc =
            case rc of
              Just c  ->
                let
                  allComplianceValues = getAllComplianceValues c.complianceDetails
                in
                  if ( allComplianceValues.okStatus.value + allComplianceValues.nonCompliant.value + allComplianceValues.error.value + allComplianceValues.unexpected.value + allComplianceValues.pending.value + allComplianceValues.reportsDisabled.value + allComplianceValues.noReport.value == 0 ) then
                    -1.0
                  else
                    c.compliance
              Nothing -> -2.0
          r1Compliance = getCompliance (getRuleCompliance model r1.id)
          r2Compliance = getCompliance (getRuleCompliance model r2.id)
        in
          compare r1Compliance r2Compliance
  in
    if model.ui.ruleFilters.tableFilters.sortOrder == Asc then
      order
    else
      case order of
        LT -> GT
        EQ -> EQ
        GT -> LT

buildRulesTable : Model -> List Rule -> List(Html Msg)
buildRulesTable model rules =
  let
    rulesList       = rules
    sortedRulesList = rulesList
      |> List.filter (\r -> filterSearch model.ui.ruleFilters.treeFilters.filter (searchFieldRules r model))
      |> List.filter (\r -> filterTags r.tags model.ui.ruleFilters.treeFilters.tags)
      |> List.sortWith (getSortFunction model)

    rowTable : Rule -> Html Msg
    rowTable r =
      let
        categoryName = text (getCategoryName model r.categoryId)

        compliance   =
          case getRuleCompliance model r.id of
            Just co ->
              buildComplianceBar defaultComplianceFilter co.complianceDetails
            Nothing -> text "No report"

        changes = text (String.fromFloat (countRecentChanges r.id model.changes))

        displayStatus : Html Msg
        displayStatus =
          let
            status = text r.status.value
          in
            case r.status.details of
              Just ms ->
               span[ class "disabled", attribute "data-bs-toggle" "tooltip", attribute "data-bs-placement" "top", title (buildTooltipContent "Reason(s)" ms)]
               [ status
               , i[class "fa fa-info-circle"][]
               ]

              Nothing -> span[][ status ]
      in
            tr[onClick (OpenRuleDetails r.id True)]
            [ td[]
              [ badgePolicyMode model.policyMode r.policyMode
              , text r.name
              , buildTagsTree r.tags
              ]
            , td[][ categoryName  ]
            , td[][ displayStatus ]
            , td[][ compliance    ]
            , td[][ changes       ]
            ]
  in
    if List.length sortedRulesList > 0 then
      List.map rowTable sortedRulesList
    else
      [ tr[][td [class "empty", colspan 5][i [class "fa fa-exclamation-triangle"][], text "No rules match your filters."]]]
