module ViewUtils exposing (..)

import DataTypes exposing (..)
import Html exposing (Html, button, div, i, span, text, h1, h3, h4, ul, li, table, thead, tbody, tr, th, td)
import Html.Attributes exposing (id, class, type_, placeholder, value, for, href, colspan, rowspan, style, selected, disabled, attribute, tabindex)
import Html.Events exposing (onClick, onInput)
import List.Extra
import List
import String exposing ( fromFloat)
import NaturalOrdering exposing (compareOn)
import ApiCalls exposing (..)
import ComplianceUtils exposing (getDirectiveComputedCompliance)

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

--
-- DATATABLES & TREES
--

thClass : TableFilters -> SortBy -> String
thClass tableFilters sortBy =
  if sortBy == tableFilters.sortBy then
    if (tableFilters.sortOrder == True) then
      "sorting_asc"
    else
      "sorting_desc"
  else
    "sorting"

sortTable : Filters -> SortBy -> Filters
sortTable filters sortBy =
  let
    tableFilters = filters.tableFilters
  in
    if sortBy == tableFilters.sortBy then
      {filters | tableFilters = {tableFilters | sortOrder = not tableFilters.sortOrder}}
    else
      {filters | tableFilters = {tableFilters | sortBy = sortBy, sortOrder = True}}

getDirectivesSortFunction : List RuleCompliance -> RuleId -> TableFilters -> Directive -> Directive -> Order
getDirectivesSortFunction rulesCompliance ruleId tableFilter d1 d2 =
  let
    order = case tableFilter.sortBy of
      Name -> NaturalOrdering.compare d1.displayName d2.displayName

      Compliance -> case List.Extra.find (\c -> c.ruleId == ruleId) rulesCompliance of
        Just co ->
          let
            d1Co = case List.Extra.find (\dir -> dir.directiveId == d1.id) co.directives of
              Just c  -> getDirectiveComputedCompliance c
              Nothing -> -2
            d2Co = case List.Extra.find (\dir -> dir.directiveId == d2.id) co.directives of
              Just c  -> getDirectiveComputedCompliance c
              Nothing -> -2
          in
            compare d1Co d2Co

        Nothing -> LT
      _ -> LT
  in
    if tableFilter.sortOrder then
      order
    else
      case order of
        LT -> GT
        EQ -> EQ
        GT -> LT

searchFieldDirectives d =
  [ d.id.value
  , d.displayName
  ]

searchFieldRules r model =
  [ r.id.value
  , r.name
  , r.categoryId
  , getCategoryName model r.categoryId
  ]

searchFieldGroups g =
  [ g.id
  , g.name
  ]

filterSearch : String -> List String -> Bool
filterSearch filterString searchFields =
  let
    -- Join all the fields into one string to simplify the search
    stringToCheck = searchFields
      |> String.join "|"
      |> String.toLower

    searchString  = filterString
      |> String.toLower
      |> String.trim
  in
    String.contains searchString stringToCheck