module Node.View exposing (..)

import Html exposing (..)
import Html.Attributes exposing (class)

import Node.DataTypes exposing (..)
import ComplianceScore.ViewUtils exposing (buildComplianceScoreDetails, buildSystemUpdatesScoreDetails)

view : Model -> Html Msg
view model =
  case model.scoreDetails of
    Just scoreDetails ->
      div[class "score-details d-flex flex-column mb-4"]
      [ h3[][text "Score details"]
      , buildComplianceScoreDetails scoreDetails.compliance
      , buildSystemUpdatesScoreDetails scoreDetails.systemUpdates
      ]
    Nothing -> text ""