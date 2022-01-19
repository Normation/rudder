module ViewTechniqueList exposing (..)

import DataTypes exposing (..)
import Dict
import Dict.Extra
import Either exposing (Either(..))
import Html exposing (..)
import Html.Attributes exposing (..)
import Html.Events exposing (..)
import List.Extra
import Maybe.Extra

--
-- This file deals with the technique list UI
-- (ie the part in the left of the UI)
--
foldedClass : TreeFilters -> String -> String
foldedClass treeFilters techniqueId =
  if List.member techniqueId treeFilters.folded then
    " jstree-closed"
  else
    " jstree-open"

foldUnfoldCategory : TreeFilters -> String -> TreeFilters
foldUnfoldCategory treeFilters catId =
  let
    foldedList  =
      if List.member catId treeFilters.folded then
        List.Extra.remove catId treeFilters.folded
      else
        catId :: treeFilters.folded
  in
    {treeFilters | folded = foldedList}

treeCategory : Model -> List Technique -> TechniqueCategory -> Maybe (Html Msg)
treeCategory model techniques category =
      let
        techniquesElem = techniques
                |> List.filter (.category >> (==) category.path)
                |> List.map (techniqueItem model)

        subCategories = case category.subCategories of SubCategories l -> l

        childsList  = case (techniquesElem, List.filterMap (treeCategory model techniques) subCategories) of
                        ([],[]) -> Nothing
                        (cats,tech) -> Just (List.concat [ cats, tech])

      in
        Maybe.map (\children ->
          li[class ("jstree-node " ++ (foldedClass model.techniqueFilter category.id))]
            [ i[class "jstree-icon jstree-ocl", onClick (UpdateTechniqueFilter (foldUnfoldCategory model.techniqueFilter category.id))][]
            , a[class "jstree-anchor" ]
              [ i [class "jstree-icon jstree-themeicon fa fa-folder jstree-themeicon-custom"][]
              , span [class "treeGroupCategoryName tooltipable"][text category.name]
              ]
            , ul[class "jstree-children"] children
            ]
        ) childsList

