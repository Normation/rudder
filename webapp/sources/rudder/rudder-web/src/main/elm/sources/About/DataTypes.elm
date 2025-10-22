module About.DataTypes exposing (..)

import Http exposing (Error)
import Http.Detailed
import Json.Encode exposing (Value)

type alias RudderInfo =
    { version   : String
    , buildTime : String
    , instanceId: String
    , relays    : List Relay
    }

type alias Relay =
    { uuid : String
    , hostname : String
    , managedNodes : Int
    }

type alias SystemInfo =
    { os : OperatingSystem
    , jvm: JvmInfo
    }

type alias OperatingSystem =
    { name : String
    , version : String
    }

type alias JvmInfo =
    { version : String
    , cmd : String
    }

type alias NodesInfo =
    { total : Int
    , audit : Int
    , enforce : Int
    , mixed : Int
    , enabled : Int
    , disabled : Int
    }

type alias PluginInfo =
    { name : String
    , version : String
    , abiVersion : String
    , license    : Maybe LicenseInfo
    }

type alias LicenseInfo =
    { licensee : String
    , startDate : String
    , endDate : String
    , allowedNodesNumber : Maybe Int
    , supportedVersions : String
    }


type alias AboutInfo =
    { rudderInfo : RudderInfo
    , system : SystemInfo
    , nodes : NodesInfo
    , plugins : List PluginInfo
    }

type alias Model =
    { contextPath : String
    , info : Maybe AboutInfo
    , ui : UI
    }

type alias UI =
    { loading : Bool
    , showRelays : Bool
    , showPlugins : Bool
    }

type Msg
  = ApiGetAboutInfo (Result (Http.Detailed.Error String) ( Http.Metadata, AboutInfo ))
  | Copy String
  | CopyJson Value
  | UpdateUI UI
