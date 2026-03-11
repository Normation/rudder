module Rules.ViewTabContent exposing (..)

import Html exposing (..)

import Rules.DataTypes exposing (..)
import Rules.ViewTabInformation exposing (informationTab)
import Rules.ViewTabDirectives exposing (directivesTab)
import Rules.ViewTabNodes exposing (nodesTab)
import Rules.ViewTabGroups exposing (groupsTab)
import Rules.ViewRepairedReports exposing (technicalLogsTab)


--
-- This file contains all methods to display the details of the selected rule.
--

tabContent: Model -> RuleDetails  -> Html Msg
tabContent model details =
    case details.tab of
      Information   -> informationTab model details
      Directives    -> directivesTab model details
      Nodes         -> nodesTab model details
      Groups        -> groupsTab model details
      TechnicalLogs -> technicalLogsTab model details
      Rules         -> div [] []
