module Groups.ViewUtils exposing (..)

import Dict
import Maybe
import Maybe.Extra
import NaturalOrdering
import List.Extra

import Groups.DataTypes exposing (..)
import Compliance.Utils exposing (getAllComplianceValues)
import GroupCompliance.DataTypes exposing (GroupId)
import Html exposing (Html, div, span, table, tbody, td, th, thead, tr)
import Html.Attributes exposing (class, style)
import Html exposing (ul)
import Html exposing (li)
import Html exposing (i)

getAllCats: Category a -> List (Category a)
getAllCats category =
  let
    subElems = case category.subElems of SubCategories l -> l
  in
    category :: (List.concatMap getAllCats subElems)

getCategoryName : Model -> String -> String
getCategoryName model id =
  let
    cat = List.Extra.find (.id >> (==) id  ) (getAllCats model.groupsTree)
  in
    case cat of
      Just c -> c.name
      Nothing -> id

getGroupCompliance : Model -> GroupId -> Maybe GroupComplianceSummary
getGroupCompliance model rId =
  Dict.get rId.value model.groupsCompliance

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

searchFieldGroups : Group -> Model -> List String
searchFieldGroups g model =
  [ g.id.value
  , g.name
  ] ++ Maybe.Extra.toList g.category ++ Maybe.Extra.toList (Maybe.map (getCategoryName model) g.category)

getSortFunction : Model -> Group -> Group -> Order
getSortFunction model g1 g2 =
  let
    getCompliance : Maybe ComplianceSummaryValue -> Float
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
    groupGlobalCompliance g = getCompliance <| Maybe.map (.global) (getGroupCompliance model g.id)
    groupTargetedCompliance g = getCompliance <| Maybe.map (.targeted) (getGroupCompliance model g.id)
    order = case model.ui.groupFilters.tableFilters.sortBy of
      Name       -> NaturalOrdering.compare g1.name g2.name
      Parent     ->
        let
          categoryOrEmpty g = Maybe.withDefault "" (Maybe.map (getCategoryName model) g.category)
          o = NaturalOrdering.compare (categoryOrEmpty g1) (categoryOrEmpty g2)
        in
          case o of
            EQ -> NaturalOrdering.compare g1.name g2.name
            _  -> o

      GlobalCompliance ->
        compare (groupGlobalCompliance g1) (groupGlobalCompliance g2)
      TargetedCompliance ->
        compare (groupTargetedCompliance g1) (groupTargetedCompliance g2)
  in
    if model.ui.groupFilters.tableFilters.sortOrder == Asc then
      order
    else
      case order of
        LT -> GT
        EQ -> EQ
        GT -> LT


generateLoadingTable : Html Msg
generateLoadingTable =
  div [class "table-container skeleton-loading"]
  [ table [class "dataTable"]
    [ thead []
      [ tr [class "head"]
        [ th [][ span[][] ]
        , th [][ span[][] ]
        , th [][ span[][] ]
        , th [][ span[][] ]
        , th [][ span[][] ]
        ]
      ]
    , tbody []
      [ tr[] [ td[][span[style "width" "45%"][]], td[][span[][]], td[][span[][]], td[][span[][]], td[][span[][]] ]
      , tr[] [ td[][span[][]], td[][span[][]], td[][span[][]], td[][span[][]], td[][span[][]] ]
      , tr[] [ td[][span[style "width" "30%"][]], td[][span[][]], td[][span[][]], td[][span[][]], td[][span[][]] ]
      , tr[] [ td[][span[style "width" "75%"][]], td[][span[][]], td[][span[][]], td[][span[][]], td[][span[][]] ]
      , tr[] [ td[][span[][]], td[][span[][]], td[][span[][]], td[][span[][]], td[][span[][]] ]
      , tr[] [ td[][span[style "width" "45%"][]], td[][span[][]], td[][span[][]], td[][span[][]], td[][span[][]] ]
      , tr[] [ td[][span[][]], td[][span[][]], td[][span[][]], td[][span[][]], td[][span[][]] ]
      , tr[] [ td[][span[style "width" "70%"][]], td[][span[][]], td[][span[][]], td[][span[][]], td[][span[][]] ]
      , tr[] [ td[][span[][]], td[][span[][]], td[][span[][]], td[][span[][]], td[][span[][]] ]
      , tr[] [ td[][span[][]], td[][span[][]], td[][span[][]], td[][span[][]], td[][span[][]] ]
      , tr[] [ td[][span[style "width" "80%"][]], td[][span[][]], td[][span[][]], td[][span[][]], td[][span[][]] ]
      , tr[] [ td[][span[style "width" "30%"][]], td[][span[][]], td[][span[][]], td[][span[][]], td[][span[][]] ]
      , tr[] [ td[][span[style "width" "75%"][]], td[][span[][]], td[][span[][]], td[][span[][]], td[][span[][]] ]
      , tr[] [ td[][span[style "width" "45%"][]], td[][span[][]], td[][span[][]], td[][span[][]], td[][span[][]] ]
      , tr[] [ td[][span[][]], td[][span[][]], td[][span[][]], td[][span[][]], td[][span[][]] ]
      , tr[] [ td[][span[style "width" "70%"][]], td[][span[][]], td[][span[][]], td[][span[][]], td[][span[][]] ]
      , tr[] [ td[][span[][]], td[][span[][]], td[][span[][]], td[][span[][]], td[][span[][]] ]
      ]
    ]
  ]

