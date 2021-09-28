module ViewRulesTable exposing (..)

import DataTypes exposing (..)
import Html exposing (Html, button, div, i, span, text, h1, h4, ul, li, input, a, p, form, label, textarea, select, option, table, thead, tbody, tr, th, td, small)
import Html.Attributes exposing (id, class, type_, placeholder, value, for, href, colspan, rowspan, style, selected, disabled, attribute)
import Html.Events exposing (onClick, onInput)
import List.Extra
import List
import String exposing ( fromFloat)
import NaturalOrdering exposing (compareOn)
import ApiCalls exposing (..)
import ViewUtilsCompliance exposing (buildComplianceBar)
--
-- This file contains all methods to display the Rules table
--


getListRules : Category Rule -> List (Rule)
getListRules r = getAllElems r

getListCategories : Category Rule  -> List (Category Rule)
getListCategories r = getAllCats r

getCategoryName : Model -> String -> String
getCategoryName model id =
  let
    cat = List.Extra.find (.id >> (==) id  ) (getListCategories model.rulesTree)
  in
    case cat of
      Just c -> c.name
      Nothing -> id

getRuleCompliance : Model -> RuleId -> Maybe RuleCompliance
getRuleCompliance model rId =
  List.Extra.find (\c -> c.ruleId == rId) model.rulesCompliance

getSortFunction : Model -> Rule -> Rule -> Order
getSortFunction model r1 r2 =
  let
    order = case model.ui.ruleFilters.sortBy of
      Name       -> compare r1.name r2.name
      Parent     ->
        let
          o = compare (getCategoryName model r1.categoryId) (getCategoryName model r2.categoryId)
        in
          case o of
            EQ -> compare r1.name r2.name
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
    if model.ui.ruleFilters.sortOrder then
      order
    else
      case order of
        LT -> GT
        EQ -> EQ
        GT -> LT

buildRulesTable : Model -> List(Html Msg)
buildRulesTable model =
  let
    rulesList      = getListRules model.rulesTree
    categoriesList = getListCategories model.rulesTree

    getCategoryName : String -> String
    getCategoryName id =
      let
        cat = List.Extra.find (.id >> (==) id  ) categoriesList
      in
        case cat of
          Just c -> c.name
          Nothing -> id

    rowTable : Rule -> Html Msg
    rowTable r =
      let
        compliance =
            case List.Extra.find (\c -> c.ruleId == r.id) model.rulesCompliance of
              Just co ->
                let
                  complianceDetails = co.complianceDetails
                in
                  buildComplianceBar complianceDetails

              Nothing -> text "No report"
      in
            tr[onClick (OpenRuleDetails r.id)]
            [ td[][ text r.name ]
            , td[][ text (getCategoryName model r.categoryId) ]
            , td[][ text (if r.enabled then "Enabled" else "Disabled") ]
            , td[][ compliance ]
            , td[][ text ""   ]
            ]
  in
    List.map rowTable rulesList
