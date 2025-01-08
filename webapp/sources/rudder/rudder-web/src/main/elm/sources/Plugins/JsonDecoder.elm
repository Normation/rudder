module Plugins.JsonDecoder exposing (..)

import Json.Decode as D exposing (..)
import Json.Decode.Pipeline exposing (optional, required)
import Plugins.DataTypes exposing (..)
import Time.TimeZone exposing (TimeZone)
import Time.ZonedDateTime exposing (ZonedDateTime)
import Time.TimeZones exposing (utc)
import Time.Iso8601
import Time.Iso8601ErrorMsg


decodeGetPluginInfos : Decoder (List PluginInfo)
decodeGetPluginInfos =
  at [ "data", "plugins" ] (list decodePluginInfo)

decodePluginInfo : Decoder PluginInfo
decodePluginInfo =
 D.succeed PluginInfo
    |> required "id" string
    |> required "name" string
    |> required "description" string
    |> required "abiVersion" string
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
  andThen (\d ->
            case d of
              Ok r -> succeed r
              Err e -> fail (String.join "\n" <| List.map (Time.Iso8601ErrorMsg.renderText "" ) e)
          ) (map (Time.Iso8601.toZonedDateTime utc) string)

decodePluginError : Decoder PluginError
decodePluginError =
    D.succeed PluginError
        |> required "error" string
        |> required "message" string
