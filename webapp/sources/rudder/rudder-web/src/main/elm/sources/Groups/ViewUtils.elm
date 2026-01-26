module Groups.ViewUtils exposing (..)

import Dict
import Maybe
import Maybe.Extra
import NaturalOrdering
import List.Extra

import Html exposing (Html, div, span, table, tbody, td, th, thead, tr)
import Html.Attributes exposing (class, style)
import Html exposing (ul, li, i)

import Groups.DataTypes exposing (..)

import Compliance.Utils exposing (getAllComplianceValues)
import GroupCompliance.DataTypes exposing (GroupId)
import Ui.Datatable exposing (SortOrder(..), getAllCats, Category, SubCategories(..))


getCategoryName : Model -> String -> String
getCategoryName model id =
  let
    cat = List.Extra.find (.id >> (==) id  ) (getAllCats model.groupsTree)
  in
    case cat of
      Just c -> c.name
      Nothing -> id

searchFieldGroups : Group -> Model -> List String
searchFieldGroups g model =
  [ g.id.value
  , g.name
  ] ++ Maybe.Extra.toList g.category ++ Maybe.Extra.toList (Maybe.map (getCategoryName model) g.category)

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

buildTooltipContent : String -> String -> String
buildTooltipContent title content =
  let
    headingTag = "<h4 class='tags-tooltip-title'>"
    contentTag = "</h4><div class='tooltip-inner-content'>"
    closeTag   = "</div>"
  in
    headingTag ++ title ++ contentTag ++ content ++ closeTag

foldedClass : Filters -> String -> String
foldedClass filters catId =
  if List.member catId filters.folded then
    " jstree-closed"
  else
    " jstree-open"

foldUnfoldCategory : Filters -> String -> Filters
foldUnfoldCategory filters catId =
  let
    foldedList  =
      if List.member catId filters.folded then
        List.Extra.remove catId filters.folded
      else
        catId :: filters.folded
  in
    {filters | folded = foldedList}

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