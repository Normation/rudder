module Plugins.DataTypes exposing (..)

import Http exposing (Error)
import Http.Detailed
import Json.Encode exposing (Value)
import Time.ZonedDateTime exposing (ZonedDateTime)


type alias PluginInfo =
    { id : String
    , name : String
    , description : String
    , abiVersion : String
    , version : String
    , pluginType : PluginType
    , errors : List PluginError
    , status : PluginStatus
    , license : Maybe LicenseInfo
    }


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


type alias Model =
    { contextPath : String
    , plugins : List PluginInfo
    }


type Msg
    = ApiGetPlugins (Result (Http.Detailed.Error String) ( Http.Metadata, List PluginInfo ))
    | Copy String
    | CopyJson Value



-- | UpdateUI UI
