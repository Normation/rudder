module Accounts.ViewModals exposing (..)

import Accounts.ApiCalls exposing (..)
import Accounts.DataTypes exposing (..)
import Accounts.DatePickerUtils exposing (..)
import Accounts.JsonDecoder exposing (parseTenants)
import Accounts.JsonEncoder exposing (encodeTenants)
import Html exposing (..)
import Html.Attributes exposing (checked, class, disabled, for, id, name, placeholder, selected, style, title, type_, value)
import Html.Events exposing (onCheck, onClick, onInput)
import SingleDatePicker exposing (Settings, TimePickerVisibility(..))
import Time.Extra exposing (Interval(..), add)

buildModal : String -> Html Msg -> Html Msg -> Html Msg
buildModal modalTitle modalBody modalBtn =
    div [ class "modal modal-account fade show", style "display" "block" ]
    [ div [class "modal-backdrop fade show", onClick (ToggleEditPopup NoModal)][]
    , div [ class "modal-dialog modal-dialog-scrollable" ]
        [ div [ class "modal-content" ]
            [ div [ class "modal-header" ]
                [ h5 [ class "modal-title" ] [ text modalTitle ]
                , button [ type_ "button", class "btn-close", onClick (ToggleEditPopup NoModal) ] []
                ]
            , div [ class "modal-body" ]
                [ modalBody
                ]
            , div [ class "modal-footer" ]
                [ button [ type_ "button", class "btn btn-default", onClick (ToggleEditPopup NoModal) ] [ text "Close" ]
                , modalBtn
                ]
            ]
        ]
    ]
tenantsContainer : Bool -> Html Msg
tenantsContainer displayTenants =
    div
    [ id "tenantapiaccounts-app"
    , style "display"
        (if displayTenants then
            "block"

         else
            "none"
        )
    ]
    [ if displayTenants then
        h5 [] [ text "Select tenants for account:" ]

      else
        text ""
    , div [ id "tenantapiaccounts-content" ] []
    ]

aclPluginContainer : Bool -> Html Msg
aclPluginContainer displayAcl =
    div
    [ id "apiauthorization-app"
    , style "display"
        (if displayAcl then
            "block"

         else
            "none"
        )
    ]
    [ if displayAcl then
        h5 [] [ text "Select ACL for account:" ]

      else
        text ""
    , div [ id "apiauthorization-content" ] []
    ]

