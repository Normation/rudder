module About.JsonEncoder exposing (..)

import About.DataTypes exposing (..)
import Json.Encode exposing (..)

encodeRudderInfo : RudderInfo -> Value
encodeRudderInfo rudderInfo =
    object
        [ ( "version", string rudderInfo.version )
        , ( "buildTime", string rudderInfo.buildTime )
        , ( "instanceId", string rudderInfo.instanceId )
        , ( "relays", (list encodeRelay) rudderInfo.relays )
        ]

encodeRelay : Relay -> Value
encodeRelay relay =
    object
        [ ( "uuid", string relay.uuid )
        , ( "hostname", string relay.hostname )
        , ( "managedNodes", int relay.managedNodes )
        ]

encodeSystemInfo : SystemInfo -> Value
encodeSystemInfo systemInfo =
    object
        [ ( "os", encodeOs systemInfo.os )
        , ( "jvm", encodeJvm systemInfo.jvm )
        ]

encodeOs : OperatingSystem -> Value
encodeOs os =
    object
        [ ( "name", string os.name )
        , ( "version", string os.version )
        ]
encodeJvm : JvmInfo -> Value
encodeJvm jvm =
    object
        [ ( "version", string jvm.version )
        , ( "cmd", string jvm.cmd )
        ]

encodeNodesInfo : NodesInfo -> Value
encodeNodesInfo nodesInfo =
    object
        [ ( "total", int nodesInfo.total )
        , ( "audit", int nodesInfo.audit )
        , ( "enforce", int nodesInfo.enforce )
        , ( "enabled", int nodesInfo.enabled )
        , ( "disabled", int nodesInfo.disabled )
        ]

encodePluginInfo : PluginInfo -> Value
encodePluginInfo pluginInfo =
    object
        [ ( "name", string pluginInfo.name )
        , ( "version", string pluginInfo.version )
        , ( "abiVersion", string pluginInfo.abiVersion )
        , ( "license", encodeLicenseInfo pluginInfo.license )
        ]

encodeLicenseInfo : Maybe LicenseInfo -> Value
encodeLicenseInfo licenseInfo =
  case licenseInfo of
    Nothing -> null
    Just li ->  object
        [ ( "licensee", string li.licensee )
        , ( "startDate", string li.startDate )
        , ( "endDate", string li.endDate )
        , ( "allowedNodesNumber", Maybe.map int li.allowedNodesNumber |> Maybe.withDefault null )
        , ( "supportedVersions", string li.supportedVersions )
        ]

encodeAboutInfo : AboutInfo -> Value
encodeAboutInfo aboutInfo =
    object
        [ ( "rudder", encodeRudderInfo aboutInfo.rudderInfo )
        , ( "system", encodeSystemInfo aboutInfo.system )
        , ( "nodes", encodeNodesInfo aboutInfo.nodes )
        , ( "plugins", (list encodePluginInfo) aboutInfo.plugins )
        ]
