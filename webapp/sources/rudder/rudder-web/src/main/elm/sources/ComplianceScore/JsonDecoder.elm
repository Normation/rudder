module ComplianceScore.JsonDecoder exposing (..)

import Compliance.DataTypes exposing (ComplianceDetails)
import Compliance.JsonDecoder exposing (decodeComplianceDetails)
import Json.Decode exposing (..)
import Score.DataTypes exposing (Score)
import Score.JsonDecoder exposing (decodeScore)


decodeComplianceScore : Decoder ComplianceDetails
decodeComplianceScore =
    decodeComplianceDetails
