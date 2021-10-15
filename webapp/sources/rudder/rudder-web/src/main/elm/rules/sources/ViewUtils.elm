module ViewUtils exposing (..)

import DataTypes exposing (..)
import Html exposing (Html, button, div, i, span, text, h1, h3, h4, ul, li, table, thead, tbody, tr, th, td, b)
import Html.Attributes exposing (id, class, type_, placeholder, value, for, href, colspan, rowspan, style, selected, disabled, attribute, tabindex)
import Html.Events exposing (onClick, onInput)
import List.Extra
import List
import String exposing ( fromFloat)
import NaturalOrdering exposing (compareOn)
import ApiCalls exposing (..)
import ComplianceUtils exposing (getDirectiveComputedCompliance, getNodeComputedCompliance)


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

getParentCategoryId : List (Category a) -> String -> String
getParentCategoryId categories categoryId =
  let
    findSubCategory : String -> Category a -> Bool
    findSubCategory catId cat =
      case List.Extra.find (\c -> c.id == catId) (getSubElems cat) of
        Just _  -> True
        Nothing -> False
  in
    case List.Extra.find (\c -> (findSubCategory categoryId c)) categories of
      Just ct -> ct.id
      Nothing -> "rootRuleCategory"
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

getNodesSortFunction : TableFilters -> List NodeInfo -> NodeComplianceByNode -> NodeComplianceByNode -> Order
getNodesSortFunction tableFilter nodes n1 n2 =
  let
    order = case tableFilter.sortBy of
      Name ->
        let
         n1Name = case List.Extra.find (\n -> n.id == n1.nodeId.value) nodes of
           Just nn -> nn.hostname
           Nothing -> ""

         n2Name = case List.Extra.find (\n -> n.id == n2.nodeId.value) nodes of
           Just nn -> nn.hostname
           Nothing -> ""
        in
          NaturalOrdering.compare n1Name n2Name

      Compliance ->
        let
          n1Co = getNodeComputedCompliance n1
          n2Co = getNodeComputedCompliance n2
        in
          compare n1Co n2Co

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

searchFieldNodes n nodes=
  [ n.nodeId.value
  , case List.Extra.find (\node -> node.id == n.nodeId.value) nodes of
    Just nn -> nn.hostname
    Nothing -> ""
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


-- TAGS DISPLAY
buildHtmlStringTag : Tag -> String
buildHtmlStringTag tag =
  let
    tagOpen  = "<span class='tags-label'>"
    tagIcon  = "<i class='fa fa-tag'></i>"
    tagKey   = "<span class='tag-key'>"   ++ tag.key   ++ "</span>"
    tagSep   = "<span class='tag-separator'>=</span>"
    tagVal   = "<span class='tag-value'>" ++ tag.value ++ "</span>"
    tagClose = "</span>"
  in
    tagOpen ++ tagIcon ++ tagKey ++ tagSep ++ tagVal ++ tagClose


buildTagsTree : List Tag -> Html Msg
buildTagsTree tags =
  let
    nbTags = List.length tags

    tooltipContent : List Tag -> String
    tooltipContent listTags =
      "<h4 class='tags-tooltip-title'>Tags <span class='tags-label'><i class='fa fa-tags'></i><b>"++ (String.fromInt nbTags) ++"</b></span></h4><div class='tags-list'>" ++ (String.concat (List.map (\tt -> buildHtmlStringTag tt) listTags)) ++ "</div>"
  in
    if (nbTags > 0) then
      span [class "tags-label bs-tooltip", attribute "data-toggle" "tooltip", attribute "data-placement" "top", attribute "data-container" "body", attribute "data-html" "true", attribute "data-original-title" (tooltipContent tags)]
      [ i [class "fa fa-tags"][]
      , b[][text (String.fromInt nbTags)]
      ]
    else
      text ""

buildTagsList : List Tag -> Html Msg
buildTagsList tags =
  let
    nbTags = List.length tags

    spanTags : Tag -> Html Msg
    spanTags t =
      span [class "tags-label"]
      [ i [class "fa fa-tag"][]
      , span [class "tag-key"       ][(if (String.isEmpty t.key  ) then i [class "fa fa-asterisk"][] else span[][text t.key  ])]
      , span [class "tag-separator" ][text "="]
      , span [class "tag-value"     ][(if (String.isEmpty t.value) then i [class "fa fa-asterisk"][] else span[][text t.value])]
      ]

    tooltipContent : List Tag -> String
    tooltipContent listTags =
      "<h4 class='tags-tooltip-title'>Tags <span class='tags-label'><i class='fa fa-tags'></i><b><i>"++ (String.fromInt (nbTags - 2)) ++" more</i></b></span></h4><div class='tags-list'>" ++ (String.concat (List.map (\tt -> buildHtmlStringTag tt) listTags)) ++ "</div>"
  in
    if (nbTags > 0) then
      if (nbTags > 2) then
        span[class "tags-list"](
          List.append (List.map (\t -> spanTags t) (List.take 2 tags)) [
            span [class "tags-label bs-tooltip", attribute "data-toggle" "tooltip", attribute "data-placement" "top", attribute "data-container" "body", attribute "data-html" "true", attribute "data-original-title" (tooltipContent (List.drop 2 tags))]
            [ i [class "fa fa-tags"][]
            , b [][ i[][text (String.fromInt (nbTags - 2)), text " more"]]
            ]
          ]
        )
      else
        span[class "tags-list"](List.map (\t -> spanTags t) tags)
    else
      text ""


badgePolicyMode : String -> String -> Html Msg
badgePolicyMode globalPolicyMode policyMode =
  let
    mode = if policyMode == "default" then globalPolicyMode else policyMode
  in
    span [class ("rudder-label label-sm label-" ++ mode)][]