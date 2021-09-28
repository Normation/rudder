module ViewUtilsCompliance exposing (..)

import DataTypes exposing (..)
import Html exposing (Html, button, div, i, span, text, h1, h4, ul, li, input, a, p, form, label, textarea, select, option, table, thead, tbody, tr, th, td, small)
import Html.Attributes exposing (id, class, type_, placeholder, value, for, href, colspan, rowspan, style, selected, disabled, attribute)
import Html.Events exposing (onClick, onInput)
import List.Extra
import List
import String exposing (fromFloat)
import ApiCalls exposing (..)

getCompliance : Float -> String -> Html msg
getCompliance val t =
  if val > 0 then
    div[class ("progress-bar progress-bar-" ++ t), style "flex" (fromFloat val)][text ((fromFloat val) ++ "%")]
  else
    text ""

getValueCompliance : Maybe Float -> Float
getValueCompliance f =
  case f of
    Just v  -> v
    Nothing -> 0


getRuleCompliance : Model -> RuleId -> Maybe RuleCompliance
getRuleCompliance model rId =
  List.Extra.find (\c -> c.ruleId == rId) model.rulesCompliance

getAllComplianceValues : ComplianceDetails -> {okStatus : Float, nonCompliant : Float, error : Float, unexpected : Float, pending : Float, reportsDisabled : Float, noReport : Float}
getAllComplianceValues complianceDetails =
  let
    valSuccessNotApplicable       = getValueCompliance complianceDetails.successNotApplicable       -- 0
    valSuccessAlreadyOK           = getValueCompliance complianceDetails.successAlreadyOK           -- 0
    valSuccessRepaired            = getValueCompliance complianceDetails.successRepaired            -- 0
    valAuditCompliant             = getValueCompliance complianceDetails.auditCompliant             -- 0
    valAuditNotApplicable         = getValueCompliance complianceDetails.auditNotApplicable         -- 0

    valAuditNonCompliant          = getValueCompliance complianceDetails.auditNonCompliant          -- 1

    valError                      = getValueCompliance complianceDetails.error                      -- 2
    valAuditError                 = getValueCompliance complianceDetails.auditError                 -- 2

    valUnexpectedUnknownComponent = getValueCompliance complianceDetails.unexpectedUnknownComponent -- 3
    valUnexpectedMissingComponent = getValueCompliance complianceDetails.unexpectedMissingComponent -- 3
    valBadPolicyMode              = getValueCompliance complianceDetails.badPolicyMode              -- 3

    valApplying                   = getValueCompliance complianceDetails.applying                   -- 4

    valReportsDisabled            = getValueCompliance complianceDetails.reportsDisabled            -- 5

    valNoReport                   = getValueCompliance complianceDetails.noReport                   -- 6
  in
    { okStatus        = valSuccessNotApplicable + valSuccessAlreadyOK + valSuccessRepaired + valAuditCompliant + valAuditNotApplicable
    , nonCompliant    = valAuditNonCompliant
    , error           = valError + valAuditError
    , unexpected      = valUnexpectedUnknownComponent + valUnexpectedMissingComponent + valBadPolicyMode
    , pending         = valApplying
    , reportsDisabled = valReportsDisabled
    , noReport        = valNoReport
    }

buildComplianceBar : ComplianceDetails -> Html Msg
buildComplianceBar complianceDetails=
  let
    allComplianceValues = getAllComplianceValues complianceDetails
  in
    if ( allComplianceValues.okStatus + allComplianceValues.nonCompliant + allComplianceValues.error + allComplianceValues.unexpected + allComplianceValues.pending + allComplianceValues.reportsDisabled + allComplianceValues.noReport == 0 ) then
      div[ class "text-muted"][text "No data available"]
    else
      div[ class "progress progress-flex"]
      [ getCompliance allComplianceValues.okStatus        "success"
      , getCompliance allComplianceValues.nonCompliant    "audit-noncompliant"
      , getCompliance allComplianceValues.error           "error"
      , getCompliance allComplianceValues.unexpected      "unknown"
      , getCompliance allComplianceValues.pending         "pending"
      , getCompliance allComplianceValues.reportsDisabled "reportsdisabled"
      , getCompliance allComplianceValues.noReport        "no-report"
      ]

getDirectiveComputedCompliance : DirectiveCompliance -> Float
getDirectiveComputedCompliance dc =
  let
    allComplianceValues = getAllComplianceValues dc.complianceDetails
  in
    if ( allComplianceValues.okStatus + allComplianceValues.nonCompliant + allComplianceValues.error + allComplianceValues.unexpected + allComplianceValues.pending + allComplianceValues.reportsDisabled + allComplianceValues.noReport == 0 ) then
      -1.0
    else
      dc.compliance