module Plugins.View exposing (..)

import Html exposing (Html, a, button, div, h1, h2, h3, i, input, label, li, p, pre, span, table, tbody, td, text, tr, ul)
import Html.Attributes exposing (attribute, checked, class, disabled, for, href, id, style, target, type_)
import Html.Attributes.Extra exposing (role)
import Html.Events exposing (onCheck, onClick)
import List.Extra
import Maybe.Extra
import Plugins.ApiCalls exposing (..)
import Plugins.DataTypes exposing (..)
import Plugins.JsonEncoder exposing (..)
import String.Extra
import Time.DateTime
import Time.Iso8601
import Time.ZonedDateTime


view : Model -> Html Msg
view model =
    let
        plugins =
            model.plugins

        content =
            div [ class "main-details" ]
                [ displayMainLicense model
                , displaySettingsErrorOrHtml model
                    (if List.isEmpty plugins then
                        i [ class "text-secondary" ] [ text "There are no plugins installed" ]

                     else
                        pluginsSection model
                    )
                ]
    in
    div [ class "rudder-template" ]
        [ div [ class "one-col w-100" ]
            [ div [ class "main-header" ]
                [ div [ class "header-title" ]
                    [ h1 []
                        [ span [] [ text "Plugins management" ]
                        ]
                    ]
                , div [ class "header-description" ]
                    [ p []
                        [ text "Plugins can extend Rudder’s base functionality to add extra features. Learn about available plugins on our "
                        , a [ target "_blank", href "https://www.rudder.io/software/plugins/" ] [ text "website" ]
                        , text " or directly "
                        , a [ target "_blank", href "https://repository.rudder.io/plugins/" ] [ text "download free plugins" ]
                        , text "."
                        ]
                    ]
                ]
            , div [ class "one-col-main" ]
                [ div [ class "template-main" ]
                    [ div [ class "main-container" ] [ content ]
                    ]
                ]
            , displayModal model
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


actionButtons : List (Html Msg)
actionButtons =
    [ button [ class "btn btn-default me-1", onClick (SetModalState (OpenModal Install)) ] [ text "Install", i [ class "fa fa-plus-circle ms-1" ] [] ]
    , button [ class "btn btn-default mx-1", onClick (SetModalState (OpenModal Uninstall)) ] [ text "Uninstall", i [ class "fa fa-minus-circle ms-1" ] [] ]
    , button [ class "btn btn-default mx-1", onClick (SetModalState (OpenModal Enable)) ] [ text "Enable", i [ class "fa fa-check-circle ms-1" ] [] ]
    , button [ class "btn btn-default ms-1", onClick (SetModalState (OpenModal Disable)) ] [ text "Disable", i [ class "fa fa-ban ms-1" ] [] ]
    ]


pluginsSection : Model -> Html Msg
pluginsSection model =
    let
        selected =
            model.ui.selected

        plugins =
            List.sortWith pluginDefaultOrdering model.plugins

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
    in
    div [ class "main-table" ]
        [ div [ class "table-container plugins-container" ]
            [ div [ class "dataTables_wrapper_top table-filter plugins-actions" ]
                [ div [ class "start" ] selectHtml
                , div [ class "end" ] actionButtons
                ]
            , h2 [ class "fs-5 p-3 m-0" ] [ text "Features" ]
            , div [ class "plugins-list" ] (List.map (displayPlugin model) plugins)
            ]
        ]


displayMainLicense : Model -> Html Msg
displayMainLicense model =
    if model.license == noGlobalLicense then
        text ""

    else
        let
            licensees =
                model.license.licensees |> Maybe.map (String.join ", ") |> Maybe.withDefault "-"

            dateOf =
                Time.ZonedDateTime.toDateTime >> Time.DateTime.date >> Time.Iso8601.fromDate

            validityPeriod =
                case ( model.license.startDate, model.license.endDate ) of
                    ( Just start, Just end ) ->
                        "from " ++ dateOf start ++ " to " ++ dateOf end

                    _ ->
                        "-"

            nbNodes =
                model.license.maxNodes |> Maybe.map String.fromInt |> Maybe.withDefault "Unlimited"
        in
        div [ class "main-license" ]
            [ div [ attribute "data-plugin" "statusInformation", class "license-card" ]
                [ div [ id "license-information", class "license-info" ]
                    [ h2 [ class "license-info-title" ] [ span [] [ text "License information" ], i [ class "license-icon ion ion-ribbon-b" ] [] ]
                    , p [ class "license-information-details" ] [ text "The following license information are provided" ]
                    , div [ class "license-information-details" ]
                        [ table [ class "table-license" ]
                            [ tbody []
                                [ tr [] [ td [] [ text "Licensee:" ], td [] [ text licensees ] ]

                                -- in individual plugin only
                                -- , tr [] [ td [] [ text "Supported version:" ], td [] [ text "from 0.0.0-0.0.0 to 99.99.0-99.99.0" ] ]
                                , tr [] [ td [] [ text "Validity period:" ], td [] [ text validityPeriod ] ]
                                , tr [] [ td [] [ text "Allowed number of nodes:" ], td [] [ text nbNodes ] ]
                                ]
                            ]
                        ]
                    ]
                ]
            ]


displaySettingsErrorOrHtml : Model -> Html Msg -> Html Msg
displaySettingsErrorOrHtml model orHtml =
    case model.ui.settingsError of
        Just ( message, details ) ->
            div [ class "callout-fade callout-warning overflow-scroll" ]
                [ p [] [ i [ class "fa fa-warning" ] [], text message ]
                , p [] [ a [ target "_blank", href (model.contextPath ++ "/secure/administration/settings") ] [ text "Open Rudder account settings", i [ class "fa fa-external-link ms-1" ] [] ] ]
                , p []
                    [ a [ class "btn btn-default", attribute "data-bs-toggle" "collapse", href "#collapseSettingsError", role "button", attribute "aria-expanded" "false", attribute "aria-controls" "collapseSettingsError" ]
                        [ text "See details" ]
                    ]
                , div [ class "collapse", id "collapseSettingsError" ]
                    [ div [ class "card card-body" ]
                        [ pre [ class "command-output" ]
                            [ text details
                            ]
                        ]
                    ]
                ]

        Nothing ->
            orHtml


findLicenseNeededError : List PluginError -> Maybe PluginError
findLicenseNeededError =
    List.Extra.find (\{ error } -> error == "license.needed.error")


pluginBadge : PluginInfo -> List (Html msg)
pluginBadge p =
    case ( p.status, findLicenseNeededError p.errors ) of
        ( Enabled, _ ) ->
            [ div [ class "position-absolute top-0 end-0" ] [ span [ class "badge float-end bg-success" ] [ text "Installed" ] ] ]

        ( _, Just _ ) ->
            [ div [ class "position-absolute top-0 end-0" ] [ span [ class "badge float-end text-dark" ] [ i [ class "fa fa-info-circle me-1" ] [], text "Missing license" ] ] ]

        ( Disabled, _ ) ->
            [ div [ class "position-absolute top-0 end-0" ] [ span [ class "badge float-end" ] [ text "Disabled" ] ] ]

        ( Uninstalled, _ ) ->
            []


pluginCardBgClass : PluginInfo -> Maybe String
pluginCardBgClass p =
    case ( p.status, findLicenseNeededError p.errors ) of
        ( Disabled, _ ) ->
            Just "plugin-card-disabled"

        ( _, Just _ ) ->
            Just "plugin-card-missing-license"

        _ ->
            Nothing


pluginErrorCalloutClass : PluginError -> Maybe String
pluginErrorCalloutClass err =
    case err.error of
        "license.near.expiration.error" ->
            Just "warning"

        "abi.version.error" ->
            Just "warning"

        "license.expired.error" ->
            Just "danger"

        _ ->
            Nothing


pluginErrorCallouts : PluginInfo -> Maybe (List (Html msg))
pluginErrorCallouts p =
    p.errors
        |> List.filterMap (\err -> pluginErrorCalloutClass err |> Maybe.map (\cls -> ( err, cls )))
        |> List.map (\( err, cls ) -> div [ class ("callout-fade callout-" ++ cls) ] [ i [ class ("me-1 fa fa-" ++ cls) ] [], text err.message ])
        |> (\e ->
                if List.isEmpty e then
                    Nothing

                else
                    Just e
           )


pluginInputCheck : Model -> PluginInfo -> List (Html Msg)
pluginInputCheck model p =
    let
        -- plugin cannot be selected
        isDisabled =
            Maybe.Extra.isJust <| findLicenseNeededError p.errors
    in
    if isDisabled then
        [ input [ id p.id, type_ "checkbox", class "d-none", disabled True ] [], i [ class "fa fa-info-circle text-muted fs-5 mx-2" ] [] ]

    else
        [ input [ id p.id, type_ "checkbox", class "mx-2", checked (List.member p.id model.ui.selected), onCheck (checkOne p.id) ] [] ]


displayPlugin : Model -> PluginInfo -> Html Msg
displayPlugin model p =
    div [ class <| "plugin-card card " ++ Maybe.withDefault "" (pluginCardBgClass p) ]
        [ div [ class "card-body" ]
            [ div [ class "form-check p-0 d-flex align-items-center" ]
                (pluginInputCheck model p
                    ++ label [ class "d-flex flex-column mx-2", for p.id ]
                        [ div [ class "d-flex align-items-baseline" ]
                            [ h3 [ class "plugin-name card-title" ] [ text p.name ]
                            , span [ class "plugin-version ms-2" ] [ text ("v" ++ p.pluginVersion) ]
                            ]
                        , div [ class "card-text" ]
                            [ div [ class "plugin-description" ] [ text p.description ]
                            , Maybe.withDefault (text "") <|
                                Maybe.map (div [ class "plugin-errors d-flex flex-column" ]) <|
                                    pluginErrorCallouts p
                            ]
                        ]
                    :: pluginBadge p
                )
            ]
        ]


buildModal : String -> Html Msg -> Msg -> Html Msg
buildModal title body saveAction =
    div [ class "modal modal-account fade show", style "display" "block" ]
        [ div [ class "modal-backdrop fade show", onClick (SetModalState NoModal) ] []
        , div [ class "modal-dialog modal-dialog-scrollable" ]
            [ div [ class "modal-content" ]
                [ div [ class "modal-header" ]
                    [ h2 [ class "fs-5 modal-title" ] [ text title ]
                    , button [ type_ "button", class "btn-close", onClick (SetModalState NoModal) ] []
                    ]
                , div [ class "modal-body" ]
                    [ body
                    ]
                , div [ class "modal-footer" ]
                    [ button [ type_ "button", class "btn btn-default", onClick (SetModalState NoModal) ] [ text "Close" ]
                    , button [ type_ "button", class "btn btn-success", onClick saveAction ] [ text "Confirm" ]
                    ]
                ]
            ]
        ]


modalTitle : RequestType -> String
modalTitle requestType =
    String.Extra.toSentenceCase (requestTypeText requestType) ++ " plugins"


modalBody : RequestType -> Model -> Html Msg
modalBody requestType model =
    div [ class "callout-fade callout-warning" ]
        [ p [] [ i [ class "fa fa-warning me-2" ] [], text <| "Rudder may restart to " ++ requestTypeText requestType ++ " " ++ String.Extra.pluralize "plugin" "plugins" (List.length model.ui.selected) ++ " :" ]
        , ul [ class "list-group m-0" ] (List.map (\p -> li [ class "list-group-item" ] [ text p ]) model.ui.selected)
        ]


displayModal : Model -> Html Msg
displayModal model =
    case model.ui.modalState of
        NoModal ->
            text ""

        OpenModal requestType ->
            buildModal (modalTitle requestType) (modalBody requestType model) (CallApi (requestTypeAction requestType))
