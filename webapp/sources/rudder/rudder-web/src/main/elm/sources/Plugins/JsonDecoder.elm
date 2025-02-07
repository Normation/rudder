module Plugins.JsonDecoder exposing (..)

import Json.Decode as D exposing (..)
import Json.Decode.Pipeline exposing (optional, required)
import Plugins.DataTypes exposing (..)
import Time.Iso8601
import Time.Iso8601ErrorMsg
import Time.TimeZones exposing (utc)
import Time.ZonedDateTime exposing (ZonedDateTime)


decodeGetPluginsInfo : Decoder PluginsInfo
decodeGetPluginsInfo =
    at [ "data" ] decodePluginsInfo


decodePluginsInfo : Decoder PluginsInfo
decodePluginsInfo =
    D.succeed PluginsInfo
        |> optional "license" (maybe decodeLicenseGlobalInfo) Nothing
        |> required "plugins" (list decodePluginInfo)


decodeLicenseGlobalInfo : Decoder LicenseGlobalInfo
decodeLicenseGlobalInfo =
    D.succeed LicenseGlobalInfo
        |> optional "licensees" (maybe (list string)) Nothing
        |> optional "startDate" (maybe decodeDateTime) Nothing
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
                        succeed Enabled

                    "disabled" ->
                        succeed Disabled

                    "uninstalled" ->
                        succeed Uninstalled

                    _ ->
                        fail ("Unknown PluginStatus: " ++ s)
            )


decodeLicenseInfo : Decoder LicenseInfo
decodeLicenseInfo =
    D.succeed LicenseInfo
        |> required "licensee" string
        |> required "allowedNodesNumber" int
        |> required "supportedVersions" string
        |> required "startDate" decodeDateTime
        |> required "endDate" decodeDateTime



-- Defaults to UTC


decodeDateTime : Decoder ZonedDateTime
decodeDateTime =
    map (Time.Iso8601.toZonedDateTime utc) string
        |> andThen
            (\d ->
                case d of
                    Ok r ->
                        succeed r

                    Err e ->
                        fail (String.join "\n" <| List.map (Time.Iso8601ErrorMsg.renderText "") e)
            )


decodeDateCount : Decoder DateCount
decodeDateCount =
    D.succeed DateCount
        |> required "date" decodeDateTime
        |> required "count" int


decodePluginError : Decoder PluginError
decodePluginError =
    D.succeed PluginError
        |> required "error" string
        |> required "message" string
