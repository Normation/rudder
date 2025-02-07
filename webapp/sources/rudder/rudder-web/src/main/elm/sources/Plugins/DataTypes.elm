module Plugins.DataTypes exposing (..)

import Bytes exposing (Bytes)
import Http
import Http.Detailed
import Json.Encode exposing (Value)
import List.Extra
import Ordering exposing (Ordering)
import Time.ZonedDateTime exposing (ZonedDateTime)


type alias PluginsInfo =
    { license : Maybe LicenseGlobalInfo
    , plugins : List PluginInfo
    }


type alias LicenseGlobalInfo =
    { licensees : Maybe (List String)
    , startDate : Maybe ZonedDateTime
    , endDates : Maybe (List DateCount)
    , maxNodes : Maybe Int
    }


type alias DateCount =
    { date : ZonedDateTime
    , count : Int
    }


type alias PluginInfo =
    { id : PluginId
    , name : String
    , description : String
    , abiVersion : String
    , pluginVersion : String
    , version : String
    , pluginType : PluginType
    , errors : List PluginError
    , status : PluginStatus
    , license : Maybe LicenseInfo
    }


type alias PluginId =
    String


type PluginType
    = Webapp
    | Integration


type PluginStatus
    = Enabled
    | Disabled
    | Uninstalled


type alias LicenseInfo =
    { licensee : String
    , allowedNodesNumber : Int
    , supportedVersions : String
    , startDate : ZonedDateTime
    , endDate : ZonedDateTime
    }


type alias PluginError =
    { error : String
    , message : String
    }


type alias UI =
    { selected : List PluginId
    , modalState : ModalState
    , settingsError : Maybe ( String, String ) -- message, details
    }


type ModalState
    = OpenModal RequestType
    | NoModal


type alias Model =
    { contextPath : String
    , license : Maybe LicenseGlobalInfo
    , plugins : List PluginInfo
    , ui : UI
    }


type Select
    = SelectOne PluginId
    | UnselectOne PluginId
    | SelectAll
    | UnselectAll


type RequestType
    = Install
    | Uninstall
    | Enable
    | Disable
    | UpdateIndex


type Msg
    = CallApi (Model -> Cmd Msg)
    | ApiGetPlugins (Result (Http.Detailed.Error String) ( Http.Metadata, PluginsInfo ))
    | ApiPostPlugins (Result (Http.Detailed.Error Bytes) RequestType)
    | SetModalState ModalState
    | Copy String
    | CopyJson Value
    | CheckSelection Select



-- | UpdateUI UI


requestTypeText : RequestType -> String
requestTypeText t =
    case t of
        Install ->
            "install"

        Uninstall ->
            "uninstall"

        Enable ->
            "enable"

        Disable ->
            "disable"

        UpdateIndex ->
            "index update"


processSelect : Select -> Model -> Model
processSelect select model =
    let
        ui =
            model.ui

        withUiSelection s =
            { ui | selected = s }

        withSelection s =
            { model | ui = withUiSelection s }

        selected =
            ui.selected

        allPlugins =
            List.map .id model.plugins
    in
    case select of
        SelectOne id ->
            withSelection (id :: selected)

        UnselectOne id ->
            withSelection (List.Extra.remove id selected)

        SelectAll ->
            withSelection allPlugins

        UnselectAll ->
            withSelection []


withSettingsError : ( String, String ) -> Model -> Model
withSettingsError error model =
    let
        updateError ui =
            { ui | settingsError = Just error }
    in
    { model | ui = updateError model.ui }


noGlobalLicense : LicenseGlobalInfo
noGlobalLicense =
    { licensees = Nothing
    , startDate = Nothing
    , endDates = Nothing
    , maxNodes = Nothing
    }


pluginStatusOrdering : Ordering PluginStatus
pluginStatusOrdering =
    Ordering.explicit [ Enabled, Disabled, Uninstalled ]


pluginDefaultOrdering : Ordering PluginInfo
pluginDefaultOrdering =
    Ordering.byFieldWith pluginStatusOrdering .status
        |> Ordering.breakTiesWith (Ordering.byField .name)
