module Plugins.View exposing (..)

import Dict exposing (Dict)
import Html exposing (Html, a, button, div, em, h1, h2, h3, i, input, label, li, p, pre, span, strong, text, ul)
import Html.Attributes exposing (attribute, autocomplete, checked, class, disabled, for, href, id, name, placeholder, style, target, title, type_, value)
import Html.Attributes.Extra exposing (role)
import Html.Events exposing (onCheck, onClick, onInput)
import List.Extra
import Maybe.Extra
import NaturalOrdering exposing (compareOn)
import Ordering
import Plugins.Action exposing (Action(..), PluginsAction(..), PluginsActionExplanation(..), actionText, explainDisallowedResult, installUpgradeCount, successPluginsFromExplanation)
import Plugins.ApiCalls exposing (..)
import Plugins.DataTypes exposing (..)
import Plugins.PluginData exposing (..)
import Plugins.Select exposing (Select(..), Selected, Selection(..), getSelection, isAllSelected, noSelected, selectedCount)
import Set exposing (Set)
import String.Extra
import Time.DateTime
import Time.Iso8601
import Time.TimeZones exposing (utc)
import Time.ZonedDateTime as ZonedDateTime exposing (ZonedDateTime)


view : Model -> Html Msg
view model =
    div [ class "rudder-template" ]
        [ div [ class "one-col w-100" ]
            [ div [ class "main-header" ]
                [ div [ class "header-title d-flex justify-content-baseline" ]
                    [ h1 []
                        [ span [] [ text "Plugins management" ]
                        ]
                    , if model.ui.loading then
                        text ""

                      else
                        displayMainLicense model
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


checkAll : Set PluginId -> Bool -> Msg
checkAll plugins =
    CheckSelection
        << (\check ->
                if check then
                    SelectAll plugins

                else
                    UnselectAll
           )


actionInstallUpgradeButton : PluginsActionExplanation -> Html Msg
actionInstallUpgradeButton x =
    let
        { install, upgrade } =
            installUpgradeCount x

        installText =
            text (actionText ActionInstall)
                :: (install
                        |> Maybe.map
                            (\count ->
                                [ em []
                                    [ text <| "(" ++ String.fromInt count ++ ")" ]
                                ]
                            )
                        |> Maybe.withDefault []
                   )

        upgradeText =
            text (actionText ActionUpgrade)
                :: (upgrade
                        |> Maybe.map
                            (\count ->
                                [ em []
                                    [ text <| "(" ++ String.fromInt count ++ ")" ]
                                ]
                            )
                        |> Maybe.withDefault []
                   )

        disabledAttrs =
            [ disabled (Maybe.Extra.isNothing install && Maybe.Extra.isNothing upgrade) ]
    in
    button
        ([ class "btn btn-default mx-1"

         -- we need action to be Install, since the Upgrade is just a user point-of-view
         , onClick (SetModalState (OpenModal ModalInstallUpgrade x))
         ]
            ++ disabledAttrs
        )
        (installText ++ text " / " :: upgradeText ++ [ i [ class "fa fa-plus-circle ms-1" ] [] ])


displaySelectAll : Int -> PluginsViewModel -> List (Html Msg)
displaySelectAll totalCount { selected, plugins } =
    let
        pluginIds =
            plugins |> Dict.keys |> Set.fromList

        isSelectAll =
            not (isAllSelected selected pluginIds)

        selectText =
            [ text
                (if isSelectAll then
                    "Select all  "

                 else
                    "Unselect all "
                )
            , em []
                [ text <| "(" ++ String.fromInt (selectedCount selected) ++ "/" ++ String.fromInt totalCount ++ ")" ]
            ]

        disableButton =
            Set.isEmpty pluginIds && selected == noSelected
    in
    if disableButton then
        [ button [ class "btn btn-default", disabled True ]
            (input [ id "select-plugins", type_ "checkbox", class "me-2", checked False ] []
                :: selectText
            )
        ]

    else
        [ label [ class "btn btn-default", for "select-plugins" ]
            -- We need a clone checkbox because bootstrap checkbox button hides it
            (input [ id "clone-select-plugins", type_ "checkbox", class "me-2", checked (not isSelectAll), onCheck (checkAll pluginIds) ] []
                :: selectText
            )
        , input [ id "select-plugins", type_ "checkbox", class "btn-check", checked (not isSelectAll), onCheck (checkAll pluginIds) ] []
        ]


displayFilters : Filters -> List (Html Msg)
displayFilters filters =
    [ div [ class "form-group" ]
        [ input
            [ class "form-control"
            , type_ "text"
            , value filters.search
            , placeholder "Filter..."
            , onInput
                (\s ->
                    UpdateFilters { filters | search = s }
                )
            ]
            []
        ]
    , div [ class "form-group" ]
        [ div [ class "btn-group", attribute "role" "group", attribute "aria-label" "Plugin Type Filter" ]
            (pluginTypeRadioButtons filters)
        ]
    , div [ class "form-group" ]
        [ div [ class "btn-group", attribute "role" "group", attribute "aria-label" "Install Status Filter" ]
            (installStatusRadioButtons filters)
        ]
    ]


pluginTypeRadioButtons : Filters -> List (Html Msg)
pluginTypeRadioButtons filters =
    [ ( "All types", FilterByAllPluginType )
    , ( "Webapp", FilterByPluginType Webapp )
    , ( "Integration", FilterByPluginType Integration )
    ]
        |> List.indexedMap (radioButton "pluginType" filters.pluginType (\s -> UpdateFilters { filters | pluginType = s }))
        |> List.concat


installStatusRadioButtons : Filters -> List (Html Msg)
installStatusRadioButtons filters =
    [ ( "All status", FilterByAllInstallStatus )
    , ( "Enabled", FilterByInstallStatus (Installed Enabled) )
    , ( "Disabled", FilterByInstallStatus (Installed Disabled) )
    , ( "Uninstalled", FilterByInstallStatus Uninstalled )
    ]
        |> List.indexedMap
            (radioButton "installStatus" filters.installStatus (\s -> UpdateFilters { filters | installStatus = s }))
        |> List.concat


radioButton : String -> a -> (a -> Msg) -> Int -> ( String, a ) -> List (Html Msg)
radioButton groupName selected toMsg index ( labelText, value ) =
    let
        isChecked =
            selected == value

        inputId =
            groupName ++ String.fromInt index
    in
    [ input
        [ type_ "radio"
        , class "btn-check"
        , name groupName
        , id inputId
        , autocomplete False
        , checked isChecked
        , onClick (toMsg value)
        ]
        []
    , label [ class "btn btn-outline-primary", Html.Attributes.for inputId ] [ text labelText ]
    ]


displayActionButtons : PluginsViewModel -> List (Html Msg)
displayActionButtons { selected, installAction, uninstallAction, enableAction, disableAction } =
    [ button [ class "btn btn-primary me-1", onClick (CallApi updateIndex) ] [ i [ class "fa fa-refresh me-1" ] [], text "Refresh plugins" ]
    , actionInstallUpgradeButton ((\(PluginsAction { explanation }) -> explanation) installAction)
    , actionButton ActionUninstall selected uninstallAction
    , actionButton ActionEnable selected enableAction
    , actionButton ActionDisable selected disableAction
    ]


actionButton : Action -> Selected -> PluginsAction -> Html Msg
actionButton action selected (PluginsAction { successCount, isActionDisabled, explanation }) =
    let
        totalSelected =
            String.fromInt <| selectedCount selected

        onlyAllowedSelected =
            String.fromInt successCount

        byTotal =
            if onlyAllowedSelected == totalSelected then
                ""

            else
                "/" ++ totalSelected

        count =
            text (actionText action ++ " ")
                :: (if isActionDisabled then
                        []

                    else
                        [ em []
                            [ text <|
                                "("
                                    ++ onlyAllowedSelected
                                    ++ byTotal
                                    ++ ")"
                            ]
                        ]
                   )

        disabledAttrs =
            [ disabled isActionDisabled ]
    in
    button
        ([ class "btn btn-default mx-1"
         , onClick (SetModalState (OpenModal (actionToModal action) explanation))
         ]
            ++ disabledAttrs
        )
        (count ++ [ actionIcon action ])


displayPluginsList : Dict PluginId Plugin -> PluginsViewModel -> Html Msg
displayPluginsList plugins pluginsModel =
    if Dict.isEmpty plugins then
        i [ class "text-secondary" ] [ text "There are no plugins available." ]

    else
        displayPluginsSection (Dict.size plugins) pluginsModel


displayPluginsSection : Int -> PluginsViewModel -> Html Msg
displayPluginsSection totalCount pluginsModel =
    let
        plugins =
            pluginsModel.plugins |> Dict.toList |> List.map Tuple.second |> List.sortWith pluginDefaultOrdering

        listContent =
            div [ class "plugins-list" ]
                (if List.isEmpty plugins then
                    [ div [ class "plugins-list callout-fade callout-warning overflow-scroll" ]
                        [ i [ class "fa fa-exclamation-triangle me-2" ] []
                        , em [] [ text "No plugins match your filters" ]
                        ]
                    ]

                 else
                    List.map (displayPlugin pluginsModel) plugins
                )
    in
    div [ class "main-table" ]
        [ div [ class "table-container plugins-container" ]
            [ div [ class "dataTables_wrapper_top table-filter plugins-actions" ]
                [ div [ class "plugins-actions-buttons" ]
                    [ div [] (displaySelectAll totalCount pluginsModel)
                    , div [] (displayActionButtons pluginsModel)
                    ]
                , div [ class "plugins-actions-filters" ]
                    (displayFilters pluginsModel.filters)
                ]
            , listContent
            ]
        ]


displayGlobalLicense : ZonedDateTime -> LicenseGlobal -> Html Msg
displayGlobalLicense now license =
    let
        licensees =
            license.licensees |> Maybe.map (String.join ", ") |> Maybe.withDefault "-"

        dateOf =
            ZonedDateTime.toDateTime >> Time.DateTime.date >> Time.Iso8601.fromDate

        -- dates needs to be aggregated by date part of datetime
        aggregateDates : List DateCount -> List ( ZonedDateTime, Int )
        aggregateDates dates =
            dates
                |> List.sortBy (.date >> dateOf)
                |> List.Extra.groupWhile (\x y -> dateOf (.date x) == dateOf (.date y))
                |> List.map (\( d, ns ) -> ( d.date, d.count + (List.map .count >> List.sum) ns ))

        isBeforeNow =
            Ordering.greaterThanBy (compareOn dateOf)

        isBeforeNextMonth zdt n =
            isBeforeNow zdt (ZonedDateTime.addMonths 1 n)

        cls d =
            if isBeforeNow d now then
                ""

            else if isBeforeNextMonth d now then
                "text-warning"

            else
                "text-danger"

        displayPluginDates ends =
            List.intersperse (text ", ")
                (List.map (\( d, n ) -> span [ class <| cls d ++ " d-inline-block" ] [ text <| dateOf d ++ " (" ++ String.Extra.pluralize " plugin)" " plugins)" n ]) ends)

        validityPeriod =
            case ( license.startDate, license.endDates ) of
                ( Just start, Just ends ) ->
                    (text <| "from " ++ dateOf start ++ " to ") :: displayPluginDates (aggregateDates ends)

                _ ->
                    [ text "-" ]

        nbNodes =
            license.maxNodes |> Maybe.map String.fromInt |> Maybe.withDefault "Unlimited"
    in
    ul []
        [ li [ class "d-inline-block" ] [ span [ class "fw-normal" ] [ text "Licensee: " ], text licensees, text ", ", span [ class "fw-normal" ] [ text "Allowed number of nodes: " ], text nbNodes ]
        , li [ class "d-inline-block" ] (span [ class "fw-normal" ] [ text "Validity period: " ] :: validityPeriod)
        ]


displaySettingError : String -> String -> Maybe String -> Html Msg
displaySettingError contextPath message details =
    let
        seeDetailsBtnHtml =
            if Maybe.Extra.isJust details then
                a [ class "btn btn-default", attribute "data-bs-toggle" "collapse", href "#collapseSettingsError", role "button", attribute "aria-expanded" "false", attribute "aria-controls" "collapseSettingsError" ]
                    [ text "See details" ]

            else
                text ""

        detailsCollapseHtml =
            details
                |> Maybe.map
                    (\d ->
                        div [ class "collapse", id "collapseSettingsError" ] [ div [ class "card card-body" ] [ pre [ class "command-output" ] [ text d ] ] ]
                    )
                |> Maybe.withDefault (text "")
    in
    div [ class "callout-fade callout-warning overflow-scroll" ]
        [ p [] [ i [ class "fa fa-warning" ] [], text message ]
        , p [] [ a [ target "_blank", href (contextPath ++ "/secure/administration/settings") ] [ text "Open Rudder account settings", i [ class "fa fa-external-link ms-1" ] [] ] ]
        , p []
            [ button [ class "btn btn-primary me-1", onClick (CallApi updateIndex) ] [ i [ class "fa fa-refresh me-1" ] [], text "Refresh plugins" ]
            , seeDetailsBtnHtml
            ]
        , detailsCollapseHtml
        ]


displayMainLicense : Model -> Html Msg
displayMainLicense model =
    case model.license of
        Nothing ->
            displaySettingError model.contextPath "No license found. Please contact Rudder to get license or configure your access" Nothing

        Just license ->
            if license == noGlobalLicense then
                displaySettingError model.contextPath "Empty license found. Please contact Rudder to get license or configure your access" Nothing

            else
                displayGlobalLicense (ZonedDateTime.fromPosix utc model.now) license


displayPluginView : Model -> List (Html Msg)
displayPluginView model =
    case model.ui.view of
        ViewSettingError ( message, details ) ->
            [ displaySettingError model.contextPath message (Just details) ]

        ViewActionError _ pluginsModel ->
            -- error is already dislayed in modal
            [ displayPluginsList model.plugins pluginsModel
            ]

        ViewPluginsList pluginsModel ->
            [ displayPluginsList model.plugins pluginsModel
            ]


pluginBadge : Plugin -> List (Html msg)
pluginBadge p =
    case ( p.installStatus, p.licenseStatus ) of
        ( Installed Disabled, _ ) ->
            [ div [ class "position-absolute top-0 start-0" ] [ span [ class "badge float-start" ] [ text "Disabled" ] ] ]

        ( _, MissingLicense _ ) ->
            [ div [ class "position-absolute top-0 start-0" ] [ span [ class "badge float-start text-dark" ] [ i [ class "fa fa-info-circle me-1" ] [], text "No license" ] ] ]

        ( Installed Enabled, _ ) ->
            [ div [ class "position-absolute top-0 start-0" ] [ span [ class "badge float-start bg-success" ] [ text "Installed" ] ] ]

        ( Uninstalled, _ ) ->
            []


pluginCardBgClass : Plugin -> Maybe String
pluginCardBgClass p =
    case ( p.installStatus, p.licenseStatus ) of
        ( _, MissingLicense _ ) ->
            Just "plugin-card-missing-license"

        ( Installed Disabled, _ ) ->
            Just "plugin-card-disabled"

        _ ->
            Nothing


pluginErrorCallouts : Plugin -> List (Html Msg)
pluginErrorCallouts { licenseStatus, abiVersionError } =
    case ( licenseStatus, abiVersionError ) of
        ( ExpiredLicense message, _ ) ->
            [ div [ class "callout-fade callout-danger" ] [ i [ class "me-1 fa fa-warning" ] [], text message ] ]

        ( MissingLicense message, _ ) ->
            [ div [ class "callout-fade callout-danger" ] [ i [ class "me-1 fa fa-warning" ] [], text message ] ]

        ( NearExpirationLicense message, _ ) ->
            [ div [ class "callout-fade callout-warning" ] [ i [ class "me-1 fa fa-warning" ] [], text message ] ]

        ( _, Just message ) ->
            [ div [ class "callout-fade callout-warning" ] [ i [ class "me-1 fa fa-warning" ] [], text message ] ]

        _ ->
            []


pluginInputCheck : { a | id : PluginId } -> Selected -> List (Html Msg)
pluginInputCheck p selected =
    case getSelection p.id selected of
        NotSelectable ->
            [ input [ id p.id, type_ "checkbox", class "d-none", disabled True ] [], i [ class "fa fa-info-circle text-muted fs-5 mx-3" ] [] ]

        Selection b ->
            [ input [ id p.id, type_ "checkbox", class "mx-3", checked b, onCheck (checkOne p.id) ] [] ]


displayPlugin : PluginsViewModel -> Plugin -> Html Msg
displayPlugin pluginsModel p =
    div [ class <| "plugin-card card " ++ Maybe.withDefault "" (pluginCardBgClass p) ]
        [ div [ class "card-body" ]
            [ div [ class "form-check p-0 d-flex align-items-center" ]
                (pluginBadge p
                    ++ pluginInputCheck p pluginsModel.selected
                    ++ [ label [ class "d-flex flex-column mx-4", for p.id ]
                            [ div [ class "d-flex align-items-baseline" ]
                                [ h3 [ class "plugin-title card-title" ]
                                    [ text p.description
                                    , a [ class "link-dark text-decoration-underline link-underline link-offset-2 link-underline-opacity-0 link-underline-opacity-100-hover", target "_blank", href p.docLink ] [ i [ class "plugin-doc-icon fa fa-book me-2" ] [] ]
                                    ]
                                ]
                            , div [ class "card-text" ]
                                -- [badge](Webapp) - ID: branding - v2.1.0
                                [ div [ class "plugin-technical-info" ]
                                    [ span [ class <| "badge badge-type plugin-" ++ (String.Extra.decapitalize <| pluginTypeText p.pluginType) ] [ text <| pluginTypeText p.pluginType ]
                                    , span [] [ text <| "ID: " ++ p.id ]
                                    , span [ class "plugin-version" ] [ text <| "v" ++ p.version ]
                                    ]
                                , Maybe.withDefault (text "") <|
                                    Maybe.map (div [ class "plugin-errors d-flex flex-column" ]) <|
                                        Maybe.Extra.filter (\l -> List.length l > 0) <|
                                            Just (pluginErrorCallouts p)
                                ]
                            ]
                       ]
                )
            ]
        ]


buildErrorModal : PluginsViewModel -> String -> ( String, String ) -> Html Msg
buildErrorModal pluginsViewModel title ( message, details ) =
    let
        seeDetailsBtnHtml =
            a
                [ class "btn btn-default", attribute "data-bs-toggle" "collapse", href "#collapseSettingsError", role "button", attribute "aria-expanded" "false", attribute "aria-controls" "collapseSettingsError" ]
                [ text "See details" ]

        detailsCollapseHtml =
            div [ class "collapse", id "collapseSettingsError" ] [ div [ class "card card-body" ] [ pre [ class "command-output" ] [ text details ] ] ]
    in
    div [ class "modal modal-plugins fade show", style "display" "block" ]
        [ div [ class "modal-backdrop fade show", onClick (ResetPluginListFromModal pluginsViewModel) ] []
        , div [ class "modal-dialog modal-dialog-scrollable" ]
            [ div [ class "modal-content" ]
                [ div [ class "modal-header" ]
                    [ h2 [ class "fs-5 modal-title" ] [ text title ]
                    , button [ type_ "button", class "btn-close", onClick (ResetPluginListFromModal pluginsViewModel) ] []
                    ]
                , div [ class "modal-body" ]
                    [ div [ class "callout-fade callout-danger overflow-scroll" ]
                        [ p [] [ i [ class "fa fa-warning" ] [], text message ]
                        , seeDetailsBtnHtml
                        , detailsCollapseHtml
                        ]
                    ]
                , div [ class "modal-footer" ]
                    [ button [ type_ "button", class "btn btn-default", onClick (ResetPluginListFromModal pluginsViewModel) ] [ text "Close" ]
                    ]
                ]
            ]
        ]


buildModal : Bool -> String -> Html Msg -> Msg -> Html Msg
buildModal loading title body saveAction =
    let
        submitButton =
            if loading then
                button [ type_ "button", class "btn btn-success", disabled True ]
                    [ text "Restarting..." ]

            else
                button [ type_ "button", class "btn btn-success", onClick saveAction ]
                    [ text "Confirm & Restart" ]
    in
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
                    , submitButton
                    ]
                ]
            ]
        ]


