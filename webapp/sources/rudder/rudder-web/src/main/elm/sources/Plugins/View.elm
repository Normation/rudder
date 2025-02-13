module Plugins.View exposing (..)

import Html exposing (Html, a, button, div, h1, h2, h3, i, input, label, li, p, pre, span, strong, table, tbody, td, text, tr, ul)
import Html.Attributes exposing (attribute, checked, class, disabled, for, href, id, style, target, type_)
import Html.Attributes.Extra exposing (role)
import Html.Events exposing (onCheck, onClick)
import List.Extra
import Maybe.Extra
import Plugins.ApiCalls exposing (..)
import Plugins.DataTypes exposing (..)
import Set exposing (Set)
import String.Extra
import Time.DateTime
import Time.Iso8601
import Time.ZonedDateTime


view : Model -> Html Msg
view model =
    div [ class "rudder-template" ]
        [ div [ class "one-col w-100" ]
            [ div [ class "main-header" ]
                [ div [ class "header-title" ]
                    [ h1 []
                        [ span [] [ text "Plugins management" ]
                        ]
                    ]
                ]
            , div [ class "one-col-main" ]
                [ div [ class "template-main" ]
                    [ div [ class "main-container" ]
                        [ div [ class "main-details" ]
                            (loadWithSpinner "spinner-border" model.ui.loading (displayPluginView model))
                        ]
                    ]
                ]
            , displayModal model
            ]
        ]


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


actionButtons : PluginsViewModel -> List (Html Msg)
actionButtons model =
    [ button [ class "btn btn-primary me-1", onClick (CallApi updateIndex) ] [ i [ class "fa fa-refresh me-1" ] [], text "Refresh plugins" ]
    , button [ class "btn btn-default mx-1", disabled <| Set.isEmpty model.selected, onClick (SetModalState (OpenModal Install)) ] [ text "Uninstall", i [ class "fa fa-minus-circle ms-1" ] [] ]
    , button [ class "btn btn-default mx-1", disabled <| Set.isEmpty model.selected, onClick (SetModalState (OpenModal Uninstall)) ] [ text "Uninstall", i [ class "fa fa-minus-circle ms-1" ] [] ]
    , button [ class "btn btn-default mx-1", disabled <| Set.isEmpty model.selected, onClick (SetModalState (OpenModal Enable)) ] [ text "Enable", i [ class "fa fa-check-circle ms-1" ] [] ]
    , button [ class "btn btn-default ms-1", disabled <| Set.isEmpty model.selected, onClick (SetModalState (OpenModal Disable)) ] [ text "Disable", i [ class "fa fa-ban ms-1" ] [] ]
    ]


displayPluginsList : PluginsViewModel -> Html Msg
displayPluginsList pluginsModel =
    if List.isEmpty pluginsModel.plugins then
        i [ class "text-secondary" ] [ text "There are no plugins available." ]

    else
        pluginsSection pluginsModel


pluginsSection : PluginsViewModel -> Html Msg
pluginsSection pluginsModel =
    let
        plugins =
            List.sortWith pluginDefaultOrdering pluginsModel.plugins

        isSelectAll =
            not (List.length plugins == Set.size pluginsModel.selected)

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
                , div [ class "end" ] (actionButtons pluginsModel)
                ]
            , div [ class "plugins-list" ] (List.map (displayPlugin pluginsModel) plugins)
            ]
        ]