displayModal : Model -> Html Msg
displayModal model =
    let
        ( checkEmptyBtn, checkEmptyWarning, checkAlreadyUsedName ) =
            case model.editAccount of
                Nothing ->
                    ( False, False, False )

                Just account ->
                    case model.ui.modalState of
                        NewAccount ->
                            ( String.isEmpty account.name, False, List.member account.name (List.map .name model.accounts) )

                        EditAccount a ->
                            ( String.isEmpty account.name, String.isEmpty account.name, a.name /= account.name && List.member account.name (List.map .name model.accounts) )

                        _ ->
                            ( False, False, False )

        saveAction = case model.editAccount of
            Nothing -> Ignore
            Just ac -> (CallApi (saveAccount ac))

        editForm = case model.editAccount of
              Nothing ->
                  text ""

              Just account ->
                  let
                      displayAclPlugin = account.authorizationType == ACL && model.aclPluginEnabled
                      displayTenants   = account.tenantMode == ByTenants && model.tenantsPluginEnabled

                      ( expirationText, selectedDate ) =
                          case account.expirationPolicy of
                              ExpireAtDate d ->
                                  ( posixToString model.ui.datePickerInfo.zone d, d )

                              NeverExpire ->
                                  ( "Never", add Month 1 model.ui.datePickerInfo.zone model.ui.datePickerInfo.currentTime )

                      displayWarningName =
                          if checkEmptyWarning then
                              span [ class "warning-info" ] [ i [ class "fa fa-warning" ] [], text " This field is required" ]

                          else if checkAlreadyUsedName then
                              span [ class "warning-info" ] [ i [ class "fa fa-warning" ] [], text " This name is already used" ]

                          else
                              text ""

                      -- if the plugin is disabled, only show a read-only view of tenants. Else, it's an option among all, none, a list. Tenants should not be set for full RW access, so we disable it in that case
                      displayTenantAccess =
                          if model.tenantsPluginEnabled then
                              select [ id "newAccount-tenants", class "form-select", onInput (\s -> UpdateAccountForm { account | tenantMode = Tuple.first (parseTenants s) }), disabled (account.authorizationType == RW) ]
                                  [ option [ value "*", selected (account.tenantMode == AllAccess) ] [ text "Access to all tenants" ]
                                  , option [ value "-", selected (account.tenantMode == NoAccess) ] [ text "Access to no tenant" ]
                                  , option [ value "list", selected (account.tenantMode == ByTenants) ]
                                      [ text
                                          ("Access to restricted list of tenants: "
                                              ++ (case account.selectedTenants of
                                                      Just tenants ->
                                                          String.join ", " tenants

                                                      Nothing ->
                                                          "-"
                                                 )
                                          )
                                      ]
                                  ]

                          else
                              span [] [ text (": " ++ encodeTenants account.tenantMode account.selectedTenants) ]

                      chooseId =
                          case model.ui.modalState of
                              NewAccount ->
                                  div [ class "form-group" ]
                                      [ label [ for "newAccount-id" ] [ text "Account ID" ]
                                      , div [] [ span [ class "small fw-light"] [ text "By default, Rudder uses a generated UUID as account ID, but you can specify your own ID if needed. In that case, no token secret will be generated automatically."] ]
                                      , input [ id "newAccount-id", placeholder "generated", class "form-control vresize float-none", value account.id, onInput (\s -> UpdateAccountForm { account | id = s }) ] []
                                      ]
                              _ -> text ""
                  in
                      div[]
                      [ form
                          [ name "newAccount"
                          , class
                              ("newAccount"
                                  ++ (if SingleDatePicker.isOpen model.ui.datePickerInfo.picker then
                                          " datepicker-open"

                                      else
                                          ""
                                     )
                              )
                          ]
                          [ div
                              [ class
                                  ("form-group"
                                      ++ (if checkEmptyWarning || checkAlreadyUsedName then
                                              " has-warning"

                                          else
                                              ""
                                         )
                                  )
                              ]
                              [ label [ for "newAccount-name" ] [ text "Name", displayWarningName ]
                              , input [ id "newAccount-name", type_ "text", class "form-control", value account.name, onInput (\s -> UpdateAccountForm { account | name = s }) ] []
                              ]
                          , div [ class "form-group" ]
                              [ label [ for "newAccount-description" ] [ text "Description" ]
                              , textarea [ id "newAccount-description", class "form-control vresize float-none", value account.description, onInput (\s -> UpdateAccountForm { account | description = s }) ] []
                              ]
                          , chooseId
                          , div [ class "form-group" ]
                              [ label [ for "newAccount-expiration", class "mb-1" ]
                                  [ text "Expiration date"
                                  , label [ for "selectDate", class "custom-toggle toggle-secondary" ]
                                      [ input [ type_ "checkbox", id "selectDate", checked <| account.expirationPolicy /= NeverExpire, onCheck (\c -> UpdateAccountForm ( account |> if c then setExpirationPolicy (ExpireAtDate selectedDate) else setExpirationPolicy NeverExpire ) ) ] []
                                      , label [ for "selectDate", class "custom-toggle-group" ]
                                          [ label [ for "selectDate", class "toggle-enabled" ] [ text "Defined" ]
                                          , span [ class "cursor" ] []
                                          , label [ for "selectDate", class "toggle-disabled" ] [ text "Undefined" ]
                                          ]
                                      ]
                                  , if checkIfExpired model.ui.datePickerInfo account then
                                      span [ class "warning-info" ] [ i [ class "fa fa-warning" ] [], text " Expiration date has passed" ]
                                    else
                                      text ""
                                  ]
                              , div [ class "elm-datepicker-container" ]
                                  [ button [ type_ "button", class "form-control btn-datepicker", disabled (account.expirationPolicy == NeverExpire), onClick (OpenPicker selectedDate), placeholder "Select an expiration date" ]
                                      [ text expirationText
                                      ]
                                  , SingleDatePicker.view (userDefinedDatePickerSettings { zone = model.ui.datePickerInfo.zone, today = model.ui.datePickerInfo.currentTime, focusedDate = selectedDate }) model.ui.datePickerInfo.picker
                                  ]
                              ]
                          , div [ class "form-group" ]
                              [ label [ for "newAccount-tenants" ] [ text "Access to tenants" ]
                              , displayTenantAccess
                              ]
                          , div [ class "form-group" ]
                              [ label [ for "newAccount-access" ] [ text "Access level" ]
                              , select [ id "newAccount-access", class "form-select", onInput (authorizationTypeFromText >> Maybe.map (\s -> UpdateAccountForm { account | authorizationType = s }) >> Maybe.withDefault Ignore) ]
                                  [ option [ value "ro", selected (account.authorizationType == RO) ] [ text "Read only" ]
                                  , option [ value "rw", selected (account.authorizationType == RW) ] [ text "Full access" ]
                                  , option [ value "acl", selected (account.authorizationType == ACL), disabled (not model.aclPluginEnabled) ]
                                      [ text
                                          ("Custom ACL"
                                              ++ (if model.aclPluginEnabled then
                                                      ""

                                                  else
                                                      " (Plugin needed)"
                                                 )
                                          )
                                      ]
                                  ]
                              ]
                          ]
                      , aclPluginContainer displayAclPlugin
                      , tenantsContainer displayTenants
                      ]
        ( modalTitle, modalBody, modalBtn ) = case model.ui.modalState of
            NewAccount ->
                ( "Create a new API account"
                , editForm
                , button [ type_ "button", class "btn btn-success", onClick saveAction, disabled (checkEmptyBtn || checkAlreadyUsedName) ] [ text "Create" ]
                )
            EditAccount account ->
                ( "Update account '" ++ account.name ++ "'"
                , editForm
                , button [ type_ "button", class "btn btn-success", onClick saveAction, disabled (checkEmptyBtn || checkAlreadyUsedName) ] [ text "Update" ]
                )
            Confirm modalType name action ->
                let
                    (title, subTitle, btnClass) = case modalType of
                        Delete ->
                            ( "Delete API account '" ++ name ++ "'"
                            , "delete"
                            , "danger"
                            )
                        Regenerate ->
                            ( "Regenerate token of API account '" ++ name ++ "'"
                            , "regenerate token of"
                            , "primary"
                            )
                in
                    ( title
                    , div []
                        [ h5 [ class "text-center" ] [ text ("You are about to " ++ subTitle ++ " an API account.") ]
                        , div [ class "alert alert-warning" ]
                            [ i [ class "fa fa-exclamation-triangle" ] []
                            , text "If you continue, any scripts using this will no longer be able to connect to Rudder's API."
                            ]
                        ]
                    , button [ type_ "button", class ("btn btn-" ++ btnClass), onClick action ] [ text "Confirm" ]
                    )
            CopyToken "" ->
                ( "Account created without secret token"
                , div []
                    [ div [ class "alert alert-info" ]
                        [ i [ class "fa fa-exclamation-triangle" ] []
                        , text "This account doesn't have a token. You can create one with the refresh action"
                        ]
                    ]
                , text ""
                )
            CopyToken token ->
                ( "Copy the token"
                , div []
                    [ div [ class "alert alert-info" ]
                        [ i [ class "fa fa-exclamation-triangle" ] []
                        , text "This is the only time the token value will be available."
                        ]
                    , div []
                        [ span [ class "token-txt" ] [ text token ]
                        , a [ class "btn-goto always clipboard", title "Copy to clipboard", onClick (Copy token) ] [ i [ class "ion ion-clipboard" ] [] ]
                        ]
                    ]
                , text ""
                )
            NoModal ->
                ("", text "", text "")

    in
        case model.ui.modalState of
            NoModal -> text ""
            _ -> buildModal modalTitle modalBody modalBtn
