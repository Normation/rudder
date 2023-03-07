module JsonDecoder exposing (..)

import DataTypes exposing (..)
import Dict exposing (Dict)
import Json.Decode exposing (..)
import Json.Decode.Pipeline exposing (..)
import Json.Decode.Field exposing (..)


decodeGetPolicyMode : Decoder String
decodeGetPolicyMode =
  at ["data", "settings", "global_policy_mode" ] string

decodeGetRules : Decoder (List Rule)
decodeGetRules =
  at ["data", "rules" ] (list decodeRule)

decodeGetDirectiveCompliance : Decoder DirectiveCompliance
decodeGetDirectiveCompliance =
  at ["data", "directiveCompliance" ] decodeDirectiveCompliance

decodeDirectiveCompliance : Decoder DirectiveCompliance
decodeDirectiveCompliance =
  succeed DirectiveCompliance
    |> required "compliance" float
    |> required "complianceDetails" decodeComplianceDetails
    |> required "rules" (list (decodeRuleCompliance "nodes" decodeNodeCompliance) )
    |> required "nodes" (list decodeRuleComplianceByNode )

decodeRuleComplianceByNode  : Decoder NodeCompliance
decodeRuleComplianceByNode =
  succeed NodeCompliance
    |> required "id"         (map NodeId string)
    |> required "name"       string
    |> required "compliance" float
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
    |> required "compliance" float
    |> required "complianceDetails" decodeComplianceDetails
    |> required "values" (list decodeValueCompliance)

decodeValueCompliance : Decoder ValueCompliance
decodeValueCompliance =
  succeed ValueCompliance
    |> required "value"      string
    |> required "reports" (list decodeReport)

decodeRule : Decoder Rule
decodeRule =
  succeed Rule
    |> required "id"              (map RuleId string)
    |> required "displayName"      string
    |> required "categoryId"       string
    |> required "shortDescription" string
    |> required "longDescription"  string
    |> required "enabled"          bool
    |> required "system"           bool
    |> required "directives"      (list (map DirectiveId string))
    |> required "targets"         (list decodeTargets)
    |> required "policyMode"       string
    |> required "status"           decodeStatus
    |> required "tags"            (list (keyValuePairs string) |> andThen toTags)

toTags : List (List ( String, String )) -> Decoder (List Tag)
toTags lst =
  let
    concatList = List.concat lst
  in
    succeed
      ( List.map (\t -> Tag (Tuple.first t) (Tuple.second t)) concatList )

decodeComposition : Decoder RuleTarget
decodeComposition =
  succeed Composition
    |> required "include" (lazy (\_ -> decodeTargets))
    |> required "exclude" (lazy (\_ -> decodeTargets))

decodeAnd: Decoder RuleTarget
decodeAnd =
  succeed And
    |> required "and" (list (lazy (\_ -> decodeTargets)))

decodeOr: Decoder RuleTarget
decodeOr =
  succeed Or
    |> required "or" (list (lazy (\_ -> decodeTargets)))

decodeTargets : Decoder RuleTarget
decodeTargets =
  oneOf
  [ lazy (\_ -> decodeComposition)
  , lazy (\_ -> decodeAnd)
  , lazy (\_ -> decodeOr)
  , map
    (\s ->
      if String.startsWith "group:" s then
        NodeGroupId (String.dropLeft 6 s)
      else if String.startsWith "node:" s then
        Node (String.dropLeft 5 s)
      else Special s
    ) string
  ]

decodeStatus : Decoder RuleStatus
decodeStatus =
  succeed RuleStatus
    |> required "value"    string
    |> optional "details" (maybe string) Nothing


decodeGetNodesList : Decoder (List NodeInfo)
decodeGetNodesList =
  at ["data", "nodes" ] (list decodeNodeInfo)

decodeNodeInfo : Decoder NodeInfo
decodeNodeInfo =
  succeed NodeInfo
    |> required "id"          string
    |> required "hostname"    string
    |> required "description" string
    |> required "policyMode"  string

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