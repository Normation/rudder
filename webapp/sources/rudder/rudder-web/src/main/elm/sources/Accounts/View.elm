module Accounts.View exposing (..)

import Accounts.ApiCalls exposing (..)
import Accounts.DataTypes exposing (..)
import Accounts.ViewModals exposing (..)
import Accounts.ViewUtils exposing (..)
import Html exposing (..)
import Html.Attributes exposing (attribute, class, disabled, href, placeholder, selected, type_, value)
import Html.Events exposing (onClick, onInput)
import List
import String


view : Model -> Html Msg
view model =
    let
        hasClearTextTokens =
            List.any (\a -> String.length a.token > 0) model.accounts
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
                                        , text "You have API accounts with tokens generated on a previous Rudder versions, those for which the "
                                        , text "beginning of the token value is displayed in the table. They are now deprecated, you should "
                                        , text "re-generate or replace them for improved security."
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
                                                    , value model.ui.tableFilters.filter
                                                    , placeholder "Filter..."
                                                    , onInput
                                                        (\s ->
                                                            let
                                                                tableFilters =
                                                                    model.ui.tableFilters
                                                            in
                                                            UpdateTableFilters { tableFilters | filter = s }
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
                                                                tableFilters =
                                                                    model.ui.tableFilters
                                                            in
                                                            UpdateTableFilters { tableFilters | authType = authType }
                                                        )
                                                    ]
                                                    [ option [ selected True, value model.ui.tableFilters.authType, disabled True ] [ text "Filter on access level" ]
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
