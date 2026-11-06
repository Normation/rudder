module Plugins.JsonDecoder exposing (decodeGetPluginMetadata)

import Json.Decode as D exposing (..)
import Json.Decode.Extra exposing (datetime)
import Json.Decode.Pipeline exposing (optional, required)
import Plugins.DataTypes exposing (..)
import Plugins.PluginData exposing (..)


decodeGetPluginMetadata : Decoder PluginMetadata
decodeGetPluginMetadata =
    map
        (\{ license, plugins } ->
            { license = license
            , plugins =
                List.map toPlugin plugins
            }
        )
        decodeGetPluginsInfo


decodeGetPluginsInfo : Decoder PluginsInfo
decodeGetPluginsInfo =
    at [ "data" ] decodePluginsInfo


decodePluginsInfo : Decoder PluginsInfo
decodePluginsInfo =
    D.succeed PluginsInfo
        |> optional "license" (maybe decodeLicenseGlobal) Nothing
        |> required "plugins" (list decodePluginInfo)


decodeLicenseGlobal : Decoder LicenseGlobal
decodeLicenseGlobal =
    D.succeed LicenseGlobal
        |> optional "licensees" (maybe (list string)) Nothing
        |> optional "startDate" (maybe datetime) Nothing
        |> optional "endDates" (maybe (list decodeDateCount)) Nothing
        |> optional "maxNodes" (maybe int) Nothing


decodePluginInfo : Decoder PluginInfo
decodePluginInfo =
    D.succeed PluginInfo
        |> required "id" string
        |> required "name" string
        |> required "description" string
        |> required "abiVersion" string
        |> required "pluginVersion" string
        |> required "version" string
        |> required "pluginType" decodePluginType
        |> required "errors" (list decodePluginError)
        |> required "status" decodePluginStatus
        |> optional "statusMessage" (maybe string) Nothing
        |> optional "license" (maybe decodeLicenseInfo) Nothing


decodePluginType : Decoder PluginType
decodePluginType =
    string
        |> andThen
            (\s ->
                case s of
                    "webapp" ->
                        succeed Webapp

                    "integration" ->
                        succeed Integration

                    _ ->
                        fail ("Unknown PluginType: " ++ s)
            )


decodePluginStatus : Decoder PluginStatus
decodePluginStatus =
    string
        |> andThen
            (\s ->
                case s of
                    "enabled" ->
                        succeed StatusEnabled

                    "disabled" ->
                        succeed StatusDisabled

                    "uninstalled" ->
                        succeed StatusUninstalled

                    _ ->
                        fail ("Unknown PluginStatus: " ++ s)
            )


decodeLicenseInfo : Decoder LicenseInfo
decodeLicenseInfo =
    D.succeed LicenseInfo
        |> required "licensee" string
        |> required "allowedNodesNumber" int
        |> required "supportedVersions" string
        |> required "startDate" datetime
        |> required "endDate" datetime


decodeDateCount : Decoder DateCount
decodeDateCount =
    D.succeed DateCount
        |> required "date" datetime
        |> required "count" int


decodePluginError : Decoder PluginInfoError
decodePluginError =
    D.succeed PluginInfoError
        |> required "error" string
        |> required "message" string
