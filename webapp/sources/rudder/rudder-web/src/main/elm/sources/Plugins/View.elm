module Plugins.View exposing (..)

import Html exposing (Html, div, h1, i, label, li, p, span, text, ul)
import Html.Attributes exposing (class)
import Plugins.DataTypes exposing (..)
import Plugins.JsonEncoder exposing (..)
import Time.Iso8601


view : Model -> Html Msg
view model =
    let
        plugins =
            model.plugins

        displayPlugin p =
            li []
                [ ul []
                    [ li [] [ text ("ID: " ++ p.id) ]
                    , li [] [ text ("Name: " ++ p.name) ]
                    , li [] [ text ("Description: " ++ p.description) ]
                    , li [] [ text ("ABI version: " ++ p.abiVersion) ]
                    , li [] [ text ("Version: " ++ p.version) ]
                    , li []
                        [ text <|
                            "Plugin Type: "
                                ++ pluginTypeText p.pluginType
                        ]
                    , li [] [ text ("Errors: " ++ (p.errors |> List.map .message |> String.join ", ")) ]
                    , li []
                        [ text <| "Status: " ++ pluginStatusText p.status
                        ]
                    , li []
                        [ text <|
                            "License: "
                                ++ (case p.license of
                                        Just license ->
                                            "Licensee: " ++ license.licensee ++ ", Allowed nodes: " ++ String.fromInt license.allowedNodesNumber ++ ", Supported versions: " ++ license.supportedVersions ++ ", Start date: " ++ Time.Iso8601.fromZonedDateTime license.startDate ++ ", End date: " ++ Time.Iso8601.fromZonedDateTime license.endDate

                                        Nothing ->
                                            "No License"
                                   )
                        ]
                    ]
                ]

        content =
            div [ class "mb-1" ]
                [ div [ class "info" ]
                    (if List.isEmpty plugins then
                        [ label []
                            [ text "Plugins"
                            ]
                        , i [ class "text-secondary" ] [ text "There are no plugins installed" ]
                        ]

                     else
                        [ ul []
                            (li []
                                [ text "Plugins"
                                ]
                                :: List.map displayPlugin plugins
                            )
                        ]
                    )
                ]
    in
    div [ class "rudder-template" ]
        [ div [ class "one-col w-100" ]
            [ div [ class "main-header" ]
                [ div [ class "header-title" ]
                    [ h1 []
                        [ span [] [ text "Plugins" ]
                        ]
                    ]
                , div [ class "header-description" ]
                    [ p [] [ text "This is the management page for Rudder plugins" ]
                    ]
                ]
            , div [ class "one-col-main overflow-auto h-100" ]
                [ div [ class "d-flex flex-column info-container p-3 pb-5 w-100 h-100 overflow-auto" ]
                    [ div [ class "col-12 col-xl-10" ] [ content ]
                    ]
                ]
            ]
        ]


pluginStatusText : PluginStatus -> String
pluginStatusText status =
    case status of
        Enabled ->
            "Enabled"

        Disabled ->
            "Disabled"

        Uninstalled ->
            "Uninstalled"


pluginTypeText : PluginType -> String
pluginTypeText pluginType =
    case pluginType of
        Webapp ->
            "Webapp"

        Integration ->
            "Integration"