displayGlobalLicense : LicenseGlobalInfo -> Html Msg
displayGlobalLicense license =
    let
        licensees =
            license.licensees |> Maybe.map (String.join ", ") |> Maybe.withDefault "-"

        dateOf =
            Time.ZonedDateTime.toDateTime >> Time.DateTime.date >> Time.Iso8601.fromDate

        -- dates needs to be aggregated by date part of datetime
        aggregateDates : List DateCount -> List ( String, Int )
        aggregateDates dates =
            dates
                |> List.sortBy (.date >> dateOf)
                |> List.Extra.groupWhile (\x y -> dateOf (.date x) == dateOf (.date y))
                |> List.map (\( d, ns ) -> ( dateOf d.date, d.count + (List.map .count >> List.sum) ns ))

        displayPluginDates ends =
            String.join ", " (List.map (\( d, n ) -> d ++ " (" ++ String.Extra.pluralize " plugin)" " plugins)" n) ends)

        validityPeriod =
            case ( license.startDate, license.endDates ) of
                ( Just start, Just ends ) ->
                    "from " ++ dateOf start ++ " to " ++ displayPluginDates (aggregateDates ends)

                _ ->
                    "-"

        nbNodes =
            license.maxNodes |> Maybe.map String.fromInt |> Maybe.withDefault "Unlimited"
    in
    div [ class "main-license" ]
        [ div [ attribute "data-plugin" "statusInformation", class "license-card" ]
            [ div [ id "license-information", class "license-info" ]
                [ div [ class "license-information-details" ]
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


displaySettingError : Model -> String -> Maybe String -> Html Msg
displaySettingError model message details =
    let
        seeDetailsBtn =
            a
                [ class "btn btn-default", attribute "data-bs-toggle" "collapse", href "#collapseSettingsError", role "button", attribute "aria-expanded" "false", attribute "aria-controls" "collapseSettingsError" ]
                [ text "See details" ]

        detailsCollapse txt =
            div [ class "collapse", id "collapseSettingsError" ] [ div [ class "card card-body" ] [ pre [ class "command-output" ] [ text txt ] ] ]

        ( seeDetailsBtnHtml, detailsCollapseHtml ) =
            details
                |> Maybe.Extra.unpack (\_ -> ( [], [] )) (\d -> ( [ seeDetailsBtn ], [ detailsCollapse d ] ))
    in
    div [ class "callout-fade callout-warning overflow-scroll" ]
        ([ p [] [ i [ class "fa fa-warning" ] [], text message ]
         , p [] [ a [ target "_blank", href (model.contextPath ++ "/secure/administration/settings") ] [ text "Open Rudder account settings", i [ class "fa fa-external-link ms-1" ] [] ] ]
         , p []
            (button [ class "btn btn-primary me-1", onClick (CallApi updateIndex) ] [ i [ class "fa fa-refresh me-1" ] [], text "Refresh plugins" ]
                :: seeDetailsBtnHtml
            )
         ]
            ++ detailsCollapseHtml
        )


displayMainLicense : Model -> Html Msg
displayMainLicense model =
    case model.license of
        Nothing ->
            displaySettingError model "No license found. Please contact Rudder to get license or configure your access" Nothing

        Just license ->
            if license == noGlobalLicense then
                displaySettingError model "Empty license found. Please contact Rudder to get license or configure your access" Nothing

            else
                displayGlobalLicense license


displayPluginView : Model -> List (Html Msg)
displayPluginView model =
    case model.ui.view of
        ViewSettingsError ( message, details ) ->
            [ displaySettingError model message (Just details) ]

        ViewPluginsList pluginsModel ->
            [ displayMainLicense model
            , displayPluginsList pluginsModel
            ]


findLicenseNeededError : List PluginError -> Maybe PluginError
findLicenseNeededError =
    List.Extra.find (\{ error } -> error == "license.needed.error")


pluginBadge : PluginInfo -> List (Html msg)
pluginBadge p =
    case ( p.status, findLicenseNeededError p.errors ) of
        ( Enabled, _ ) ->
            [ div [ class "position-absolute top-0 end-0" ] [ span [ class "badge float-end bg-success" ] [ text "Installed" ] ] ]

        ( _, Just _ ) ->
            [ div [ class "position-absolute top-0 end-0" ] [ span [ class "badge float-end text-dark" ] [ i [ class "fa fa-info-circle me-1" ] [], text "No license" ] ] ]

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
        |> List.map (\( err, cls ) -> div [ class ("callout-fade callout-" ++ cls) ] [ i [ class "me-1 fa fa-warning" ] [], text err.message ])
        |> (\e ->
                if List.isEmpty e then
                    Nothing

                else
                    Just e
           )


pluginInputCheck : PluginInfo -> Set PluginId -> List (Html Msg)
pluginInputCheck p selected =
    let
        -- plugin cannot be selected
        isDisabled =
            Maybe.Extra.isJust <| findLicenseNeededError p.errors
    in
    if isDisabled then
        [ input [ id p.id, type_ "checkbox", class "d-none", disabled True ] [], i [ class "fa fa-info-circle text-muted fs-5 mx-2" ] [] ]

    else
        [ input [ id p.id, type_ "checkbox", class "mx-2", checked (Set.member p.id selected), onCheck (checkOne p.id) ] [] ]


displayPlugin : PluginsViewModel -> PluginInfo -> Html Msg
displayPlugin pluginsModel p =
    div [ class <| "plugin-card card " ++ Maybe.withDefault "" (pluginCardBgClass p) ]
        [ div [ class "card-body" ]
            [ div [ class "form-check p-0 d-flex align-items-center" ]
                (pluginInputCheck p pluginsModel.selected
                    ++ label [ class "d-flex flex-column mx-2", for p.id ]
                        [ div [ class "d-flex align-items-baseline" ]
                            [ h3 [ class "plugin-title card-title" ] [ text p.description ]
                            , span [ class "plugin-version ms-2" ] [ text ("v" ++ p.pluginVersion) ]
                            ]
                        , div [ class "card-text" ]
                            [ div [ class "plugin-name" ] [ a [ class "link-secondary text-decoration-underline link-underline link-offset-2 link-underline-opacity-25 link-underline-opacity-100-hover", target "_blank", href (pluginDocumentationLink p) ] [ i [ class "fa fa-book me-2" ] [], text p.name ] ]
                            , Maybe.withDefault (text "") <|
                                Maybe.map (div [ class "plugin-errors d-flex flex-column" ]) <|
                                    pluginErrorCallouts p
                            ]
                        ]
                    :: pluginBadge p
                )
            ]
        ]


buildModal : Bool -> String -> Html Msg -> Msg -> Html Msg
buildModal loading title body saveAction =
    div [ class "modal modal-plugins fade show", style "display" "block" ]
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
                    [ button [ type_ "button", class "btn btn-default", onClick (SetModalState NoModal) ] [ text "Cancel" ]
                    , button [ type_ "button", class "btn btn-success", onClick saveAction ]
                        (loadWithSpinner "spinner-grow spinner-grow-sm"
                            loading
                            (if loading then
                                []

                             else
                                [ text "Confirm" ]
                            )
                        )
                    ]
                ]
            ]
        ]


