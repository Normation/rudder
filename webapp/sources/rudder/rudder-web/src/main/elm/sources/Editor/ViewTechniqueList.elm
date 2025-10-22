module Editor.ViewTechniqueList exposing (..)

import Dict
import Either exposing (Either(..))
import Html exposing (..)
import Html.Attributes exposing (..)
import Html.Events exposing (..)
import Html.Events.Extra exposing (onClickPreventDefault)
import List.Extra
import Maybe.Extra
import NaturalOrdering as N exposing (compare)
import Editor.DataTypes exposing (..)

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
              , span [class "treeGroupCategoryName"][text category.name]
              ]
            , ul[class "jstree-children"] children
            ]
        ) childsList

techniqueList : Model -> List Technique -> Html Msg
techniqueList model techniques =
  let
    strFilter = String.toLower (String.trim model.techniqueFilter.filter)
    filteredTechniques = List.sortWith (\t1 t2 -> N.compare t1.name t2.name) (List.filter (\t -> (String.contains strFilter (String.toLower t.name)) || (String.contains strFilter (String.toLower t.id.value)) ) techniques)
    filteredDrafts = List.sortWith (\t1 t2 -> N.compare t1.technique.name t2.technique.name) (List.filter (\t -> (String.contains strFilter (String.toLower t.technique.name)) && Maybe.Extra.isNothing t.origin ) (Dict.values model.drafts))
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
              , i [class "treeGroupCategoryName"][text "Drafts"]
              ]
            , ul[class "jstree-children"] (List.map (draftsItem model) filteredDrafts)
            ]
    modelTechniqueFilter = model.techniqueFilter
  in
    div [ class "template-sidebar sidebar-left col-techniques", onClick OpenTechniques ] [
      div [ class "sidebar-header"] [
        div [ class "header-title flex-wrap" ] [
          h1 [class "d-flex align-items-center"] [
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
          , button [class "btn btn-default", type_ "button", onClick (UpdateTechniqueFilter {modelTechniqueFilter | filter = ""})][span [class "fa fa-times"][]]
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
                    TechniqueDetails _ (Clone _ _ id) _ _ ->
                      if id.value == draft.id.value then
                         "jstree-clicked"
                      else
                        ""
                    TechniqueDetails _ (Creation id) _ _ ->
                      if id.value == draft.id.value then
                         "jstree-clicked"
                      else
                        ""
                    _ ->  ""
    hasDeprecatedMethod = List.any (\m -> Maybe.Extra.isJust m.deprecated )(List.concatMap (\c -> Maybe.Extra.toList (Dict.get c.methodName.value model.methods)) (List.concatMap allMethodCalls draft.technique.elems))
  in

    li [class "jstree-node jstree-leaf"]
          [ i[class "jstree-icon jstree-ocl"][]
          , a[class ("jstree-anchor " ++ activeClass), onClick (SelectTechnique (Right draft))]
            [ i [class "jstree-icon jstree-themeicon fa fa-pen jstree-themeicon-custom"][]
            , span [class "treeGroupName"]
              [ text (if String.isEmpty draft.technique.name then "<unamed draft>" else draft.technique.name)  ]
            , if hasDeprecatedMethod  then
                span [ class "cursor-help"
                , attribute "data-bs-toggle" "tooltip"
                , attribute "data-bs-placement" "top"
                , attribute "data-bs-html" "true"
                , title "<div>This technique uses <b>deprecated</b> generic methods.</div>"
                ] [ i [ class "fa fa-info-circle deprecated-icon" ] [] ]
              else
                text ""
            ]
          ]

techniqueItem: Model -> Technique -> Html Msg
techniqueItem model technique =
  let
    techniqueMethods = List.concatMap allMethodCalls technique.elems
    unknownMethods = (List.filter (\c -> Maybe.Extra.isNothing (Dict.get c.methodName.value model.methods)) techniqueMethods)
    activeClass = case model.mode of
                    TechniqueDetails t (Edit _) _ _ ->
                      if t.id == technique.id then
                         "jstree-clicked"
                      else
                        ""
                    _ -> ""
    hasDeprecatedMethod = List.any (\m -> Maybe.Extra.isJust m.deprecated )(List.concatMap (\c -> Maybe.Extra.toList (Dict.get c.methodName.value model.methods)) (List.concatMap allMethodCalls technique.elems))
    hasUnknownMethod =
      if List.isEmpty techniqueMethods then
        False
      else
        if List.isEmpty unknownMethods then
          False
        else
          True

  in

    li [class "jstree-node jstree-leaf"]
          [ i[class "jstree-icon jstree-ocl"][]
          , a[class ("jstree-anchor " ++ activeClass), href (model.contextPath ++ "/secure/configurationManager/techniqueEditor/technique/" ++ technique.id.value), onClickPreventDefault (SelectTechnique (Left technique))]
            [ i [class "jstree-icon jstree-themeicon fa fa-cog jstree-themeicon-custom"][]
            , span [class "treeGroupName"]
              [ text technique.name  ]
            , if Dict.member technique.id.value model.drafts then
              span [class "badge" ] [ text "draft" ]
              else text ""
            , if hasDeprecatedMethod  then
                span
                [ class "cursor-help"
                , attribute "data-bs-toggle" "tooltip"
                , attribute "data-bs-placement" "top"
                , attribute "data-bs-html" "true"
                , title "<div>This technique uses <b>deprecated</b> generic methods.</div>"
                ] [ i [ class "fa fa-info-circle deprecated-icon" ] [] ]
              else
                text ""
            , if hasUnknownMethod  then
                span
                [ class "cursor-help"
                , attribute "data-bs-toggle" "tooltip"
                , attribute "data-bs-placement" "top"
                , attribute "data-bs-html" "true"
                , title ("<div>This technique uses <b>unknown</b> generic methods: " ++ (String.join ", " (List.map (\m -> m.methodName.value) (List.Extra.unique unknownMethods))) ++ ".</br>These methods do not exist in the library, you must provide them or it will break at run time</div>")
                ] [ i [ class "fa fa-warning text-warning-rudder min-size-icon unknown-gm-icon" ] [] ]
              else
                text ""
            ]
          ]
