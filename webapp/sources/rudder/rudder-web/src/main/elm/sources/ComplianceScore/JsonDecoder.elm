module ComplianceScore.JsonDecoder exposing (..)

import Compliance.DataTypes exposing (ComplianceDetails)

import Json.Decode exposing (..)
import Score.DataTypes exposing (Score)
import Score.JsonDecoder exposing (decodeScore)
import Compliance.JsonDecoder exposing (decodeComplianceDetails)

decodeComplianceScore : Decoder  ComplianceDetails
decodeComplianceScore =
  decodeComplianceDetails