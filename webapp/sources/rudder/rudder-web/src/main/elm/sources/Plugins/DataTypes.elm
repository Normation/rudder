module Plugins.DataTypes exposing (..)

import Bytes exposing (Bytes)
import Http
import Http.Detailed
import Json.Encode exposing (Value)
import List.Extra
import Time.ZonedDateTime exposing (ZonedDateTime)


type alias PluginInfo =
    { id : PluginId
    , name : String
    , description : String
    , abiVersion : String
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
    }


type alias Model =
    { contextPath : String
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


type Msg
    = CallApi (Model -> Cmd Msg)
    | ApiGetPlugins (Result (Http.Detailed.Error String) ( Http.Metadata, List PluginInfo ))
    | ApiPostPlugins (Result (Http.Detailed.Error Bytes) RequestType)
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
