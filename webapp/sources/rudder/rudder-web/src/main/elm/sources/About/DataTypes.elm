module About.DataTypes exposing (..)

import Http exposing (Error)
import Json.Encode exposing (Value)

type alias RudderInfo =
    { version : String
    , build   : String
    , instanceId : String
    , relays : List Relay
    }

type alias Relay =
    { uuid : String
    , hostname : String
    , managedNodes : Int
    }

type alias SystemInfo =
    { os : OperatingSystem
    , jvm : JvmInfo
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
    , enabled : Int
    , disabled : Int
    }

type alias PluginInfo =
    { id : String
    , name : String
    , version : String
    , abiVersion : String
    , license    : LicenseInfo
    }

type alias LicenseInfo =
    { licensee : String
    , startDate : String
    , endDate : String
    , allowedNodesNumber : Int
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
  = Ignore
  | GetAboutInfo  (Result Error AboutInfo)
  | Copy String
  | CopyJson Value
  | UpdateUI UI