module View exposing (..)

import DataTypes exposing (..)
import Html exposing (..)
import Html.Attributes exposing (..)
import Html.Events exposing (onClick)
import List
import ViewUtils exposing (..)
import ViewRulesCompliance exposing (..)
import ViewNodesCompliance exposing (..)
view : Model -> Html Msg
view model =
  div [class "tab-table-content"]
  ( List.append
    [ ul [class "ui-tabs-nav"]
      [ li [class ("ui-tabs-tab ui-tab" ++ (if model.ui.viewMode == RulesView then " active" else ""))]
        [ a [onClick (ChangeViewMode RulesView)]
          [ text "By Rules"
          ]
        ]
      , li [class ("ui-tabs-tab ui-tab" ++ (if model.ui.viewMode == NodesView then " active" else ""))]
        [ a [onClick (ChangeViewMode NodesView)]
          [ text "By Nodes"
          ]
        ]
      ]
    ]
    ( case model.ui.viewMode of
      RulesView -> displayRulesComplianceTable model
      NodesView -> displayNodesComplianceTable model
    )
  )