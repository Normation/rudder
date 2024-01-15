module Score.JsonDecoder exposing (..)

import Json.Decode exposing (..)
import Json.Decode.Pipeline exposing (..)

import Score.DataTypes exposing (..)

decodeGetScore : Decoder GlobalScore
decodeGetScore =
  at [ "data" ] decodeGlobalScore

decodeGetDetails : Decoder (List DetailedScore)
decodeGetDetails =
  at [ "data" ] (list decodeDetailedScore)
decodeGlobalScore : Decoder GlobalScore
decodeGlobalScore =
  succeed GlobalScore
    |> required "value"   (map toScoreValue string)
    |> required "message" string
    |> required "details" (list decodeScore)

decodeScore : Decoder Score
decodeScore =
  succeed Score
    |> required "value"   ( map toScoreValue string )
    |> required "name"    string
    |> required "message" string


decodeDetailedScore : Decoder DetailedScore
decodeDetailedScore =
  succeed DetailedScore
    |> required "value"   ( map toScoreValue string )
    |> required "name"    string
    |> required "message" string
    |> required "details" value

decodeSystemUpdatesDetails : Decoder SystemUpdatesDetails
decodeSystemUpdatesDetails =
  succeed SystemUpdatesDetails
    |> optional "update"      (map Just int) Nothing
    |> optional "enhancement" (map Just int) Nothing
    |> optional "security"    (map Just int) Nothing
    |> optional "bugfix"      (map Just int) Nothing

toScoreValue : String -> ScoreValue
toScoreValue str =
   case str of
    "A" -> A
    "B" -> B
    "C" -> C
    "D" -> D
    "E" -> E
    "F" -> F
    _   -> X
