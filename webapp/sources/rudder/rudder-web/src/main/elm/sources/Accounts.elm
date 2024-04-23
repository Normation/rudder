module Accounts exposing (..)

import Accounts.ApiCalls exposing (..)
import Accounts.DataTypes as TenantMode exposing (..)
import Accounts.DatePickerUtils exposing (..)
import Accounts.Init exposing (..)
import Accounts.JsonDecoder exposing (decodeErrorDetails)
import Accounts.JsonEncoder exposing (encodeAccountTenants, encodeTokenAcl)
import Accounts.View exposing (view)
import Accounts.ViewUtils exposing (..)
import Browser
import Dict
import Dict.Extra
import Http exposing (..)
import Http.Detailed as Detailed
import Json.Encode exposing (..)
import List.Extra
import Random
import Result
import SingleDatePicker exposing (Settings, TimePickerVisibility(..), defaultSettings, defaultTimePickerSettings)
import Task
import Time exposing (Month(..), Posix, Zone)
import Time.Extra as Time exposing (Interval(..), add)
import UUID



{--
This application manage the list of API Accounts and their token properties.
The general behavior is:
- there is a list of API Accounts with action buttons for editing, deleting, token generation, etc
- new one can be created
- action button create a modal window
- there is a main data type about current state of modal (none, new account, editing, etc)
--}


main =
    Browser.element
        { init = init
        , view = view
        , update = update
        , subscriptions = subscriptions
        }


generator : Random.Generator String
generator =
    Random.map UUID.toString UUID.generator



--
-- update loop --
--