generateLoadingList : Html Msg
generateLoadingList =
  ul[class "skeleton-loading"]
  [ li[style "width" "calc(100% - 25px)"][i[][], span[][]]
  , li[][i[][], span[][]]
  , li[style "width" "calc(100% - 95px)"][i[][], span[][]]
  , ul[]
    [ li[style "width" "calc(100% - 45px)"][i[][], span[][]]
    , li[style "width" "calc(100% - 125px)"][i[][], span[][]]
    , li[][i[][], span[][]]
    ]
  , li[][i[][], span[][]]
  ]

thClass : TableFilters -> SortBy -> String
thClass tableFilters sortBy =
  if sortBy == tableFilters.sortBy then
    case  tableFilters.sortOrder of
      Asc  -> "sorting_asc"
      Desc -> "sorting_desc"
  else
    "sorting"

sortTable : Filters -> SortBy -> Filters
sortTable filters sortBy =
  let
    tableFilters = filters.tableFilters
    order =
      case tableFilters.sortOrder of
        Asc -> Desc
        Desc -> Asc
  in
    if sortBy == tableFilters.sortBy then
      {filters | tableFilters = {tableFilters | sortOrder = order}}
    else
      {filters | tableFilters = {tableFilters | sortBy = sortBy, sortOrder = Asc}}

buildTooltipContent : String -> String -> String
buildTooltipContent title content =
  let
    headingTag = "<h4 class='tags-tooltip-title'>"
    contentTag = "</h4><div class='tooltip-inner-content'>"
    closeTag   = "</div>"
  in
    headingTag ++ title ++ contentTag ++ content ++ closeTag

foldedClass : TreeFilters -> String -> String
foldedClass treeFilters catId =
  if List.member catId treeFilters.folded then
    " jstree-closed"
  else
    " jstree-open"

foldUnfoldCategory : Filters -> String -> Filters
foldUnfoldCategory filters catId =
  let
    treeFilters = filters.treeFilters
    foldedList  =
      if List.member catId treeFilters.folded then
        List.Extra.remove catId treeFilters.folded
      else
        catId :: treeFilters.folded
  in
    {filters | treeFilters = {treeFilters | folded = foldedList}}

getIdAnchorKey : String -> String
getIdAnchorKey id =
  if String.startsWith "special:" id || String.startsWith "policyServer:" id then
    "target"
  else
    "groupId"

getGroupLink : String -> String -> String
getGroupLink contextPath id =
  let
    anchorKey = getIdAnchorKey id
  in
    contextPath ++ """/secure/nodeManager/groups#{\"""" ++ anchorKey ++ """\":\"""" ++ id ++ """\"}"""

hasMoreGroups : Model -> Bool
hasMoreGroups model =
  let
    countTreeElements : Category Group -> Int
    countTreeElements category =
      let
        subElems = case category.subElems of SubCategories l -> l
      in
        (List.length category.elems) + List.sum (List.map countTreeElements subElems)
  in
    (countTreeElements model.groupsTree) > (Dict.size model.groupsCompliance)