modalTitle : RequestType -> String
modalTitle requestType =
    String.Extra.toSentenceCase (requestTypeText requestType) ++ " plugins"


modalBody : RequestType -> PluginsViewModel -> Html Msg
modalBody requestType pluginsModel =
    div [ class "callout-fade callout-warning" ]
        [ p [] [ i [ class "fa fa-warning me-2" ] [], strong [] [ text "Rudder will restart" ], text <| " to " ++ requestTypeText requestType ++ " " ++ String.Extra.pluralize "plugin" "plugins" (Set.size pluginsModel.selected) ++ " :" ]
        , ul [ class "list-group m-0" ] (pluginsModel.selected |> Set.toList |> List.map (\p -> li [ class "list-group-item" ] [ text p ]))
        ]


displayModal : Model -> Html Msg
displayModal model =
    case model.ui.view of
        ViewSettingsError _ ->
            text ""

        ViewPluginsList pluginsModel ->
            case pluginsModel.modalState of
                NoModal ->
                    text ""

                OpenModal requestType ->
                    buildModal model.ui.loading (modalTitle requestType) (modalBody requestType pluginsModel) (RequestApi requestType pluginsModel.selected)


loadWithSpinner : String -> Bool -> List (Html Msg) -> List (Html Msg)
loadWithSpinner spinnerClass loading html =
    [ if loading then
        div [ class <| "d-flex justify-content-center fade show" ]
            [ div [ class spinnerClass, role "status" ]
                [ span [ class "visually-hidden" ] [ text "Loading..." ] ]
            ]

      else
        text ""
    , div
        [ class <|
            "fade "
                ++ (if loading then
                        ""

                    else
                        "show"
                   )
        ]
        html
    ]


pluginDocumentationLink : PluginInfo -> String
pluginDocumentationLink p =
    String.join "/" <|
        "https://docs.rudder.io/reference"
            :: pluginMinorAbiVersion p
            :: "plugins"
            :: (p.id ++ ".html")
            :: []
