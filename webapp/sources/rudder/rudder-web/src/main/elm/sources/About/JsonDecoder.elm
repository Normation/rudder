module About.JsonDecoder exposing (..)

import Json.Decode as D exposing (..)
import Json.Decode.Pipeline exposing (required, optional)

import About.DataTypes exposing (..)


decodeApiGetAboutInfo : Decoder AboutInfo
decodeApiGetAboutInfo =
  at [ "data" ] decodeAboutInfo

decodeAboutInfo : Decoder AboutInfo
decodeAboutInfo =
  D.succeed AboutInfo
    |> required "rudder" decodeRudderInfo
    |> required "system" decodeSystemInfo
    |> required "nodes" decodeNodesInfo
    |> required "plugins" (list decodePluginInfo)

decodeRudderInfo : Decoder RudderInfo
decodeRudderInfo =
  D.succeed RudderInfo
    |> required "version" string
    |> required "buildTime" string
    |> required "instanceId" string
    |> required "relays" (list decodeRelay)

decodeRelay : Decoder Relay
decodeRelay =
  D.succeed Relay
    |> required "uuid" string
    |> required "hostname" string
    |> required "managedNodes" int

decodeSystemInfo : Decoder SystemInfo
decodeSystemInfo =
  D.succeed SystemInfo
    |> required "os" decodeOperatingSystem
    |> required "jvm" decodeJvm

decodeOperatingSystem : Decoder OperatingSystem
decodeOperatingSystem =
  D.succeed OperatingSystem
    |> required "name" string
    |> required "version" string

decodeJvm : Decoder JvmInfo
decodeJvm =
  D.succeed JvmInfo
    |> required "version" string
    |> required "cmd" string

decodeNodesInfo : Decoder NodesInfo
decodeNodesInfo =
  D.succeed NodesInfo
    |> required "total" int
    |> required "audit" int
    |> required "mixed" int
    |> required "enforce" int
    |> required "enabled" int
    |> required "disabled" int

decodePluginInfo : Decoder PluginInfo
decodePluginInfo =
  D.succeed PluginInfo
    |> required "name" string
    |> required "version" string
    |> required "abiVersion" string
    |> optional "license" (D.maybe decodeLicenseInfo) Nothing

decodeLicenseInfo : Decoder LicenseInfo
decodeLicenseInfo =
  D.succeed LicenseInfo
    |> required "licensee" string
    |> required "startDate" string
    |> required "endDate" string
    |> optional "allowedNodesNumber" (D.maybe int) Nothing
    |> required "supportedVersions" string
