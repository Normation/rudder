module Accounts exposing (..)

import Browser
import Dict
import Dict.Extra
import Http exposing (..)
import Http.Detailed as Detailed
import Result
import List.Extra
import Random
import UUID
import Json.Encode exposing (..)
import SingleDatePicker exposing (Settings, TimePickerVisibility(..), defaultSettings, defaultTimePickerSettings)
import Task
import Time exposing (Month(..), Posix, Zone)
import Time.Extra as Time exposing (Interval(..), add)

import Accounts.ApiCalls exposing (..)
import Accounts.DataTypes exposing (..)
import Accounts.DatePickerUtils exposing (..)
import Accounts.Init exposing (..)
import Accounts.JsonEncoder exposing (encodeTokenAcl)
import Accounts.JsonDecoder exposing (decodeErrorDetails)
import Accounts.View exposing (view)
import Accounts.ViewUtils exposing (..)

main = Browser.element
  { init          = init
  , view          = view
  , update        = update
  , subscriptions = subscriptions
  }

generator : Random.Generator String
generator = Random.map (UUID.toString) UUID.generator

--
-- update loop --
--
update : Msg -> Model -> ( Model, Cmd Msg )
update msg model =
  case msg of
    Copy s -> (model, copy s)

    -- Generate random id
    GenerateId nextMsg ->
      (model, Random.generate nextMsg generator)

    -- Do an API call
    CallApi call ->
      (model, call model)

    -- neutral element
    Ignore ->
      (model , successNotification "")

    ToggleEditPopup modalState ->
      let
        ui = model.ui
        currentTime = model.ui.datePickerInfo.currentTime
        expDate     = add Month 1 model.ui.datePickerInfo.zone currentTime
        (editAccount, tokenId, acl) = case modalState of
          NewAccount    -> (Just (Account "" "" "" "rw" "" True "" "" "" True (Just expDate) Nothing), "", [])
          EditAccount a -> (Just a, a.id, case a.acl of
            Just ac -> ac
            Nothing -> []
            )
          _ -> (Nothing, "", [])
      in
        ( { model | ui = {ui | modalState = modalState}, editAccount = editAccount }, shareAcl (encodeTokenAcl tokenId acl) )

    CloseCopyPopup ->
      let
        ui = model.ui
      in
        ({ model | ui = {ui | copyState = NoCopy}}, Cmd.none)

    UpdateTableFilters tableFilters ->
      let
        ui = model.ui
      in
        ({model | ui = { ui | tableFilters = tableFilters}}, Cmd.none)

    GetAccountsResult res ->
      case  res of
        Ok (metadata, apiResult) ->
          let
            modelUi  = model.ui
            accounts = apiResult.accounts
            aclPluginEnabled = apiResult.aclPluginEnabled
            initAclPlugin = if aclPluginEnabled && not modelUi.pluginAclInit then initAcl "" else Cmd.none
          in
            ( { model | accounts = accounts, aclPluginEnabled = aclPluginEnabled, ui = { modelUi | loadingAccounts = False, pluginAclInit = True } }
              , Cmd.batch [initTooltips "", initAclPlugin]
            )
        Err err ->
          processApiError "Getting API accounts list" err model

    UpdateAccountForm account ->
      ({model | editAccount = Just account}, Cmd.none)

    SaveAccount (Ok (metadata, account)) ->
      let
        ui = model.ui
        copyState = case ui.modalState of
          NewAccount -> Token account.token
          _ -> NoCopy
        action = case ui.modalState of
          NewAccount -> "created"
          _ -> "updated"

        newModel = {model | ui = {ui | modalState = NoModal, copyState = copyState }, editAccount = Nothing}
      in
        (newModel, Cmd.batch [(successNotification ("Account '"++ account.name ++"' successfully " ++ action)) , (getAccounts newModel)])

    SaveAccount (Err err) ->
      processApiError "Saving account" err model

    ConfirmActionAccount actionType (Ok (metadata, account)) ->
      let
        ui = model.ui
        copyState = case actionType of
          Delete     -> NoCopy
          Regenerate -> Token account.token
        newModel = {model | ui = {ui | modalState = NoModal, copyState = copyState }}
        message  = case actionType of
          Delete     -> "deleted"
          Regenerate -> "regenerated token of"
      in
        (newModel, Cmd.batch [successNotification ("Successfully " ++ message ++ " API account '" ++ account.name ++  "'"), (getAccounts model)])

    ConfirmActionAccount actionType (Err err) ->
      let
        action = case actionType of
          Delete     -> "Deleting"
          Regenerate -> "regenerating token of"
      in
        processApiError (action ++ " API account") err model

    GetCheckedAcl (Ok acl) ->
      let
        newAccount = case model.editAccount of
          Just ac -> Just { ac | acl = Just acl }
          Nothing -> Nothing
        newModel = { model | editAccount = newAccount }
      in
        (newModel, Cmd.none)

    GetCheckedAcl (Err err) ->
        (model, errorNotification ("Error when selecting custom ACL" ))

    -- DATEPICKER
    OpenPicker posix->
      let
        ui = model.ui
        datePicker = ui.datePickerInfo
      in
      ( { model | ui = { ui | datePickerInfo = { datePicker | picker = SingleDatePicker.openPicker (userDefinedDatePickerSettings datePicker.zone datePicker.currentTime posix) posix (Just posix) datePicker.picker }}}, Cmd.none )

    UpdatePicker subMsg ->
      let
        
        
        ui = model.ui
        datePicker = ui.datePickerInfo
        selectedDate = case datePicker.pickedTime of
          Nothing -> datePicker.currentTime
          Just d -> d
        ( newPicker, maybeNewTime ) =  SingleDatePicker.update (userDefinedDatePickerSettings datePicker.zone datePicker.currentTime selectedDate) subMsg datePicker.picker
        newModel = case model.editAccount of
          Nothing -> model
          Just a  ->
            let
              newTime    = case maybeNewTime of
                Just t  -> Just t
                Nothing -> a.expirationDate
              newAccount = Just {a | expirationDate = newTime}
            in
              { model | ui = { ui | datePickerInfo = { datePicker | picker = newPicker, pickedTime = newTime }}, editAccount = newAccount}
      in
        ( newModel , Cmd.none )

    AdjustTimeZone newZone ->
      let
        ui = model.ui
        datePicker = ui.datePickerInfo
        newModel = { model | ui = { ui | datePickerInfo = { datePicker | zone = newZone }}}
      in
        ( newModel, getAccounts newModel )

    Tick newTime ->
      let
        ui = model.ui
        datePicker = ui.datePickerInfo
      in
        ( { model | ui = { ui | datePickerInfo = { datePicker | currentTime = newTime }}}, Cmd.none )

processApiError : String -> Detailed.Error String -> Model -> ( Model, Cmd Msg )
processApiError apiName err model =
  let
    modelUi = model.ui
    message =
      case err of
        Detailed.BadUrl url ->
          "The URL " ++ url ++ " was invalid"
        Detailed.Timeout ->
          "Unable to reach the server, try again"
        Detailed.NetworkError ->
          "Unable to reach the server, check your network connection"
        Detailed.BadStatus metadata body ->
          let
            (title, errors) = decodeErrorDetails body
          in
            title ++ "\n" ++ errors
        Detailed.BadBody metadata body msg ->
          msg
  in
    ({ model | ui = { modelUi | loadingAccounts = False}}, errorNotification ("Error when "++apiName ++", details: \n" ++ message ) )
