module ComplianceScore.JsonDecoder exposing (..)

import Json.Decode exposing (Decoder, andThen, string, int, list, succeed, at, map)
import Json.Decode.Pipeline exposing (required, optional)

import ComplianceScore.DataTypes exposing (..)
import Compliance.JsonDecoder exposing (decodeComplianceDetails)

decodeGetComplianceScore : Decoder GlobalComplianceScore
decodeGetComplianceScore =
  at [ "data" ] decodeComplianceScore

decodeComplianceScore : Decoder GlobalComplianceScore
decodeComplianceScore =
  succeed GlobalComplianceScore
    |> required "value"   ( string |> andThen (\s -> toScoreValue s) )
    |> required "message" string
    |> required "details" (list decodeGlobalScoreDetails)

decodeGlobalScoreDetails : Decoder GlobalScoreDetails
decodeGlobalScoreDetails =
  succeed GlobalScoreDetails
    |> required "value"   ( string |> andThen (\s -> toScoreValue s) )
    |> required "name"    string
    |> required "message" string

decodeGetScoreDetails : Decoder ScoreDetails
decodeGetScoreDetails =
  at [ "data" ] decodeScoreDetails

decodeScoreDetails : Decoder ScoreDetails
decodeScoreDetails =
  succeed ScoreDetails
    |> required "compliance"     decodeComplianceScoreDetails
    |> optional "system-updates" (map Just decodeSystemUpdatesScoreDetails) Nothing

decodeComplianceScoreDetails : Decoder ComplianceScoreDetails
decodeComplianceScoreDetails =
  succeed ComplianceScoreDetails
    |> required "value"   ( string |> andThen (\s -> toScoreValue s) )
    |> required "name"    string
    |> required "message" string
    |> required "details" decodeComplianceDetails

decodeSystemUpdatesScoreDetails : Decoder SystemUpdatesScoreDetails
decodeSystemUpdatesScoreDetails =
  succeed SystemUpdatesScoreDetails
    |> required "value"   ( string |> andThen (\s -> toScoreValue s) )
    |> required "name"    string
    |> required "message" string
    |> required "details" decodeSystemUpdatesDetails

decodeSystemUpdatesDetails : Decoder SystemUpdatesDetails
decodeSystemUpdatesDetails =
  succeed SystemUpdatesDetails
    |> optional "update"      (map Just int) Nothing
    |> optional "enhancement" (map Just int) Nothing
    |> optional "security"    (map Just int) Nothing
    |> optional "bugfix"      (map Just int) Nothing

toScoreValue : String -> Decoder ScoreValue
toScoreValue str =
  succeed ( case str of
    "A" -> A
    "B" -> B
    "C" -> C
    "D" -> D
    "E" -> E
    "F" -> F
    _   -> X
  )