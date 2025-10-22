module DirectiveCompliance.JsonDecoder exposing (..)

import Json.Decode exposing (..)
import Json.Decode.Pipeline exposing (..)
import Json.Decode.Field exposing (require)

import DirectiveCompliance.DataTypes exposing (..)
import Compliance.DataTypes exposing (..)
import Compliance.JsonDecoder exposing (decodeComplianceDetails)


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
    |> required "policyMode"       string
    |> required "complianceDetails" decodeComplianceDetails
    |> required "components" (list (decodeComponentCompliance elem decoder ))

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
