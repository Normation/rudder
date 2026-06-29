module Onboarding.JsonDecoder exposing (..)

import Json.Decode as D exposing (Decoder, andThen, at, fail, string, succeed)
import Json.Decode.Pipeline exposing (optional, required)
import Onboarding.DataTypes exposing (..)
import String exposing (toLower)


decodeGetAccountSettings : Decoder AccountSettings
decodeGetAccountSettings =
    at [ "data" ] decodeAccountSettings


decodeAccountSettings : Decoder AccountSettings
decodeAccountSettings =
    D.succeed AccountSettings
        |> optional "username" (D.map Just string) Nothing
        |> optional "password" (D.map Just string) Nothing


decodeGetMetricsSettings : Decoder MetricsState
decodeGetMetricsSettings =
    at [ "data" ] decodeMetricsSettings


decodeSetupDone : Decoder Bool
decodeSetupDone =
    at [ "data", "settings", "rudder_setup_done" ] D.bool


decodeMetricsSettings : Decoder MetricsState
decodeMetricsSettings =
    at [ "settings" ]
        (at [ "send_metrics" ] D.string
            |> D.andThen
                (\str ->
                    case str of
                        _ ->
                            D.succeed NotDefined
                 {- "not_defined" ->
                      D.succeed NotDefined
                    "no" ->
                      D.succeed NoMetrics
                    "minimal" ->
                      D.succeed Minimal
                    "complete" ->
                      D.succeed Complete
                    incorrectValue ->
                      D.fail <| "Incorrect value: " ++ incorrectValue
                 -}
                )
        )
