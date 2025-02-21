module Plugins.Tests.PluginsTests exposing (suite)

import Dict
import Expect exposing (..)
import Fuzz exposing (..)
import Plugins.DataTypes exposing (..)
import Plugins.Init exposing (initPluginsViewModel)
import Test exposing (..)
import Time.Iso8601
import Time.TimeZones exposing (utc)
import Time.ZonedDateTime


pluginInfoFuzz : Fuzzer PluginInfo
pluginInfoFuzz =
    constant PluginInfo
        |> andMap string
        |> andMap string
        |> andMap string
        |> andMap string
        |> andMap string
        |> andMap string
        |> andMap pluginTypeFuzzer
        |> andMap (list pluginErrorFuzzer)
        |> andMap pluginStatusFuzzer
        |> andMap (maybe licenseInfoFuzzer)


pluginTypeFuzzer : Fuzzer PluginType
pluginTypeFuzzer =
    oneOf [ constant Webapp, constant Integration ]


pluginErrorFuzzer : Fuzzer PluginError
pluginErrorFuzzer =
    map2 PluginError string string


pluginStatusFuzzer : Fuzzer PluginStatus
pluginStatusFuzzer =
    oneOf [ constant StatusEnabled, constant StatusDisabled, constant StatusUninstalled ]


licenseInfoFuzzer : Fuzzer LicenseInfo
licenseInfoFuzzer =
    map5 LicenseInfo string int string zonedDateTimeFuzzer zonedDateTimeFuzzer


zonedDateTimeFuzzer : Fuzzer Time.ZonedDateTime.ZonedDateTime
zonedDateTimeFuzzer =
    constant (Time.Iso8601.toZonedDateTime utc "2025-02-12T10:12:14Z")
        |> filterMap Result.toMaybe


suite =
    describe "Plugins.module"
        [ fuzz (list pluginInfoFuzz) "initialize install action" <|
            \plugins -> initPluginsViewModel |> setPluginsView plugins |> .installAction |> Expect.equal (ActionDisabled NoPluginSelected)
        , fuzz pluginInfoFuzz "allow installing installable plugin" <|
            \plugin ->
                let
                    plugins =
                        setPluginInfoStatus StatusUninstalled plugin |> List.singleton

                    viewModel =
                        initPluginsViewModel
                            |> setPluginsView plugins
                            |> processViewModelSelect SelectAll

                    expected =
                        ActionEnabled { success = Dict.fromList [ ( plugin.id, AllowedAction ) ], warning = Dict.empty }
                in
                viewModel |> .installAction |> Expect.equal expected
        ]
