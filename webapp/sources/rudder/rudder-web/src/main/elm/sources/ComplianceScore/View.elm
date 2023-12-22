module ComplianceScore.View exposing (..)

import Html exposing (..)
import Html.Attributes exposing (..)
import List
import Html.Lazy
import List.Extra
import Markdown

import ComplianceScore.DataTypes exposing (..)
import ComplianceScore.ViewUtils exposing (..)
import NodeCompliance.DataTypes exposing (NodeId)
import Compliance.Utils exposing (buildComplianceBar, defaultComplianceFilter)

view : Model -> Html Msg
view model =
  div[ class "compliance-score d-flex flex-column mb-4" ]
  [ div[class "global-score d-flex"]
    ( case model.complianceScore of
      Just complianceScore ->
        [ div[class "score-badge"]
          [ getScoreBadge complianceScore.value [] False
          ]
        , div[class "score-breakdown ps-5 flex-grow-1 flex-column"]
           [ h3[][text "Score breakdown"]
           , div[class "d-flex"](scoreBreakdownList complianceScore.details)
           ]
        , div[class "score-explanation ps-3 flex-grow-1"]
          ( Markdown.toHtml Nothing complianceScore.message )
        ]
      Nothing ->
        let
          noComplianceMsg = "There is no score" ++
            ( case model.item of
              Just item -> case item of
                Node _ -> " for this node"
                Rule _ -> " for this rule"
              Nothing -> ""
            )
        in
          [ div[class "score-badge sm "]
            [ getScoreBadge X [] False
            ]
          , div[class "no-compliance d-flex flex-grow-1 align-items-center ps-4"]
            [ i[class "fa fa-warning"][]
            , text noComplianceMsg
            ]
          ]
      )
  ]
