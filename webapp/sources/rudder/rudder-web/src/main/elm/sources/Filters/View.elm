module Filters.View exposing (..)

import Html exposing (..)
import Html.Attributes exposing (checked, class, id, placeholder, style, type_, value)
import Html.Events exposing (onCheck, onClick, onInput)

import Filters.DataTypes exposing (..)

import Tags.DataTypes exposing (Msg(..), Tag)
import Tags.View exposing (displayTags, displayTagForm)


view : Model -> Html Filters.DataTypes.Msg
view model =
  let
    newTag = model.newTag
    addBtnDisabled = String.isEmpty newTag.key && String.isEmpty newTag.value
  in
    div [ id "directiveFilter" ]
    [ div [ class "header-filter"]
      [ div [ class "filter-auto-width"]
        [ div [ class "input-group search-addon main-filter pb-0"]
          [ button [ type_ "button", class "btn btn-sm btn-default", onClick ToggleTree]
            [ span [ class "fa fa-folder fa-folder-open"][]
            ]
          , input [ class "form-control input-sm", placeholder "Filter", id "treeSearch", value model.filter, onInput (\s -> UpdateFilter s)][]
          , button [ type_ "button", class "btn btn-default btn-sm", onClick (UpdateFilter "")]
            [ span [ class "fa fa-times"][]
            ]
          ]
        ]
      , button [ class "btn btn-default more-filters", onClick ShowMore][]
      ]
    , div
      [ class ("filters-container" ++ if model.showMore then "" else " d-none")
      , style "display" "grid"]
      [ div [class "filterTag"]
        [ div [ id "form-tag" ]
          [ displayTagForm model.newTag model.tags model.completionKeys model.completionValues Filters.DataTypes.UpdateTag Filters.DataTypes.UpdateTags addBtnDisabled
          , ( if List.isEmpty model.tags then
            text ""
            else
            div [ class "only-tags"]
            [ button [ class "btn btn-default btn-xs pull-right clear-tags", onClick (Filters.DataTypes.UpdateTags Tags.DataTypes.Remove [])]
              [ text "Clear all tags"
              , i [class "fa fa-trash"][]
              ]
            ]
            )
          ]
        , displayTags model.newTag model.tags Filters.DataTypes.UpdateTag Filters.DataTypes.UpdateTags True True []
        ]
      , toggleShowHideTechniques model
      ]
    ]

toggleShowHideTechniques : Model -> Html Filters.DataTypes.Msg
toggleShowHideTechniques model =
    div
        [ id "form-show-hide-techniques"]
        [ input
            [ type_ "checkbox"
            , checked model.hideUnusedTechniques
            , onCheck ToggleHideUnusedTechniques]
            []
        ,  b[] [text " Hide unused techniques"]
        ]
        