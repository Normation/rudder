module Plugins.View exposing (..)

import Html exposing (Html, button, div, h1, h2, i, input, label, li, p, span, text, ul)
import Html.Attributes exposing (checked, class, disabled, for, id, type_)
import Html.Events exposing (onCheck, onClick)
import Plugins.ApiCalls exposing (changePluginStatus, installPlugins, removePlugins)
import Plugins.DataTypes exposing (..)
import Plugins.JsonEncoder exposing (..)
import Time.Iso8601


view : Model -> Html Msg
view model =
    let
        plugins =
            model.plugins

        ui =
            model.ui

        selected =
            ui.selected

        displayPlugin p =
            li []
                [ ul []
                    [ input [ id p.id, type_ "checkbox", checked (List.member p.id selected), onCheck (checkOne p.id) ] []
                    , li [] [ text ("ID: " ++ p.id) ]
                    , li [] [ text ("Name: " ++ p.name) ]
                    , li [] [ text ("Description: " ++ p.description) ]
                    , li [] [ text ("ABI version: " ++ p.abiVersion) ]
                    , li [] [ text ("Version: " ++ p.version) ]
                    , li [] [ text ("Plugin Type: " ++ pluginTypeText p.pluginType) ]
                    , li [] [ text ("Errors: " ++ (p.errors |> List.map .message |> String.join ", ")) ]
                    , li [] [ text ("Status: " ++ pluginStatusText p.status) ]
                    , li [] [ text ("License: " ++ displayPluginLicense p.license) ]
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
                        [ h2 []
                            [ text "All plugins"
                            ]
                        , headerSection model
                        , ul []
                            (List.map displayPlugin plugins)
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


displayPluginLicense : Maybe LicenseInfo -> String
displayPluginLicense l =
    case l of
        Just license ->
            "Licensee: "
                |> String.append license.licensee
                |> String.append ", Allowed nodes: "
                |> String.append (String.fromInt license.allowedNodesNumber)
                |> String.append ", Supported versions: "
                |> String.append license.supportedVersions
                |> String.append ", Start date: "
                |> String.append (Time.Iso8601.fromZonedDateTime license.startDate)
                |> String.append ", End date: "
                |> String.append (Time.Iso8601.fromZonedDateTime license.endDate)

        Nothing ->
            "No License"


pluginTypeText : PluginType -> String
pluginTypeText pluginType =
    case pluginType of
        Webapp ->
            "Webapp"

        Integration ->
            "Integration"


checkOne : PluginId -> Bool -> Msg
checkOne id =
    CheckSelection
        << (\check ->
                if check then
                    SelectOne id

                else
                    UnselectOne id
           )


checkAll : Bool -> Msg
checkAll =
    CheckSelection
        << (\check ->
                if check then
                    SelectAll

                else
                    UnselectAll
           )


actionButtons : Model -> List (Html Msg)
actionButtons model =
    [ button [ class "btn btn-default", onClick (CallApi (installPlugins model.ui.selected)) ] [ text "Install", i [ class "fa fa-plus-circle ms-2" ] [] ]
    , button [ class "btn btn-default", onClick (CallApi (removePlugins model.ui.selected)) ] [ text "Uninstall", i [ class "fa fa-minus-circle ms-2" ] [] ]
    , button [ class "btn btn-default", onClick (CallApi (changePluginStatus Enable model.ui.selected)) ] [ text "Enable", i [ class "fa fa-check-circle ms-2" ] [] ]
    , button [ class "btn btn-default", onClick (CallApi (changePluginStatus Disable model.ui.selected)) ] [ text "Disable", i [ class "fa fa-ban ms-2" ] [] ]
    ]


headerSection : Model -> Html Msg
headerSection model =
    let
        selected =
            model.ui.selected

        plugins =
            model.plugins

        isSelectAll =
            not (List.length plugins == List.length selected)

        selectText =
            if isSelectAll then
                "Select all"

            else
                "Unselect all"

        selectHtml =
            [ label [ class "btn btn-default", for "select-plugins" ]
                -- We need a clone checkbox because bootstrap checkbox button hides it
                [ input [ id "clone-select-plugins", type_ "checkbox", class "me-2", checked (not isSelectAll), onCheck checkAll ] []
                , text selectText
                ]
            , input [ id "select-plugins", type_ "checkbox", class "btn-check", checked (not isSelectAll), onCheck checkAll ] []
            ]

        actionHtml =
            actionButtons model
    in
    div [ class "plugins-header" ]
        (selectHtml ++ actionHtml)
