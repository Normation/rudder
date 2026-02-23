module Accounts exposing (..)

import Accounts.ApiCalls exposing (..)
import Accounts.DataTypes as TenantMode exposing (..)
import Accounts.DataTypes as Token exposing (..)
import Accounts.DataTypes as TokenState exposing (..)
import Accounts.Init exposing (..)
import Accounts.JsonDecoder exposing (decodeErrorDetails)
import Accounts.JsonEncoder exposing (encodeAccountTenants, encodeTokenAcl)
import Accounts.View exposing (view)
import Accounts.ViewUtils exposing (..)
import Browser
import Http.Detailed as Detailed
import Random
import Result
import SingleDatePicker exposing (Settings, TimePickerVisibility(..))
import Time.Extra exposing (Interval(..), add)
import UUID
import Maybe.Extra
import Utils.DatePickerUtils exposing (userDefinedDatePickerSettings)



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
                            Just (Account "" "" "" RW "" Enabled currentTime TokenState.GeneratedV2 (Just Token.Hashed) Nothing (ExpireAtDate expDate) Nothing Nothing TenantMode.AllAccess Nothing)

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


                    in
                    ( { model | accounts = accounts, ui = { modelUi | loadingAccounts = False, pluginAclInit = True, pluginTenantsInit = True } }
                    , Cmd.batch [ initTooltips "" ]
                    )

                Err err ->
                    processApiError "Getting API accounts list" err model

        UpdateAccountForm account ->
            ( { model | editAccount = Just account }, Cmd.none )

        SaveAccount (Ok ( metadata, account )) ->
            let
                ui =
                    model.ui

                (modalState, action) =
                    case ui.modalState of
                        NewAccount ->
                            ( CopyToken (exposeToken account.token)
                            , "created"
                            )

                        _ ->
                            ( NoModal
                            , "updated"
                            )

                newModel =
                    { model | ui = { ui | modalState = modalState }, editAccount = Nothing }
            in
            ( newModel, Cmd.batch [ successNotification ("Account '" ++ account.name ++ "' successfully " ++ action), getAccounts newModel ] )

        SaveAccount (Err err) ->
            processApiError "Saving account" err model

        ConfirmActionAccount actionType (Ok ( metadata, account )) ->
            let
                ui =
                    model.ui

                (modalState, message) =
                    case actionType of
                        Delete ->
                            ( NoModal
                            , "deleted"
                            )

                        Regenerate ->
                            ( CopyToken (exposeToken account.token)
                            , "regenerated token of"
                            )
                        Create ->
                            ( CopyToken (exposeToken account.token)
                            , "created token for"
                            )

                newModel =
                    { model | ui = { ui | modalState = modalState } }

            in
            ( newModel, Cmd.batch [ successNotification ("Successfully " ++ message ++ " API account '" ++ account.name ++ "'"), getAccounts model ] )

        ConfirmActionAccount actionType (Err err) ->
            let
                action =
                    case actionType of
                        Delete ->
                            "Deleting"

                        Regenerate ->
                            "Regenerating token of"

                        Create ->
                            "Creating token for"
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
            ( { model | ui = { ui | datePickerInfo = { datePicker | picker = SingleDatePicker.openPicker (userDefinedDatePickerSettings { zone = datePicker.zone, today = datePicker.currentTime, focusedDate = posix }) posix (Just posix) datePicker.picker } } }, Cmd.none )

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
                    SingleDatePicker.update (userDefinedDatePickerSettings { zone = datePicker.zone, today = datePicker.currentTime, focusedDate = selectedDate }) subMsg datePicker.picker

                newModel =
                    case model.editAccount of
                        Nothing ->
                            model

                        Just a ->
                            let
                                newAccount =
                                    maybeNewTime
                                        |> Maybe.map (setIfExpireAtDate >> updateExpirationPolicy)
                                        |> Maybe.Extra.unpack (\_ -> a) (\f -> f a)

                                newTime =
                                        expirationDate newAccount.expirationPolicy

                            in
                            { model | ui = { ui | datePickerInfo = { datePicker | picker = newPicker, pickedTime = newTime } }, editAccount = Just newAccount }
            in
            ( newModel, Cmd.none )

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
