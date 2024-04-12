module Node.View exposing (..)

import Dict
import Html exposing (..)
import Html.Attributes exposing (class)

import List.Extra
import Node.DataTypes exposing (..)
import Score.DataTypes exposing (DetailedScore)
import String.Extra

view : Model -> Html Msg
view model =
  div[class "score-details d-flex flex-column mb-0"]
    ( h3[][text "Score details"] :: (List.map (showScore model) model.details))

showScore : Model -> DetailedScore ->  Html Msg
showScore model score =
  let
     name = List.Extra.find (.id >> (==) score.scoreId) model.scoreInfo |> Maybe.map .name |> Maybe.withDefault (String.Extra.humanize score.scoreId)
  in
  div[class "d-flex mb-3 align-items-center"]
    ( label[class "text-end"][text name] ::
      (Dict.get score.scoreId model.detailsHtml |>  Maybe.withDefault [ small [] [text "No details yet"]]) )