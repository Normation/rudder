module Score.ViewUtils exposing (..)

import Html exposing (..)
import Html.Attributes exposing (..)
import Html.Events exposing (onMouseLeave, onMouseOver)
import List
import List.Extra
import String.Extra

import Score.DataTypes exposing (..)
import Compliance.Utils exposing (buildTooltipContent)

scoreLabel : ScoreValue -> String
scoreLabel score =
  case score of
    A -> "A"
    B -> "B"
    C -> "C"
    D -> "D"
    E -> "E"
    F -> "F"
    X -> "X"



getScoreBadge :   Maybe String -> ScoreValue  -> Bool -> Html Msg
getScoreBadge id score smallSize =
  span
    [ onMouseOver (ShowScoreMessage id), onMouseLeave (ShowScoreMessage Nothing), class ("badge-compliance-score " ++ (scoreLabel score) ++ (if smallSize then " sm" else ""))]
    []

scoreBreakdownList : List Score -> List ScoreInfo -> List (Html Msg)
scoreBreakdownList scoreDetails scoreInfo = scoreDetails
  |> List.sortBy .scoreId
  |> List.filter (.value >> (/=) X)
  |> List.map(\sD ->
    let
      name = List.Extra.find (.id >> (==) sD.scoreId) scoreInfo |> Maybe.map .name |> Maybe.withDefault (String.Extra.humanize sD.scoreId)
    in
    div[class "d-flex flex-column pe-5 align-items-center"]
    [ getScoreBadge (Just sD.scoreId) sD.value True
    , label[class "text-center pt-2"][text name ]
    ]
  )