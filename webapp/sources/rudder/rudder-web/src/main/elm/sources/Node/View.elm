module Node.View exposing (..)

import Dict
import Html exposing (..)
import Html.Attributes exposing (class)

import Node.DataTypes exposing (..)
import Score.DataTypes exposing (DetailedScore)

view : Model -> Html Msg
view model =
  div[class "score-details d-flex flex-column mb-4"]
    ( h3[][text "Score details"] :: (List.map (showScore model) model.details))

showScore : Model -> DetailedScore ->  Html Msg
showScore model score =
  div[class "d-flex mb-3 align-items-center"]
    ( label[class "text-end"][text score.name] ::
      (Dict.get score.name model.detailsHtml |>  Maybe.withDefault [ small [] [text "No details yet"]]) )