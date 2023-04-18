module DirectiveCompliance.JsonDecoder exposing (..)

import Dict exposing (Dict)
import Json.Decode exposing (..)
import Json.Decode.Pipeline exposing (..)
import Json.Decode.Field exposing (..)

import DirectiveCompliance.DataTypes exposing (..)


decodeGetPolicyMode : Decoder String
decodeGetPolicyMode =
  at ["data", "settings", "global_policy_mode" ] string


decodeGetDirectiveCompliance : Decoder DirectiveCompliance
decodeGetDirectiveCompliance =
  at ["data", "directiveCompliance" ] decodeDirectiveCompliance

decodeDirectiveCompliance : Decoder DirectiveCompliance
decodeDirectiveCompliance =
  succeed DirectiveCompliance
    |> required "compliance" float
    |> required "policyMode"       string
    |> required "complianceDetails" decodeComplianceDetails
    |> required "rules" (list (decodeRuleCompliance "nodes" decodeNodeCompliance) )
    |> required "nodes" (list decodeRuleComplianceByNode )

decodeRuleComplianceByNode  : Decoder NodeCompliance
decodeRuleComplianceByNode =
  succeed NodeCompliance
    |> required "id"         (map NodeId string)
    |> required "name"       string
    |> required "compliance" float
    |> required "policyMode"       string
    |> required "complianceDetails" decodeComplianceDetails
    |> required "rules" (list (decodeRuleCompliance "values" decodeValueCompliance ))

decodeReport : Decoder Report
decodeReport =
  succeed Report
    |> required "status"      string
    |> optional "message"    (maybe  string) Nothing

decodeNodeCompliance : Decoder NodeValueCompliance
decodeNodeCompliance =
  succeed NodeValueCompliance
    |> required "id"     (map NodeId string)
    |> required "name"    string
    |> required "policyMode"       string
    |> required "compliance" float
    |> required "complianceDetails" decodeComplianceDetails
    |> required "values" (list decodeValueCompliance)

decodeValueCompliance : Decoder ValueCompliance
decodeValueCompliance =
  succeed ValueCompliance
    |> required "value"      string
    |> required "reports" (list decodeReport)

decodeRuleCompliance : String -> Decoder a -> Decoder (RuleCompliance a)
decodeRuleCompliance elem decoder =
  succeed RuleCompliance
    |> required "id"         (map RuleId string)
    |> required "name"       string
    |> required "compliance" float
    |> required "complianceDetails" decodeComplianceDetails
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