module Compliance.Html exposing (..)

import Compliance.DataTypes exposing (ComplianceDetails, ComplianceFilters)
import Compliance.HtmlString as HtmlString
import Html exposing (Html)
import Html.String

buildComplianceBar : ComplianceFilters -> ComplianceDetails -> Html msg
buildComplianceBar filters complianceDetails = HtmlString.buildComplianceBar filters complianceDetails |> Html.String.toHtml