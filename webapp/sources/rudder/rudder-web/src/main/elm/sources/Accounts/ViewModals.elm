module Accounts.ViewModals exposing (..)

import Html exposing (..)
import Html.Attributes exposing (id, class, type_, for, attribute, name, value, checked, style, placeholder, disabled, selected)
import Html.Events exposing (onClick, onInput, custom, onCheck)
import SingleDatePicker exposing (Settings, TimePickerVisibility(..), defaultSettings, defaultTimePickerSettings)
import Task
import Time exposing (Month(..), Posix, Zone)
import Time.Extra as Time exposing (Interval(..), add)

import Accounts.ApiCalls exposing (..)
import Accounts.DataTypes exposing (..)
import Accounts.DatePickerUtils exposing (..)


displayModals : Model -> Html Msg
displayModals model =
  let
    (checkEmptyBtn, checkEmptyWarning, checkAlreadyUsedName) =
      case model.editAccount of
        Nothing -> (False, False, False)
        Just account ->
          case model.ui.modalState of
            NewAccount    -> ( String.isEmpty account.name , False , List.member account.name (List.map .name model.accounts) )
            EditAccount a -> ( String.isEmpty account.name , String.isEmpty account.name , (a.name/=account.name && List.member account.name (List.map .name model.accounts)) )
            _ -> ( False , False , False )

    modalClass = if model.ui.modalState == NoModal then "" else " in"

    (modalTitle, btnTxt, btnClass) = case model.ui.modalState of
       NoModal       -> ( "" , "Save", "default")
       NewAccount    -> ( "Create a new API account"             , "Create" , "success" )
       EditAccount a -> ( "Update account '" ++ a.name ++ "'"    , "Update" , "success" )
       Confirm Delete a call     -> ( "Delete API account '" ++ a ++ "'", "Confirm"  , "danger" )
       Confirm Regenerate a call -> ( "Regenerate token of API account '" ++ a ++ "'", "Confirm", "primary")

    (popupBody, saveAction, displayAcl) = case model.ui.modalState of
      NoModal                  -> ( text "" , Ignore, False)
      Confirm modalType a call ->
        let
          subTitle = case modalType of
            Delete     -> "delete"
            Regenerate -> "regenerate token of"
        in
          ( div[]
            [ h4 [class "text-center"][text ("You are about to " ++ subTitle ++ " an API account.")]
            , div [class "alert alert-warning"]
              [ i [class "fa fa-exclamation-triangle"][]
              , text "If you continue, any scripts using this will no longer be able to connect to Rudder's API."
              ]
            ]
          , call
          , False
          )
      _ ->
        case model.editAccount of
          Nothing -> ( text "" , Ignore, False )
          Just account ->
            let
              datePickerValue = getDateString model.ui.datePickerInfo model.ui.datePickerInfo.pickedTime
              (expirationDate, selectedDate) = case account.expirationDate of
                Just d  -> (( if account.expirationDateDefined then (posixToString model.ui.datePickerInfo d) else "Never" ), d)
                Nothing -> ("Never", (add Month 1 model.ui.datePickerInfo.zone model.ui.datePickerInfo.currentTime))
              aclList = case account.acl of
                Just l  -> l
                Nothing -> []

              displayWarningName =
                if checkEmptyWarning then
                  span[class "warning-info"] [i[class "fa fa-warning"][], text " This field is required"]
                else if checkAlreadyUsedName then
                  span[class "warning-info"] [i[class "fa fa-warning"][], text " This name is already used"]
                else
                  text ""
            in
              ( form [name "newAccount", class ("newAccount" ++ if SingleDatePicker.isOpen model.ui.datePickerInfo.picker then " datepicker-open" else "") ]
                [ div [class ("form-group" ++ if checkEmptyWarning || checkAlreadyUsedName then " has-warning" else "")]
                  [ label [for "newAccount-name"][text "Name", displayWarningName]
                  , input [id "newAccount-name", type_ "text", class "form-control", value account.name, onInput (\s -> UpdateAccountForm {account | name = s} )][]
                  ]
                , div [class "form-group"]
                  [ label [for "newAccount-description"][text "Description"]
                  , textarea [id "newAccount-description", class "form-control vresize float-inherit", value account.description, onInput (\s -> UpdateAccountForm {account | description = s} )][]
                  ]
                , div [class "form-group"]
                  [ label [for "newAccount-expiration"]
                    [ text "Expiration date"
                    , label [for "selectDate", class "custom-toggle toggle-secondary"]
                      [ input [type_ "checkbox", id "selectDate", checked account.expirationDateDefined, onCheck (\c ->  UpdateAccountForm {account | expirationDateDefined = c} )][]
                      , label [for "selectDate", class "custom-toggle-group"]
                        [ label [for "selectDate", class "toggle-enabled" ][text "Defined"]
                        , span  [class "cursor"][]
                        , label [for "selectDate", class "toggle-disabled"][text "Undefined"]
                        ]
                      ]
                    , ( if checkIfExpired model.ui.datePickerInfo account then
                        span[class "warning-info"][ i[class "fa fa-warning"][], text " Expiration date has passed"]
                      else
                        text ""
                      )
                    ]
                  , div [ class "elm-datepicker-container" ]
                    [ button [ type_ "button", class "form-control btn-datepicker", disabled (not account.expirationDateDefined), onClick (OpenPicker selectedDate), placeholder "Select an expiration date"]
                      [ text expirationDate
                      ]
                    , SingleDatePicker.view (userDefinedDatePickerSettings model.ui.datePickerInfo.zone model.ui.datePickerInfo.currentTime selectedDate) model.ui.datePickerInfo.picker
                    ]
                  ]
                , div [class "form-group"]
                  [ label [for "newAccount-access"][text "Access level"]
                  , select [id "newAccount-access", class "form-control", onInput (\s -> UpdateAccountForm {account | authorisationType = s} )]
                    [ option [value "none", selected (account.authorisationType == "none" )] [ text "No access"   ]
                    , option [value "ro"  , selected (account.authorisationType == "ro"   )] [ text "Read only"   ]
                    , option [value "rw"  , selected (account.authorisationType == "rw"   )] [ text "Full access" ]
                    , option [value "acl" , selected (account.authorisationType == "acl"  ), disabled (not model.aclPluginEnabled) ] [ text ("Custom ACL" ++ if model.aclPluginEnabled then "" else " (Plugin needed)") ]
                    ]
                  ]
                ]
              , (CallApi (saveAccount account))
              , account.authorisationType == "acl"
              )
  in
    div [class ("modal fade " ++ modalClass)]
    [ div [class "modal-backdrop fade in", onClick (ToggleEditPopup NoModal)][]
    , div [class "modal-dialog"]
      [ div [class "modal-content"]
        [ div [class "modal-header"]
          [ div [class "close", attribute "data-dismiss" "modal", onClick (ToggleEditPopup NoModal)]
            [ span[][text (String.fromChar (Char.fromCode 215))]
            ]
          , h4 [class "modal-title"] [text modalTitle]
          ]
        , div [class "modal-body"]
          [ popupBody
            -- ACL plugin container
          , div [id "apiauthorization-app", style "display" (if displayAcl then "block" else "none")]
            [ div [id "apiauthorization-content"][]
            ]
          ]
        , div [class "modal-footer"]
          [ button [type_ "button", class "btn btn-default", onClick (ToggleEditPopup NoModal)] [ text "Close" ]
          , button [type_ "button", class ("btn btn-" ++ btnClass), onClick saveAction, disabled (checkEmptyBtn || checkAlreadyUsedName) ][text btnTxt]
          ]
        ]
      ]
    ]