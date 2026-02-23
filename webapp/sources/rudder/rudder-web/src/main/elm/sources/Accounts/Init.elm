port module Accounts.Init exposing (..)

import Accounts.ApiCalls exposing (getAccounts)
import Accounts.DataTypes exposing (..)
import Accounts.JsonDecoder exposing (decodePortAcl)
import Dict
import Json.Decode exposing (..)
import SingleDatePicker exposing (Settings, TimePickerVisibility(..))
import Task
import Time exposing (Month(..), Posix, Zone)
import TimeZone

import Ui.Datatable exposing (defaultTableFilters)
import Utils.DatePickerUtils exposing (userDefinedDatePickerSettings)


-- PORTS / SUBSCRIPTIONS


port successNotification : String -> Cmd msg


port errorNotification : String -> Cmd msg


port initTooltips : String -> Cmd msg



-- for desktop copy to clipboard


port copy : String -> Cmd msg



-- port used to tell the ApiAuthorization plugin extension to init itself if present and get/send ACL for accounts


port initAcl : String -> Cmd msg


port shareAcl : Value -> Cmd msg


port getCheckedAcl : (Json.Decode.Value -> msg) -> Sub msg



-- port used to tell the ApiTenants plugin extension to init itself if present and get/send tenants for accounts


port initTenants : String -> Cmd msg


port focusAccountTenants : Value -> Cmd msg


port getCheckedTenants : (Json.Decode.Value -> msg) -> Sub msg


subscriptions : Model -> Sub Msg
subscriptions model =
    Sub.batch
        [ SingleDatePicker.subscriptions (userDefinedDatePickerSettings { zone = model.ui.datePickerInfo.zone, today = model.ui.datePickerInfo.currentTime, focusedDate = model.ui.datePickerInfo.currentTime }) model.ui.datePickerInfo.picker
        , Time.every 1000 Tick -- Update of the current time every second
        , getCheckedAcl (GetCheckedAcl << decodeValue (Json.Decode.list decodePortAcl)) -- here, we are talking to the plugin, so with ("path", "verb")
        , getCheckedTenants (GetCheckedTenants << decodeValue (Json.Decode.list string))
        ]


init : { contextPath : String, hasWriteRights : Bool, aclPluginEnabled:Bool, timeZone: String, tenantsPluginEnabled: Bool } -> ( Model, Cmd Msg )
init flags =
    let
        initTimeZone =
            Dict.get flags.timeZone TimeZone.zones
                |> Maybe.withDefault (\() -> Time.utc)

        initDatePicker =
            DatePickerInfo (Time.millisToPosix 0) (initTimeZone ()) Nothing (SingleDatePicker.init UpdatePicker)

        initFilters = Filters (defaultTableFilters Name) Nothing

        initUi =
            UI initFilters NoModal False True initDatePicker False False

        initModel =
            Model flags.contextPath initUi [] flags.aclPluginEnabled flags.tenantsPluginEnabled Nothing

        initAclPlugin =
            if flags.aclPluginEnabled && not initUi.pluginAclInit then
                initAcl ""
            else
                Cmd.none

        initTenantsPlugin =
            if flags.tenantsPluginEnabled && not initUi.pluginTenantsInit then
                initTenants ""
            else
                Cmd.none

        initActions =
            [ Task.perform Tick Time.now
            , initAclPlugin
            , initTenantsPlugin
            , getAccounts initModel
            ]
    in
    ( initModel, Cmd.batch initActions )
