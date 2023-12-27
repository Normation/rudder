module ComplianceScore.View exposing (..)

import Compliance.DataTypes exposing (ComplianceDetails)
import Compliance.HtmlString exposing (buildComplianceBar)
import Compliance.Utils exposing (defaultComplianceFilter)
import Html.String exposing (..)
import Html.String.Attributes exposing (..)
import Score.DataTypes exposing (Score)


buildScoreDetails :  ComplianceDetails  -> Html msg
buildScoreDetails details =
   buildComplianceBar defaultComplianceFilter details
