module Onboarding.JsonDecoder exposing (..)

import Json.Decode as D exposing (Decoder, andThen, fail, string, succeed, at)
import Json.Decode.Pipeline exposing (required, optional)
import String exposing (toLower)

import Onboarding.DataTypes exposing (..)


decodeGetAccountSettings : Decoder AccountSettings
decodeGetAccountSettings =
  at [ "data", "pluginSettings" ] decodeAccountSettings

decodeAccountSettings : Decoder AccountSettings
decodeAccountSettings =
  D.succeed AccountSettings
    |> required "username"      D.string
    |> optional "password"      (D.map Just string) Nothing
    |> required "url"           D.string
    |> optional "proxyUrl"      (D.map Just string) Nothing
    |> optional "proxyUser"     (D.map Just string) Nothing
    |> optional "proxyPassword" (D.map Just string) Nothing

decodeGetMetricsSettings : Decoder MetricsState
decodeGetMetricsSettings =
  at [ "data" ] decodeMetricsSettings


decodeSetupDone : Decoder Bool
decodeSetupDone =
  at [ "data", "settings", "rudder_setup_done" ] D.bool

decodeMetricsSettings : Decoder MetricsState
decodeMetricsSettings =
  at [ "settings" ] (at [ "send_metrics" ] D.string
    |> D.andThen (\str ->
       case str of
        _ ->   D.succeed NotDefined
       {- "not_defined" ->
          D.succeed NotDefined
        "no" ->
          D.succeed NoMetrics
        "minimal" ->
          D.succeed Minimal
        "complete" ->
          D.succeed Complete
        incorrectValue ->
          D.fail <| "Incorrect value: " ++ incorrectValue -}
    ))

