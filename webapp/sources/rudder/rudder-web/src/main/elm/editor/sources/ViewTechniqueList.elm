module ViewTechniqueList exposing (..)

import DataTypes exposing (..)
import Dict
import Html exposing (..)
import Html.Attributes exposing (..)
import Html.Events exposing (..)
import Maybe.Extra

--
-- This file deals with the technique list UI
-- (ie the part in the left of the UI)
--

techniqueList : Model -> List Technique -> Html Msg
techniqueList model techniques =
  let
    filteredTechniques = List.sortBy .name (List.filter (\t -> (String.contains model.techniqueFilter t.name) || (String.contains model.techniqueFilter t.id.value) ) techniques)
    html =
      if List.isEmpty techniques then
        [ div [ class "empty"] [text "The techniques list is empty."] ]
      else
        case filteredTechniques of
          []   ->  [ div [ class "empty"] [text "No technique matches the search filter."] ]
          list -> (List.map (techniqueItem model) list)
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
          input [ class "form-control",  type_ "text",  placeholder "Filter", onInput UpdateTechniqueFilter  ]  []
        ]
      ]
    , div [ class "sidebar-body" ] [
        div [ class "techniques-list-container" ] [
          ul [] html

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
                        [ class "active"]
                      else
                        [ ]
                    _ -> []
    hasDeprecatedMethod = List.any (\m -> Maybe.Extra.isJust m.deprecated )(List.concatMap (\c -> Maybe.Extra.toList (Dict.get c.methodName.value model.methods)) (List.concatMap allMethodCalls technique.elems))
  in
    li activeClass [
      div [ class "item",  onClick (SelectTechnique technique) ] [
        span [] [ text technique.name ]
      , if hasDeprecatedMethod  then
          span [ class "cursor-help popover-bs", attribute "data-toggle"  "popover", attribute "data-trigger" "hover"
               , attribute "data-container" "body", attribute  "data-placement" "right", attribute "data-title" technique.name
               , attribute "data-content" "<div>This technique uses <b>deprecated</b> generic methods.</div>"
               , attribute "data-html" "true" ] [ i [ class "glyphicon glyphicon-info-sign deprecated-icon" ] [] ]
        else
          text ""
      ]
    , div [ class "col-auto align-self-center" ,  onClick (SelectTechnique technique) ] [
        i [ class "ion ion-edit" ] []
      ]
    ]
