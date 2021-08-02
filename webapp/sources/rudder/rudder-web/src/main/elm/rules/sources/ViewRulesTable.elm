module ViewRulesTable exposing (..)

import DataTypes exposing (..)
import Html exposing (Html, button, div, i, span, text, h1, h4, ul, li, input, a, p, form, label, textarea, select, option, table, thead, tbody, tr, th, td, small)
import Html.Attributes exposing (id, class, type_, placeholder, value, for, href, colspan, rowspan, style, selected, disabled, attribute)
import Html.Events exposing (onClick, onInput)
import List.Extra
import List
import String exposing ( fromFloat)
import NaturalOrdering exposing (compareOn)
import ApiCalls exposing (..)

--
-- This file contains all methods to display the Rules table
--


getListRules : Category Rule -> List (Rule)
getListRules r = getAllElems r

getListCategories : Category Rule  -> List (Category Rule)
getListCategories r = getAllCats r

buildRulesTable : Model -> List(Html Msg)
buildRulesTable model =
  let
    rulesList      = getListRules model.rulesTree
    categoriesList = getListCategories model.rulesTree

    getCategoryName : String -> String
    getCategoryName id =
      let
        cat = List.Extra.find (.id >> (==) id  ) categoriesList
      in
        case cat of
          Just c -> c.name
          Nothing -> id

    rowTable : Rule -> Html Msg
    rowTable r =
      let
        compliance =
            case List.Extra.find (\c -> c.ruleId == r.id) model.rulesCompliance of
              Just co ->
                let
                  complianceDetails = co.complianceDetails

                  buildComplianceBar : Float -> String -> Html msg
                  buildComplianceBar val t =
                    div[class ("progress-bar progress-bar-" ++ t), style "flex" (fromFloat val)][text ((fromFloat val) ++ "%")]

                  getValueCompliance : Maybe Float -> Float
                  getValueCompliance f =
                    case f of
                      Just v  -> v
                      Nothing -> 0

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
                  noreport        = valNoReport


                in
                  if ( okStatus + nonCompliant + error + unexpected + pending + reportsDisabled + noreport == 0 ) then
                    div[ class "text-muted"][text "No data available"]
                  else
                    div[ class "progress progress-flex"]
                    [ buildComplianceBar okStatus        "success"
                    , buildComplianceBar nonCompliant    "audit-noncompliant"
                    , buildComplianceBar error           "error"
                    , buildComplianceBar unexpected      "unknown"
                    , buildComplianceBar pending         "pending"
                    , buildComplianceBar reportsDisabled "reportsdisabled"
                    , buildComplianceBar noreport        "no-report"
                    ]

              Nothing -> text "No report"
      in
            tr[onClick (OpenRuleDetails r.id)]
            [ td[][ text r.name ]
            , td[][ text (getCategoryName r.categoryId) ]
            , td[][ text (if r.enabled == True then "Enabled" else "Disabled") ]
            , td[][ compliance ]
            , td[][ text ""   ]
            ]
  in
    List.map rowTable rulesList
