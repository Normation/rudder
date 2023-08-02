module Tags.View exposing (..)

import Html exposing (..)
import Html.Attributes exposing (class, type_, value, id, disabled, for, placeholder, list)
import Html.Events exposing (onClick, onInput)
import List
import List.Extra exposing (remove)
import String

import Tags.DataTypes exposing (..)


view : Model -> Html Msg
view model =
  let
    newTag = model.newTag

    displayTag : Tag -> Html Msg
    displayTag tag =
      div [class "btn-group btn-group-xs"] -- ng-class "{'match': tagMatch(tag)}"
      [ button [type_ "button", class "btn btn-default tags-label" , onClick (AddToFilter tag)]
        [ i [class "fa fa-tag"][]
        , span [class "tag-key"      ][ text tag.key   ]
        , span [class "tag-separator"][ text "="       ]
        , span [class "tag-value"    ][ text tag.value ]
        , span [class "fa fa-search-plus"][]
        ]
      , ( if model.ui.isEditForm then
        button [ type_ "button", class "btn btn-default", onClick (UpdateTags Remove (remove tag model.tags))]
        [ span [class "fa fa-times text-danger"][]
        ]
        else
        text ""
        )
      ]

    addBtnDisabled = String.isEmpty model.newTag.key || String.isEmpty model.newTag.value
  in
    div [id "tagApp"]
    [ div [id "tagForm"]
      [ div [class "form-group"]
        [ label [for "newTagKey", class "col-xs-12 row"]
          [ span [class "text-fit"][ text "Tags" ]
          ]
        , div [class "input-group col-xs-6"]
          [ div [id "newTagKey", placeholder "key"]
            [ input [class "form-control input-sm input-key", list "completion-key-list", placeholder "key", value newTag.key, onInput (\s -> (UpdateTag Key {newTag | key = s}))][]
            , datalist [id "completion-key-list"]
              ( List.map (\c -> option[value c.value][]) model.ui.completionKeys )
            ]
          , span [class "input-group-addon addon-json"] [ text "=" ]
          , div [id "newTagValue", placeholder "value"]
            [ input [class "form-control input-sm input-value", list "completion-val-list", placeholder "value", value newTag.value, onInput (\s -> (UpdateTag Val {newTag | value = s}))][]
            , datalist [id "completion-val-list"]
              ( List.map (\c -> option[value c.value][]) model.ui.completionValues )
            ]
          , span [class "input-group-btn"]
            [ button [type_ "button", class "btn btn-default btn-sm", onClick (UpdateTags Add (newTag :: model.tags)), disabled addBtnDisabled]
              [ span [class "fa fa-plus"][]
              ]
            ]
          ]
        ]
      ]
    , div [class ("tags-container form-group row col-xs-12" ++ (if List.isEmpty model.tags then " noTags" else ""))]
      ( model.tags |> List.map displayTag )
    ]

