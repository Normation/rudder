module Compliance.HtmlString exposing (..)

import Compliance.DataTypes exposing (ComplianceDetails, ComplianceFilters)
import Compliance.Utils exposing (buildTooltipContent, filterCompliance, getAllComplianceValues, sumPercent)
import Html.String exposing (..)
import Html.String.Attributes exposing (..)
import String exposing (fromFloat)


buildComplianceBar : ComplianceFilters -> ComplianceDetails -> Html msg
buildComplianceBar filters complianceDetails =
  let
    filteredCompliance = filterCompliance complianceDetails filters
    displayCompliance : {value : Float, rounded : Int, details : String} -> String -> Html msg
    displayCompliance compliance className =
      if compliance.value > 0 then
        let
          --Hide the compliance text if the value is too small (less than 3%)
          realPercent = compliance.value / (sumPercent filteredCompliance) * 100
          realRounded = Basics.floor realPercent
          complianceTxt = if realRounded < 3 then "" else String.fromInt (realRounded) ++ "%"
        in
          div [class ("progress-bar progress-bar-" ++ className), attribute "data-bs-toggle" "tooltip", attribute "data-bs-placement" "top", title (buildTooltipContent "Compliance" compliance.details), style "flex" (fromFloat realPercent)]
          [ text complianceTxt ]
      else
        text ""

    allComplianceValues = getAllComplianceValues filteredCompliance

  in
    if ( allComplianceValues.okStatus.value + allComplianceValues.nonCompliant.value + allComplianceValues.error.value + allComplianceValues.unexpected.value + allComplianceValues.pending.value + allComplianceValues.reportsDisabled.value + allComplianceValues.noReport.value == 0 ) then
      div[ class "text-muted"][text "No data available"]
    else
      div[ class "progress progress-flex"]
      [ displayCompliance allComplianceValues.okStatus        "success"
      , displayCompliance allComplianceValues.nonCompliant    "audit-noncompliant"
      , displayCompliance allComplianceValues.error           "error"
      , displayCompliance allComplianceValues.unexpected      "unknown progress-bar-striped"
      , displayCompliance allComplianceValues.pending         "pending progress-bar-striped"
      , displayCompliance allComplianceValues.reportsDisabled "reportsdisabled"
      , displayCompliance allComplianceValues.noReport        "no-report"
      ]
