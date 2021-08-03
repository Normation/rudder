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
            , td[][ text (getCategoryName r.categoryId) ]
            , td[][ text (if r.enabled == True then "Enabled" else "Disabled") ]
            , td[][ compliance ]
            , td[][ text ""   ]
            ]
  in
    List.map rowTable rulesList