techniqueList : Model -> List Technique -> Html Msg
techniqueList model techniques =
  let
    filteredTechniques = List.sortBy .name (List.filter (\t -> (String.contains model.techniqueFilter.filter t.name) || (String.contains model.techniqueFilter.filter t.id.value) ) techniques)
    filteredDrafts = List.sortBy (.technique >> .name) (List.filter (\t -> (String.contains model.techniqueFilter.filter t.technique.name) && Maybe.Extra.isNothing t.origin ) (Dict.values model.drafts))
    techniqueItems =
      if List.isEmpty techniques && Dict.isEmpty model.drafts then
         div [ class "empty"] [text "The techniques list is empty."]
      else
        case (filteredTechniques, filteredDrafts) of
          ([], [])   ->   div [ class "empty"] [text "No technique matches the search filter."]
          (list, _) ->
              treeCategory model list model.categories |> Maybe.withDefault (text "")
    drafts =
      if List.isEmpty filteredDrafts then
        text ""
      else
          li[class ("jstree-node " ++ (foldedClass model.techniqueFilter "drafts"))]
            [ i[class "jstree-icon jstree-ocl", onClick (UpdateTechniqueFilter (foldUnfoldCategory model.techniqueFilter "drafts"))][]
            , a[class "jstree-anchor" ]
              [ i [class "jstree-icon jstree-themeicon fa fa-folder jstree-themeicon-custom"][]
              , i [class "treeGroupCategoryName tooltipable"][text "Drafts"]
              ]
            , ul[class "jstree-children"] (List.map (draftsItem model) filteredDrafts)
            ]
    modelTechniqueFilter = model.techniqueFilter
  in
    div [ class "template-sidebar sidebar-left col-techniques", onClick OpenTechniques ] [
      div [ class "sidebar-header"] [
        div [ class "header-title" ] [
          h1 [] [
            text "Techniques"
          , span [ id "nb-techniques", class "badge badge-secondary badge-resources" ] [
              span [] [ text (String.fromInt (List.length techniques)) ]
            ]
          ]
        , div [ class "header-buttons", hidden (not model.hasWriteRights)] [ -- Need to add technique-write rights
            label [class "btn btn-sm btn-primary", onClick StartImport] [
              text "Import "
            , i [ class "fa fa-upload" ] []
            ]
          , button [ class "btn btn-sm btn-success", onClick  (GenerateId (\s -> NewTechnique (TechniqueId s))) ] [
              text "Create "
            , i [ class "fa fa-plus-circle"] []
            ]
          ]
        ]
      , div [ class "header-filter" ] [
          div [class "input-group"] [
            input [ class "form-control",  type_ "text",  placeholder "Filter", onInput (\s -> UpdateTechniqueFilter {modelTechniqueFilter | filter = s}) , value model.techniqueFilter.filter]  []
          , div [class "input-group-btn"] [
              button [class "btn btn-default", type_ "button", onClick (UpdateTechniqueFilter {modelTechniqueFilter | filter = ""})][span [class "fa fa-times"][]]
            ]
          ]
        ]
      ]
    , div [ class "sidebar-body" ]
      [ div [class "sidebar-list"][(
        if model.loadingTechniques then
          generateLoadingList
          else
            div [class "jstree jstree-default"][
              ul[class "jstree-container-ul jstree-children"] [ techniqueItems, drafts ]
            ]
          )
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

allMethodCalls: MethodElem -> List MethodCall
allMethodCalls call =
  case call of
    Call _ c -> [ c ]
    Block _ b -> List.concatMap allMethodCalls b.calls


draftsItem: Model -> Draft -> Html Msg
draftsItem model draft =
  let
    activeClass = case model.mode of
                    TechniqueDetails t _ _ ->
                      if t.id.value == draft.id then
                         "jstree-clicked"
                      else
                        ""
                    _ -> ""
    hasDeprecatedMethod = List.any (\m -> Maybe.Extra.isJust m.deprecated )(List.concatMap (\c -> Maybe.Extra.toList (Dict.get c.methodName.value model.methods)) (List.concatMap allMethodCalls draft.technique.elems))
  in

    li [class "jstree-node jstree-leaf"]
          [ i[class "jstree-icon jstree-ocl"][]
          , a[class ("jstree-anchor " ++ activeClass), onClick (SelectTechnique (Right draft))]
            [ i [class "jstree-icon jstree-themeicon fa fa-pen jstree-themeicon-custom"][]
            , span [class "treeGroupName tooltipable"]
              [ text (if String.isEmpty draft.technique.name then "<unamed draft>" else draft.technique.name)  ]
            , if hasDeprecatedMethod  then
                span [ class "cursor-help popover-bs", attribute "data-toggle"  "popover", attribute "data-trigger" "hover"
                     , attribute "data-container" "body", attribute  "data-placement" "right", attribute "data-title" draft.technique.name
                     , attribute "data-content" "<div>This technique uses <b>deprecated</b> generic methods.</div>"
                     , attribute "data-html" "true" ] [ i [ class "glyphicon glyphicon-info-sign deprecated-icon" ] [] ]
              else
                text ""
            ]
          ]

techniqueItem: Model -> Technique -> Html Msg
techniqueItem model technique =
  let
    activeClass = case model.mode of
                    TechniqueDetails t _ _ ->
                      if t.id == technique.id then
                         "jstree-clicked"
                      else
                        ""
                    _ -> ""
    hasDeprecatedMethod = List.any (\m -> Maybe.Extra.isJust m.deprecated )(List.concatMap (\c -> Maybe.Extra.toList (Dict.get c.methodName.value model.methods)) (List.concatMap allMethodCalls technique.elems))
  in

    li [class "jstree-node jstree-leaf"]
          [ i[class "jstree-icon jstree-ocl"][]
          , a[class ("jstree-anchor " ++ activeClass), onClick (SelectTechnique (Left technique))]
            [ i [class "jstree-icon jstree-themeicon fa fa-cog jstree-themeicon-custom"][]
            , span [class "treeGroupName tooltipable"]
              [ text technique.name  ]
            , if Dict.member technique.id.value model.drafts then
              span [class "badge" ] [ text "draft" ]
              else text ""
            , if hasDeprecatedMethod  then
                span [ class "cursor-help popover-bs", attribute "data-toggle"  "popover", attribute "data-trigger" "hover"
                     , attribute "data-container" "body", attribute  "data-placement" "right", attribute "data-title" technique.name
                     , attribute "data-content" "<div>This technique uses <b>deprecated</b> generic methods.</div>"
                     , attribute "data-html" "true" ] [ i [ class "glyphicon glyphicon-info-sign deprecated-icon" ] [] ]
              else
                text ""
            ]
          ]
