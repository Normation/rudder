module Compliance.JsonDecoder exposing (..)

import Json.Decode exposing (Decoder, float, map, string, succeed)
import Json.Decode.Pipeline exposing (optional, required)

import Compliance.DataTypes exposing (..)

decodeComplianceDetails : Decoder ComplianceDetails
decodeComplianceDetails =
  succeed ComplianceDetails
    |> optional "successNotApplicable"       (map Just float) Nothing
    |> optional "successAlreadyOK"           (map Just float) Nothing
    |> optional "successRepaired"            (map Just float) Nothing
    |> optional "error"                      (map Just float) Nothing
    |> optional "auditCompliant"             (map Just float) Nothing
    |> optional "auditNonCompliant"          (map Just float) Nothing
    |> optional "auditError"                 (map Just float) Nothing
    |> optional "auditNotApplicable"         (map Just float) Nothing
    |> optional "unexpectedUnknownComponent" (map Just float) Nothing
    |> optional "unexpectedMissingComponent" (map Just float) Nothing
    |> optional "noReport"                   (map Just float) Nothing
    |> optional "reportsDisabled"            (map Just float) Nothing
    |> optional "applying"                   (map Just float) Nothing
    |> optional "badPolicyMode"              (map Just float) Nothing


decodeSkippedDirectiveDetails : Decoder SkippedDetails
decodeSkippedDirectiveDetails =
  succeed SkippedDetails
    |> required "overridingRuleId" string
    |> required "overridingRuleName" string