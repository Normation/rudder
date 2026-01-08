module NodeCompliance.JsonDecoder exposing (..)

import Json.Decode exposing (..)
import Json.Decode.Pipeline exposing (..)
import Json.Decode.Field exposing (require)

import NodeCompliance.DataTypes exposing (..)
import Compliance.DataTypes exposing (..)
import Compliance.JsonDecoder exposing (decodeComplianceDetails, decodeSkippedDirectiveDetails)


decodeGetPolicyMode : Decoder String
decodeGetPolicyMode =
  at ["data", "settings", "global_policy_mode" ] string

decodeGetNodeCompliance : Decoder NodeCompliance
decodeGetNodeCompliance =
  at ["data", "nodes" ] (index 0 decodeNodeCompliance)

decodeNodeCompliance : Decoder NodeCompliance
decodeNodeCompliance =
  succeed NodeCompliance
    |> required "id"         (map NodeId string)
    |> required "name"       string
    |> required "compliance" float
    |> required "policyMode" string
    |> required "complianceDetails" decodeComplianceDetails
    |> required "rules"      (list decodeRuleCompliance)

decodeRuleCompliance  : Decoder RuleCompliance
decodeRuleCompliance =
  succeed RuleCompliance
    |> required "id"         (map NodeId string)
    |> required "name"       string
    |> required "compliance" float
    |> required "policyMode" string
    |> required "complianceDetails" decodeComplianceDetails
    |> required "directives" (list (decodeDirectiveCompliance "values" decodeValueCompliance ))

decodeDirectiveCompliance : String -> Decoder a -> Decoder (DirectiveCompliance a)
decodeDirectiveCompliance elem decoder =
  succeed DirectiveCompliance
    |> required "id"         (map DirectiveId string)
    |> required "name"       string
    |> required "compliance" float
    |> required "policyMode" string
    |> required "complianceDetails" decodeComplianceDetails
    |> optional "skippedDetails" (maybe decodeSkippedDirectiveDetails) Nothing
    |> required "components" (list (decodeComponentCompliance elem decoder ))

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

decodeComponentValueCompliance : String -> Decoder a -> Decoder (ComponentValueCompliance a)
decodeComponentValueCompliance elem decoder =
  succeed ComponentValueCompliance
    |> required "name"       string
    |> required "compliance" float
    |> required "complianceDetails" decodeComplianceDetails
    |> required elem (list decoder)

decodeComponentCompliance : String -> Decoder a -> Decoder (ComponentCompliance a)
decodeComponentCompliance elem decoder =
  oneOf [
     map  (\b -> Block b) <| decodeBlockCompliance elem decoder ()
  ,  map  (\v -> Value v) <| decodeComponentValueCompliance elem decoder
  ]

decodeBlockCompliance :  String -> Decoder a -> () -> Decoder (BlockCompliance a)
decodeBlockCompliance elem decoder _ =
  require "name" string <| \name ->
  require "compliance" float <| \compliance ->
  require "complianceDetails" decodeComplianceDetails <| \details ->
  require "components" (list (decodeComponentCompliance elem decoder))   <| \components ->
    succeed ({ component = name, compliance = compliance, complianceDetails = details, components =  components } )

decodeReport : Decoder Report
decodeReport =
  succeed Report
    |> required "status"      string
    |> optional "message"    (maybe  string) Nothing

decodeValueCompliance : Decoder ValueCompliance
decodeValueCompliance =
  succeed ValueCompliance
    |> required "value"      string
    |> required "reports" (list decodeReport)