modalTitle : ModalAction -> String
modalTitle action =
    String.Extra.toSentenceCase (modalActionText action) ++ " plugins"


modalBody : ModalAction -> PluginsActionExplanation -> Dict PluginId Plugin -> Html Msg
modalBody action explanation plugins =
    let
        doAction act =
            String.Extra.decapitalize (modalActionText act)

        pastAction act =
            act
                ++ (if String.endsWith "e" act then
                        "d"

                    else
                        "ed"
                   )

        pluginListGroupItem p =
            li [ class "list-group-item" ]
                (case Dict.get p plugins of
                    Just { id, description } ->
                        [ text description
                        , text " "
                        , em [] [ text <| "(" ++ id ++ ")" ]
                        ]

                    Nothing ->
                        [ text p
                        ]
                )

        successHtml success =
            div [ class "callout-fade callout-success" ]
                [ ul [ class "list-group m-0" ] (success |> Set.toList |> List.map pluginListGroupItem)
                ]

        displaySuccess success actionText =
            [ p [] [ strong [] [ text "Rudder will restart" ], text <| " to " ++ actionText ++ " " ++ String.Extra.pluralize "plugin" "plugins" (Set.size success) ++ " :" ]
            , successHtml success
            ]

        displayError errors actionText =
            [ p [] [ strong [] [ text <| "Following plugins cannot be " ++ pastAction actionText ++ " : " ] ]
            , div [ class "callout-fade callout-warning plugin-action" ]
                (errors
                    |> List.map
                        (\( error, ps ) ->
                            div [ class "plugin-action-error" ]
                                [ div [ class "px-1 mb-1 fw-bolder" ] [ text <| explainDisallowedResult error ]
                                , ul [ class "list-group m-0" ] (ps |> Set.toList |> List.map pluginListGroupItem)
                                ]
                        )
                )
            ]

        actionInstallText =
            String.Extra.decapitalize (actionText ActionInstall)

        actionUpgradeText =
            String.Extra.decapitalize (actionText ActionUpgrade)

        errorInstallUpgradeText =
            "installed/upgrade"
    in
    case explanation of
        PluginsActionNoExplanation ->
            text ""

        PluginsActionExplainSuccess success ->
            div [] (displaySuccess success (doAction action))

        PluginsActionExplainErrors errors ->
            div [] (displayError errors (doAction action))

        PluginsActionExplainSuccessWarning { success, warning } ->
            div [] (displaySuccess success (doAction action) ++ displayError warning (doAction action))

        PluginsActionExplainInstall success ->
            div [] (displaySuccess success actionInstallText)

        PluginsActionExplainInstallWarning { install, warning } ->
            div [] (displaySuccess install actionInstallText ++ displayError warning actionInstallText)

        PluginsActionExplainUpgrade success ->
            div [] (displaySuccess success actionUpgradeText)

        PluginsActionExplainUpgradeWarning { upgrade, warning } ->
            div [] (displaySuccess upgrade actionUpgradeText ++ displayError warning errorInstallUpgradeText)

        PluginsActionExplainInstallUpgrade { install, upgrade } ->
            div [] (displaySuccess install actionInstallText ++ [ p [] [ text <| "and upgrade " ++ String.fromInt (Set.size upgrade) ++ " plugins :" ], successHtml upgrade ])

        PluginsActionExplainInstallUpgradeWarning { install, upgrade, warning } ->
            div [] (displaySuccess install actionInstallText ++ [ p [] [ text <| "and upgrade " ++ String.fromInt (Set.size upgrade) ++ " plugins :" ], successHtml upgrade ] ++ displayError warning errorInstallUpgradeText)


displayModal : Model -> Html Msg
displayModal model =
    case model.ui.view of
        ViewSettingError _ ->
            text ""

        ViewActionError errDetails pluginsModel ->
            case pluginsModel.modalState of
                ErrorModal action ->
                    buildErrorModal pluginsModel (modalTitle action) errDetails

                _ ->
                    text ""

        ViewPluginsList pluginsModel ->
            case pluginsModel.modalState of
                OpenModal action explanation ->
                    buildModal model.ui.loading (modalTitle action) (modalBody action explanation pluginsModel.plugins) (RequestApi (actionRequestType action) (successPluginsFromExplanation explanation))

                _ ->
                    text ""


actionIcon : Action -> Html Msg
actionIcon action =
    case action of
        ActionInstall ->
            i [ class "fa fa-plus-circle ms-1" ] [] 

        ActionUpgrade ->
            i [ class "fa fa-plus-circle ms-1" ] [] 

        ActionEnable ->
            i [ class "fa fa-check-circle ms-1" ] [] 

        ActionDisable ->
            i [ class "fa fa-ban ms-1" ] [] 

        ActionUninstall ->
            i [ class "fa fa-minus-circle ms-1" ] [] 


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
