module ComplianceUtils exposing (..)

import DataTypes exposing (..)
import Dict
import Dict.Extra
import Html exposing (Html, button, div, i, span, text, h1, h4, ul, li, input, a, p, form, label, textarea, select, option, table, thead, tbody, tr, th, td, small)
import Html.Attributes exposing (id, class, type_, placeholder, value, for, href, colspan, rowspan, style, selected, disabled, attribute)
import Html.Events exposing (onClick, onInput)
import List.Extra
import List
import String exposing (fromFloat)
import Tuple exposing (first, second)
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

getRuleCompliance : Model -> RuleId -> Maybe RuleComplianceGlobal
getRuleCompliance model rId =
  Dict.get rId.value model.rulesCompliance

getAllComplianceValues : ComplianceDetails -> {okStatus : {value : Float, details : String}, nonCompliant : {value : Float, details : String}, error : {value : Float, details : String}, unexpected : {value : Float, details : String}, pending : {value : Float, details : String}, reportsDisabled : {value : Float, details : String}, noReport : {value : Float, details : String}}
getAllComplianceValues complianceDetails =
  let
    barContent : List (Float, String) -> String
    barContent lst =
      let
        content = lst
          |> List.map (\x ->
            if first x > 0 then
              "<li>" ++ second x ++ ": " ++ String.fromFloat (first x) ++ "%</li>"
            else
              ""
          )
      in
        String.join "" (List.append ["</ul>"] ("<ul>" :: content))

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

    okStatus        = valSuccessNotApplicable + valSuccessAlreadyOK + valSuccessRepaired + valAuditCompliant + valAuditNotApplicable
    nonCompliant    = valAuditNonCompliant
    error           = valError + valAuditError
    unexpected      = valUnexpectedUnknownComponent + valUnexpectedMissingComponent + valBadPolicyMode
    pending         = valApplying
    reportsDisabled = valReportsDisabled
    noReport        = valNoReport

    okStatusText =
      if okStatus > 0 then
        let
          checks  =
            [ ( valSuccessAlreadyOK     , "Success (enforce)"        )
            , ( valAuditCompliant       , "Compliant"                )
            , ( valSuccessRepaired      , "Repaired"                 )
            , ( valSuccessNotApplicable , "Not applicable (enforce)" )
            , ( valAuditNotApplicable   , "Not applicable (audit)"   )
            ]
          content = barContent checks
        in
          content
      else
        ""

    nonCompliantText =
      if nonCompliant > 0 then
        barContent [( nonCompliant , "Non compliance" )]
      else
        ""

    errorText =
      if error > 0 then
        let
          checks  =
            [ ( valError      , "Errors (enforce)" )
            , ( valAuditError , "Errors (audit)"   )
            ]
          content = barContent checks
        in
            content
      else
        ""

    unexpectedText =
      if unexpected > 0 then
        let
          checks  =
            [ ( valUnexpectedUnknownComponent , "Unknown reports" )
            , ( valUnexpectedMissingComponent , "Missing reports" )
            , ( valBadPolicyMode              , "Not supported mixed mode on directive from same Technique" )
            ]
          content = barContent checks
        in
            content
      else
        ""

    pendingText =
      if pending > 0 then
        barContent [( valApplying , "Applying" )]
      else
        ""

    reportsDisabledText =
      if reportsDisabled > 0 then
        barContent [( valReportsDisabled , "Reports Disabled" )]
      else
        ""

    noReportText =
      if noReport > 0 then
        barContent [( valNoReport , "No report" )]
      else
        ""

    allComplianceValues =
      { okStatus        = { value = okStatus        , details = okStatusText        }
      , nonCompliant    = { value = nonCompliant    , details = nonCompliantText    }
      , error           = { value = error           , details = errorText           }
      , unexpected      = { value = unexpected      , details = unexpectedText      }
      , pending         = { value = pending         , details = pendingText         }
      , reportsDisabled = { value = reportsDisabled , details = reportsDisabledText }
      , noReport        = { value = noReport        , details = noReportText        }
      }
  in
    allComplianceValues

getDirectiveComputedCompliance : (DirectiveCompliance value) -> Float
getDirectiveComputedCompliance dc =
  let
    allComplianceValues = getAllComplianceValues dc.complianceDetails
  in
    if ( allComplianceValues.okStatus.value + allComplianceValues.nonCompliant.value + allComplianceValues.error.value + allComplianceValues.unexpected.value + allComplianceValues.pending.value + allComplianceValues.reportsDisabled.value + allComplianceValues.noReport.value == 0 ) then
      -1.0
    else
      dc.compliance

getNodeComputedCompliance : NodeCompliance -> Float
getNodeComputedCompliance nc =
  let
    allComplianceValues = getAllComplianceValues nc.complianceDetails
  in
    if ( allComplianceValues.okStatus.value + allComplianceValues.nonCompliant.value + allComplianceValues.error.value + allComplianceValues.unexpected.value + allComplianceValues.pending.value + allComplianceValues.reportsDisabled.value + allComplianceValues.noReport.value == 0 ) then
      -1.0
    else
      nc.compliance

mergeCompliance : ComplianceDetails -> ComplianceDetails -> ComplianceDetails
mergeCompliance c1 c2 =
  let
    sumMaybeFloat : Maybe Float -> Maybe Float -> Maybe Float
    sumMaybeFloat  mf1 mf2 =
      case (mf1,mf2) of
        ( Nothing , Nothing ) -> Nothing
        ( Just f1 , Just f2 ) -> Just (f1+f2)
        ( a       , Nothing ) -> a
        ( Nothing , b       ) -> b
    toMaybeFloat = \ fun -> sumMaybeFloat (fun c1) (fun c2)
  in
    ComplianceDetails
      (toMaybeFloat .successNotApplicable)
      (toMaybeFloat .successAlreadyOK)
      (toMaybeFloat .successRepaired)
      (toMaybeFloat .error)
      (toMaybeFloat .auditCompliant)
      (toMaybeFloat .auditNonCompliant)
      (toMaybeFloat .auditError)
      (toMaybeFloat .auditNotApplicable)
      (toMaybeFloat .unexpectedUnknownComponent)
      (toMaybeFloat .unexpectedMissingComponent)
      (toMaybeFloat .noReport)
      (toMaybeFloat .reportsDisabled)
      (toMaybeFloat .applying)
      (toMaybeFloat .badPolicyMode)