update : Msg -> Model -> ( Model, Cmd Msg )
update msg model =
    case msg of
        Copy s ->
            ( model, copy s )

        -- Generate random id
        GenerateId nextMsg ->
            ( model, Random.generate nextMsg generator )

        -- Do an API call
        CallApi call ->
            ( model, call model )

        -- neutral element
        Ignore ->
            ( model, successNotification "" )

        ToggleEditPopup modalState ->
            let
                ui =
                    model.ui

                currentTime =
                    model.ui.datePickerInfo.currentTime

                expDate =
                    add Month 1 model.ui.datePickerInfo.zone currentTime

                editAccount =
                    case modalState of
                        NewAccount ->
                            Just (Account "" "" "" "rw" "" True "" "" "" True (Just expDate) Nothing TenantMode.AllAccess Nothing)

                        EditAccount a ->
                            Just a

                        _ ->
                            Nothing

                tokenId =
                    editAccount |> Maybe.map .id |> Maybe.withDefault ""

                acl =
                    editAccount |> Maybe.andThen .acl |> Maybe.withDefault []

                tenants =
                    editAccount |> Maybe.andThen .selectedTenants |> Maybe.withDefault []
            in
            ( { model | ui = { ui | modalState = modalState }, editAccount = editAccount }, Cmd.batch [ shareAcl (encodeTokenAcl tokenId acl), focusAccountTenants (encodeAccountTenants tokenId tenants) ] )

        CloseCopyPopup ->
            let
                ui =
                    model.ui
            in
            ( { model | ui = { ui | copyState = NoCopy } }, Cmd.none )

        UpdateFilters filters ->
            let
                ui =
                    model.ui
            in
            ( { model | ui = { ui | filters = filters } }, Cmd.none )

        GetAccountsResult res ->
            case res of
                Ok ( metadata, apiResult ) ->
                    let
                        modelUi =
                            model.ui

                        accounts =
                            apiResult.accounts

                        aclPluginEnabled =
                            apiResult.aclPluginEnabled

                        tenantsPluginEnabled =
                            apiResult.tenantsPluginEnabled

                        initAclPlugin =
                            if aclPluginEnabled && not modelUi.pluginAclInit then
                                initAcl ""

                            else
                                Cmd.none

                        initTenantsPlugin =
                            if apiResult.tenantsPluginEnabled && not modelUi.pluginTenantsInit then
                                initTenants ""

                            else
                                Cmd.none
                    in
                    ( { model | accounts = accounts, aclPluginEnabled = aclPluginEnabled, tenantsPluginEnabled = tenantsPluginEnabled, ui = { modelUi | loadingAccounts = False, pluginAclInit = True, pluginTenantsInit = True } }
                    , Cmd.batch [ initTooltips "", initAclPlugin, initTenantsPlugin ]
                    )

                Err err ->
                    processApiError "Getting API accounts list" err model

        UpdateAccountForm account ->
            ( { model | editAccount = Just account }, Cmd.none )

        SaveAccount (Ok ( metadata, account )) ->
            let
                ui =
                    model.ui

                copyState =
                    case ui.modalState of
                        NewAccount ->
                            Token account.token

                        _ ->
                            NoCopy

                action =
                    case ui.modalState of
                        NewAccount ->
                            "created"

                        _ ->
                            "updated"

                newModel =
                    { model | ui = { ui | modalState = NoModal, copyState = copyState }, editAccount = Nothing }
            in
            ( newModel, Cmd.batch [ successNotification ("Account '" ++ account.name ++ "' successfully " ++ action), getAccounts newModel ] )

        SaveAccount (Err err) ->
            processApiError "Saving account" err model

        ConfirmActionAccount actionType (Ok ( metadata, account )) ->
            let
                ui =
                    model.ui

                copyState =
                    case actionType of
                        Delete ->
                            NoCopy

                        Regenerate ->
                            Token account.token

                newModel =
                    { model | ui = { ui | modalState = NoModal, copyState = copyState } }

                message =
                    case actionType of
                        Delete ->
                            "deleted"

                        Regenerate ->
                            "regenerated token of"
            in
            ( newModel, Cmd.batch [ successNotification ("Successfully " ++ message ++ " API account '" ++ account.name ++ "'"), getAccounts model ] )

        ConfirmActionAccount actionType (Err err) ->
            let
                action =
                    case actionType of
                        Delete ->
                            "Deleting"

                        Regenerate ->
                            "regenerating token of"
            in
            processApiError (action ++ " API account") err model

        GetCheckedAcl (Ok acl) ->
            let
                newAccount =
                    case model.editAccount of
                        Just ac ->
                            Just { ac | acl = Just acl }

                        Nothing ->
                            Nothing

                newModel =
                    { model | editAccount = newAccount }
            in
            ( newModel, Cmd.none )

        GetCheckedAcl (Err err) ->
            ( model, errorNotification "Error when selecting custom ACL" )

        GetCheckedTenants (Ok selectedTenants) ->
            let
                newAccount =
                    case model.editAccount of
                        Just ac ->
                            case selectedTenants of
                                [] ->
                                    Just { ac | selectedTenants = Nothing }

                                l ->
                                    Just { ac | selectedTenants = Just (List.sort l) }

                        Nothing ->
                            Nothing

                newModel =
                    { model | editAccount = newAccount }
            in
            ( newModel, Cmd.none )

        GetCheckedTenants (Err err) ->
            ( model, errorNotification "Error when selecting tenants from list" )

        -- DATEPICKER
        OpenPicker posix ->
            let
                ui =
                    model.ui

                datePicker =
                    ui.datePickerInfo
            in
            ( { model | ui = { ui | datePickerInfo = { datePicker | picker = SingleDatePicker.openPicker (userDefinedDatePickerSettings datePicker.zone datePicker.currentTime posix) posix (Just posix) datePicker.picker } } }, Cmd.none )

        UpdatePicker subMsg ->
            let
                ui =
                    model.ui

                datePicker =
                    ui.datePickerInfo

                selectedDate =
                    case datePicker.pickedTime of
                        Nothing ->
                            datePicker.currentTime

                        Just d ->
                            d

                ( newPicker, maybeNewTime ) =
                    SingleDatePicker.update (userDefinedDatePickerSettings datePicker.zone datePicker.currentTime selectedDate) subMsg datePicker.picker

                newModel =
                    case model.editAccount of
                        Nothing ->
                            model

                        Just a ->
                            let
                                newTime =
                                    case maybeNewTime of
                                        Just t ->
                                            Just t

                                        Nothing ->
                                            a.expirationDate

                                newAccount =
                                    Just { a | expirationDate = newTime }
                            in
                            { model | ui = { ui | datePickerInfo = { datePicker | picker = newPicker, pickedTime = newTime } }, editAccount = newAccount }
            in
            ( newModel, Cmd.none )

        AdjustTimeZone newZone ->
            let
                ui =
                    model.ui

                datePicker =
                    ui.datePickerInfo

                newModel =
                    { model | ui = { ui | datePickerInfo = { datePicker | zone = newZone } } }
            in
            ( newModel, getAccounts newModel )

        Tick newTime ->
            let
                ui =
                    model.ui

                datePicker =
                    ui.datePickerInfo
            in
            ( { model | ui = { ui | datePickerInfo = { datePicker | currentTime = newTime } } }, Cmd.none )


processApiError : String -> Detailed.Error String -> Model -> ( Model, Cmd Msg )
processApiError apiName err model =
    let
        modelUi =
            model.ui

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
                        ( title, errors ) =
                            decodeErrorDetails body
                    in
                    title ++ "\n" ++ errors

                Detailed.BadBody metadata body msg ->
                    msg
    in
    ( { model | ui = { modelUi | loadingAccounts = False } }, errorNotification ("Error when " ++ apiName ++ ", details: \n" ++ message) )
