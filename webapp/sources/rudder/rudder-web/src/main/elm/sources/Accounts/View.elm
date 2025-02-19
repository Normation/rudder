module Accounts.View exposing (..)

import Accounts.ApiCalls exposing (..)
import Accounts.DataTypes exposing (..)
import Accounts.DataTypes as TokenState exposing (..)
import Accounts.ViewModals exposing (..)
import Accounts.ViewUtils exposing (..)
import Html exposing (..)
import Html.Attributes exposing (class, disabled, href, placeholder, selected, type_, value)
import Html.Events exposing (onClick, onInput)
import List


view : Model -> Html Msg
view model =
    let
        hasClearTextTokens =
            List.any (\a -> a.tokenState == TokenState.GeneratedV1) model.accounts
    in
    div [ class "rudder-template" ]
        [ div [ class "one-col" ]
            [ div [ class "main-header" ]
                [ div [ class "header-title" ]
                    [ h1 []
                        [ span [] [ text "API accounts" ]
                        ]
                    ]
                , div [ class "header-description" ]
                    [ p []
                        [ text "Configure accounts allowed to connect to Rudder's REST API. For API usage, read the dedicated "
                        , a [ href "https://docs.rudder.io/api/" ] [ text "documentation" ]
                        , text "."
                        ]
                    ]
                ]
            , div [ class "one-col-main" ]
                [ div [ class "template-main" ]
                    [ div [ class "main-container" ]
                        [ div [ class "main-details" ]
                            [ div [ class "parameters-container" ]
                                [ if hasClearTextTokens then
                                    div [ class "alert alert-warning" ]
                                        [ i [ class "fa fa-exclamation-triangle" ] []
                                        , text "You have API accounts with tokens generated on an pre-8.1 Rudder versions. "
                                        , text "They are now disabled, you should re-generate or replace them."
                                        ]

                                  else
                                    text ""
                                , button [ class "btn btn-success new-icon", onClick (ToggleEditPopup NewAccount) ] [ text "Create an account" ]
                                , div [ class "main-table" ]
                                    [ div [ class "table-container" ]
                                        [ div [ class "dataTables_wrapper_top table-filter" ]
                                            [ div [ class "form-group" ]
                                                [ input
                                                    [ class "form-control"
                                                    , type_ "text"
                                                    , value model.ui.filters.tableFilters.filter
                                                    , placeholder "Filter..."
                                                    , onInput
                                                        (\s ->
                                                            let
                                                                filters =
                                                                    model.ui.filters
                                                                tableFilters =
                                                                    filters.tableFilters
                                                            in
                                                            UpdateFilters { filters | tableFilters = { tableFilters | filter = s }}
                                                        )
                                                    ]
                                                    []
                                                ]
                                            , div [ class "form-group" ]
                                                [ select
                                                    [ class "form-select"
                                                    , onInput
                                                        (\authType ->
                                                            let
                                                                filters =
                                                                    model.ui.filters
                                                            in
                                                            UpdateFilters { filters | authType = authorizationTypeFromText authType }
                                                        )
                                                    ]
                                                    [ option [ selected True, value (model.ui.filters.authType |> Maybe.map authorizationTypeText |> Maybe.withDefault ""), disabled True ] [ text "Filter on access level" ]
                                                    , option [ value "" ] [ text "All accounts" ]
                                                    , option [ value "none" ] [ text "No access" ]
                                                    , option [ value "ro" ] [ text "Read only" ]
                                                    , option [ value "rw" ] [ text "Full access" ]
                                                    , option [ value "acl" ] [ text "Custom ACL" ]
                                                    ]
                                                ]
                                            , div [ class "end" ]
                                                [ button [ class "btn btn-default", onClick (CallApi getAccounts) ] [ i [ class "fa fa-refresh" ] [] ]
                                                ]
                                            ]
                                        , displayAccountsTable model
                                        ]
                                    ]
                                ]
                            ]
                        ]
                    ]
                ]
            ]
        , displayModal model
        ]
