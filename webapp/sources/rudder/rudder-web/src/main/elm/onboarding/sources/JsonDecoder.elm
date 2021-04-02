module JsonDecoder exposing (..)

import DataTypes exposing (..)
import Json.Decode as D exposing (Decoder, andThen, fail, string, succeed, at)
import Json.Decode.Pipeline exposing (required, optional)
import String exposing (toLower)

decodeGetAccountSettings : Decoder AccountSettings
decodeGetAccountSettings =
  at [ "data", "pluginSettings" ] decodeAccountSettings

decodeAccountSettings : Decoder AccountSettings
decodeAccountSettings =
  D.succeed AccountSettings
    |> required "username"      D.string
    |> required "password"      D.string
    |> required "url"           D.string
    |> optional "proxyUrl"      (D.map Just string) Nothing
    |> optional "proxyUser"     (D.map Just string) Nothing
    |> optional "proxyPassword" (D.map Just string) Nothing

decodeGetMetricsSettings : Decoder MetricsState
decodeGetMetricsSettings =
  at [ "data" ] decodeMetricsSettings

decodeMetricsSettings : Decoder MetricsState
decodeMetricsSettings =
  at [ "settings" ] (at [ "send_metrics" ] D.string
    |> D.andThen (\str ->
       case str of
        "not_defined" ->
          D.succeed NotDefined
        "no" ->
          D.succeed NoMetrics
        "minimal" ->
          D.succeed Minimal
        "complete" ->
          D.succeed Complete
        incorrectValue ->
          D.fail <| "Incorrect value: " ++ incorrectValue
    ))

