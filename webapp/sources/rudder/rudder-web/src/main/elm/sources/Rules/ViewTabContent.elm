module Rules.ViewTabContent exposing (..)

import Html exposing (..)
import Html.Attributes exposing (attribute, class, type_)
import Html.Events exposing (onClick)
import Html.Lazy
import Rules.DataTypes exposing (..)
import Rules.ViewRepairedReports exposing (technicalLogsTab)
import Rules.ViewTabDirectives exposing (directivesComplianceTab, selectDirectivesTab)
import Rules.ViewTabGroups exposing (groupsTab)
import Rules.ViewTabInformation exposing (informationTab)
import Rules.ViewTabNodes exposing (nodesTab)
import Rules.ViewTabRecentActivity exposing (recentActivityTab)



--
-- This file contains all methods to display the details of the selected rule.
--


tabContent : Model -> RuleDetails -> Html Msg
tabContent model details =
    case details.tab of
        Information ->
            informationTab model details

        ComplianceTab sortBy ->
            viewComplianceTab model details sortBy

        Directives ->
            selectDirectivesTab details model

        Groups ->
            groupsTab model details

        TechnicalLogs ->
            technicalLogsTab model details

        Rules ->
            div [] []

        RecentActivity ->
            recentActivityTab model.activityTable


viewComplianceTab : Model -> RuleDetails -> ComplianceSortBy -> Html Msg
viewComplianceTab model details sortBy =
    div [ class "tab-table-content" ]
        (List.append
            [ ul [ class "nav nav-underline" ]
                [ li [ class "nav-item" ]
                    [ button
                        [ attribute "role" "tab"
                        , type_ "button"
                        , class
                            ("nav-link "
                                ++ (if sortBy == ByDirective then
                                        " active"

                                    else
                                        ""
                                   )
                            )
                        , onClick (UpdateRuleForm { details | tab = ComplianceTab ByDirective })
                        ]
                        [ text "By directive" ]
                    ]
                , li [ class "nav-item" ]
                    [ button
                        [ attribute "role" "tab"
                        , type_ "button"
                        , class
                            ("nav-link "
                                ++ (if sortBy == ByNode then
                                        " active"

                                    else
                                        ""
                                   )
                            )
                        , onClick (UpdateRuleForm { details | tab = ComplianceTab ByNode })
                        ]
                        [ text "By node" ]
                    ]
                ]
            ]
            [ case sortBy of
                ByDirective ->
                    Html.Lazy.lazy (directivesComplianceTab details) model

                ByNode ->
                    Html.Lazy.lazy (nodesTab details) model
            ]
        )
