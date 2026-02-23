module GroupRelatedRules.ViewUtils exposing (..)

import List.Extra
import Html exposing (..)
import Html.Attributes exposing (..)

import GroupRelatedRules.DataTypes exposing (..)
import Rules.DataTypes exposing (missingCategoryId)
import Ui.Datatable exposing (Category, SubCategories(..), getSubElems, getAllCats)
import Utils.TooltipUtils exposing (buildTooltipContent, htmlEscape)

-- get all missing categories
getAllMissingCats: Category a -> List (Category a)
getAllMissingCats category =
  let
    missingCategory = List.filter (\sub -> sub.id == missingCategoryId) (getSubElems category)
  in
  List.concatMap getAllCats missingCategory

filterRuleElemsByIds : List String -> Category Rule -> Category Rule
filterRuleElemsByIds ids category =
  let
    copyCat cat = { cat | elems = List.filter (\e -> List.member e.id.value ids) cat.elems }
    filteredElems = (copyCat category).elems
  in
    case category.subElems of
      Ui.Datatable.SubCategories subCats ->
        let
          newSubCats = List.map (filterRuleElemsByIds ids) subCats
        in
          { category | subElems = SubCategories newSubCats, elems = filteredElems }


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

badgeIncludedExcluded : Model -> RuleId -> Html Msg
badgeIncludedExcluded model ruleId =
  let
    isIncluded = List.member ruleId model.rulesMeta.includedRules 
    isExcluded = List.member ruleId model.rulesMeta.excludedRules
    msg =
      -- TODO green red
      if isIncluded then Just "<div style='margin-bottom:5px;'>This rule is <b style='color:#9bc832;'>included</b> by the group.</div>"
      else if isExcluded then Just "<div style='margin-bottom:5px;'>This rule is <b style='color:#3694d1;'>excluded</b> by the group.</div>"
      else Nothing
    labelClass = if isIncluded then "label-included label-green" else if isExcluded then "label-excluded" else ""
  in
    case msg of
      Just m -> span [class ("treeGroupName rudder-label label-sm " ++ labelClass), attribute "data-bs-toggle" "tooltip", attribute "data-bs-placement" "bottom", title (buildTooltipContent "Included/Excluded" m)][]
      Nothing -> text ""


buildTagsTree : List Tag -> Html Msg
buildTagsTree tags =
  let
    nbTags = List.length tags

    tooltipContent : List Tag -> String
    tooltipContent listTags =
      buildTooltipContent ("Tags <span class='tags-label'><i class='fa fa-tags'></i><b>"++ (String.fromInt nbTags) ++"</b></span>") (String.concat (List.map (\tt -> buildHtmlStringTag tt) listTags))
  in
    if (nbTags > 0) then
      span [class "tags-label", attribute "data-bs-toggle" "tooltip", attribute "data-bs-placement" "top", title (tooltipContent tags)]
      [ i [class "fa fa-tags"][]
      , b[][text (String.fromInt nbTags)]
      ]
    else
      text ""

buildHtmlStringTag : Tag -> String
buildHtmlStringTag tag =
  let
    tagOpen  = "<span class='tags-label'>"
    tagIcon  = "<i class='fa fa-tag'></i>"
    tagKey   = "<span class='tag-key'>"   ++ htmlEscape tag.key   ++ "</span>"
    tagSep   = "<span class='tag-separator'>=</span>"
    tagVal   = "<span class='tag-value'>" ++ htmlEscape tag.value ++ "</span>"
    tagClose = "</span>"
  in
    tagOpen ++ tagIcon ++ tagKey ++ tagSep ++ tagVal ++ tagClose



filterTags : List Tag -> List Tag -> Bool
filterTags ruleTags tags =
  if List.isEmpty tags then
    True
  else if List.isEmpty ruleTags then
    False
  else
    --List.Extra.count (\t -> List.Extra.notMember t ruleTags) tags <= 0
    tags
      |> List.all (\tag ->
        if not (String.isEmpty tag.key) && not (String.isEmpty tag.value) then
          List.member tag ruleTags
        else if String.isEmpty tag.key then
          case List.Extra.find (\t -> t.value == tag.value) ruleTags of
            Just _ -> True
            Nothing -> False
        else if String.isEmpty tag.value then
          case List.Extra.find (\t -> t.key == tag.key) ruleTags of
            Just _ -> True
            Nothing -> False
        else
          True
        )

getCategoryName : Model -> String -> String
getCategoryName model id =
  let
    cat = List.Extra.find (.id >> (==) id  ) (getAllCats model.ruleTree)
  in
    case cat of
      Just c -> c.name
      Nothing -> id

searchFieldRules : Rule -> Model -> List String
searchFieldRules r model =
  [ r.id.value
  , r.name
  , r.categoryId
  , getCategoryName model r.categoryId
  ]


foldedClass : Filters -> String -> String
foldedClass treeFilters catId =
  if List.member catId treeFilters.folded then
    " jstree-closed"
  else
    " jstree-open"

foldUnfoldCategory : Filters -> String -> Filters
foldUnfoldCategory treeFilters catId =
  let
    foldedList  =
      if List.member catId treeFilters.folded then
        List.Extra.remove catId treeFilters.folded
      else
        catId :: treeFilters.folded
  in
    {treeFilters | folded = foldedList}

getRuleLink : String -> RuleId -> String
getRuleLink contextPath id =
  contextPath ++ "/secure/configurationManager/ruleManagement/rule/" ++ id.value

getRuleCategoryLink : String -> String -> String
getRuleCategoryLink contextPath catId =
  contextPath ++ "/secure/configurationManager/ruleManagement/ruleCategory/" ++ catId