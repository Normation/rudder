module About.JsonEncoder exposing (..)

import About.DataTypes exposing (..)
import Json.Encode exposing (..)

encodeRudderInfo : RudderInfo -> Value
encodeRudderInfo rudderInfo =
    object
        [ ( "version", string rudderInfo.version )
        , ( "build", string rudderInfo.build )
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
        [ ( "id", string pluginInfo.id )
        , ( "name", string pluginInfo.name )
        , ( "version", string pluginInfo.version )
        , ( "abiVersion", string pluginInfo.abiVersion )
        , ( "license", encodeLicenseInfo pluginInfo.license )
        ]

encodeLicenseInfo : LicenseInfo -> Value
encodeLicenseInfo licenseInfo =
    object
        [ ( "licensee", string licenseInfo.licensee )
        , ( "startDate", string licenseInfo.startDate )
        , ( "endDate", string licenseInfo.endDate )
        , ( "allowedNodesNumber", int licenseInfo.allowedNodesNumber )
        , ( "supportedVersions", string licenseInfo.supportedVersions )
        ]

encodeAboutInfo : AboutInfo -> Value
encodeAboutInfo aboutInfo =
    object
        [ ( "rudder", encodeRudderInfo aboutInfo.rudderInfo )
        , ( "system", encodeSystemInfo aboutInfo.system )
        , ( "nodes", encodeNodesInfo aboutInfo.nodes )
        , ( "plugins", (list encodePluginInfo) aboutInfo.plugins )
        ]