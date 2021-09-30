module ViewTechniqueList exposing (..)

import DataTypes exposing (..)
import Dict
import Dict.Extra
import Html exposing (..)
import Html.Attributes exposing (..)
import Html.Events exposing (..)
import List.Extra
import Maybe.Extra

--
-- This file deals with the technique list UI
-- (ie the part in the left of the UI)
--

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
          li[class "jstree-node jstree-open"]
            [ i[class "jstree-icon jstree-ocl"][]
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
    filteredTechniques = List.sortBy .name (List.filter (\t -> (String.contains model.techniqueFilter t.name) || (String.contains model.techniqueFilter t.id.value) ) techniques)
    html =
      if List.isEmpty techniques then
         div [ class "empty"] [text "The techniques list is empty."]
      else
        case filteredTechniques of
          []   ->   div [ class "empty"] [text "No technique matches the search filter."]
          list ->
            let
              cat = Dict.Extra.groupBy (.category) list |> Dict.toList
            in
              treeCategory model list model.categories |> Maybe.withDefault (text "")
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
            input [ class "form-control",  type_ "text",  placeholder "Filter", onInput UpdateTechniqueFilter , value model.techniqueFilter]  []
          , div [class "input-group-btn"] [
              button [class "btn btn-default", type_ "button", onClick (UpdateTechniqueFilter "")][span [class "fa fa-times"][]]
            ]
          ]
        ]
      ]
    , div [ class "sidebar-body" ] [
        div [class "sidebar-list"] [
          div [class "jstree jstree-default"] [
            ul[class "jstree-container-ul jstree-children"] [ html ]
          ]
        ]
      ]
    ]


allMethodCalls: MethodElem -> List MethodCall
allMethodCalls call =
  case call of
    Call _ c -> [ c ]
    Block _ b -> List.concatMap allMethodCalls b.calls

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
          , a[class ("jstree-anchor " ++ activeClass), onClick (SelectTechnique technique)]
            [ i [class "jstree-icon jstree-themeicon fa fa-cog jstree-themeicon-custom"][]
            , span [class "treeGroupName tooltipable"]
              [ text technique.name  ]
            , if hasDeprecatedMethod  then
                span [ class "cursor-help popover-bs", attribute "data-toggle"  "popover", attribute "data-trigger" "hover"
                     , attribute "data-container" "body", attribute  "data-placement" "right", attribute "data-title" technique.name
                     , attribute "data-content" "<div>This technique uses <b>deprecated</b> generic methods.</div>"
                     , attribute "data-html" "true" ] [ i [ class "glyphicon glyphicon-info-sign deprecated-icon" ] [] ]
              else
                text ""
            ]
          ]
