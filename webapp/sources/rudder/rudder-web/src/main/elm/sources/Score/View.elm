module Score.View exposing (..)

import Html exposing (..)
import Html.Attributes exposing (..)
import List.Extra
import Markdown

import Score.DataTypes exposing (..)
import Score.ViewUtils exposing (..)
import String.Extra

view : Model -> Html Msg
view model =
  div[ class "compliance-score d-flex flex-column mb-4" ]
  [ div[class "global-score d-flex"]
    ( case model.score of
      Just complianceScore ->
        let

          (message,name) = case model.scoreToShow of
                      Nothing -> (complianceScore.message, "Global")
                      Just scoreId ->
                        let
                          msg = complianceScore.details |> List.Extra.find (.scoreId >> (==) scoreId) |> Maybe.map .message |> Maybe.withDefault complianceScore.message
                          n   = List.Extra.find (.id >> (==) scoreId) model.scoreInfo |> Maybe.map .name |> Maybe.withDefault (String.Extra.humanize scoreId)
                        in
                          (msg,n)
        in
        [ div[class "score-badge"]
          [ getScoreBadge Nothing complianceScore.value False
          ]
        , div[class "score-breakdown ps-5 flex-column"]
           [ h3[][text "Score breakdown"]
           , div[class "d-flex"](scoreBreakdownList complianceScore.details model.scoreInfo)
           ]
        , div[class "score-explanation ps-3 flex-grow-1"]
          (( h3 [] [text name ] ) ::
          ( Markdown.toHtml Nothing message ))
        ]
      Nothing ->
        let
          noComplianceMsg = "There is no score for this node"
        in
          [ div[class "score-badge sm "]
            [ getScoreBadge Nothing X False
            ]
          , div[class "no-compliance d-flex flex-grow-1 align-items-center ps-4"]
            [ i[class "fa fa-warning"][]
            , text noComplianceMsg
            ]
          ]
    )
  ]
