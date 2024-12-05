module Nodes.View exposing (..)

import Html exposing (..)
import Html.Attributes exposing (..)
import Html.Events exposing (onClick, onInput)

import Nodes.DataTypes exposing (..)
import Nodes.ViewUtils exposing (..)
import Nodes.ApiCalls exposing (getNodeDetails)

import Ui.Datatable exposing (generateLoadingTable)

view : Model -> Html Msg
view model =
  if model.ui.hasReadRights then
    let
      nodes   = model.nodes
      ui      = model.ui
      filters = ui.filters

      editColumnsBtn =
        if ui.editColumns then
          button [class "btn btn-success btn-sm btn-icon", style "min-width" "120px", onClick (UpdateUI {ui | editColumns = False})]
          [ text "Confirm"
          , i [class "fa fa-check"][]
          ]
        else
          button [class "btn btn-default btn-sm btn-icon", style "min-width" "120px", onClick (UpdateUI {ui | editColumns = True})]
          [ text "Edit columns"
          , i [class "fa fa-pencil"][]
          ]

      displayColumnsEdit =
        if ui.editColumns then
          let
            colOptions = allColumns
              |> List.filter (\c -> not (List.member c ui.columns))
              |> List.map (\c -> option[value (getColumnTitle c)][text (getColumnTitle c)])
          in
            div[class "more-filters edit-columns"]
            [ select[](colOptions)
            ]
        else
          text ""
    in
      div [class "rudder-template"]
      [ div [class "one-col"]
        [ div [class "main-header"]
          [ div [class "header-title"]
            [ h1[][ span[][text "Nodes"] ]
            ]
          ]
        , div [class "one-col-main"]
          [ div [class "template-main"]
            [ if model.ui.loading then
              generateLoadingTable False 5
              else
              div [class "main-table tab-table-content col-sm-12"]
              [ div [class "table-header extra-filters"]
                [ div [class "main-filters"]
                  [ input [type_ "text", placeholder "Filter", class "input-sm form-control", onInput (\s -> UpdateUI {ui | filters = {filters | filter = s}})][]
                  , editColumnsBtn
                  , button [class "btn btn-default btn-sm btn-refresh", onClick (CallApi getNodeDetails)][i [class "fa fa-refresh"][]]
                  ]
                , displayColumnsEdit
                ]
               , div [class "table-container"]
                [ table [ class "no-footer dataTable"]
                  [ thead [] [nodesTableHeader model.ui]
                  , tbody [] (buildNodesTable model)
                  ]
                ]
              ]
            ]
          ]
        ]
      ]
  else
    text "No rights"
