module Plugins.Tests.PluginsTests exposing (suite)

import Dict
import Expect exposing (..)
import Fuzz exposing (..)
import Plugins.Action exposing (..)
import Plugins.DataTypes exposing (PluginsViewModel, pluginsFromList, setViewModelPlugins)
import Plugins.Init exposing (initPluginsViewModel)
import Plugins.PluginData exposing (..)
import Plugins.Select exposing (..)
import Test exposing (..)
import Time.Iso8601
import Time.TimeZones exposing (utc)
import Time.ZonedDateTime


pluginFuzz : Fuzzer Plugin
pluginFuzz =
    constant Plugin
        |> andMap string
        |> andMap string
        |> andMap pluginTypeFuzzer
        |> andMap installStatusFuzzer
        |> andMap string
        |> andMap string
        |> andMap string
        |> andMap licenseStatusFuzzer
        |> andMap (maybe string)


licenseStatusFuzzer : Fuzzer LicenseStatus
licenseStatusFuzzer =
    oneOf
        [ map ValidLicense pluginLicenseFuzzer
        , map NearExpirationLicense string
        , map ExpiredLicense string
        , map MissingLicense string
        , constant NoLicense
        ]


pluginLicenseFuzzer : Fuzzer PluginLicense
pluginLicenseFuzzer =
    map2 PluginLicense zonedDateTimeFuzzer zonedDateTimeFuzzer


installStatusFuzzer : Fuzzer InstallStatus
installStatusFuzzer =
    oneOf [ map Installed activationStatusFuzzer, constant Uninstalled ]


activationStatusFuzzer : Fuzzer ActivationStatus
activationStatusFuzzer =
    oneOf [ constant Enabled, constant Disabled ]


pluginTypeFuzzer : Fuzzer PluginType
pluginTypeFuzzer =
    oneOf [ constant Webapp, constant Integration ]


pluginErrorFuzzer : Fuzzer PluginInfoError
pluginErrorFuzzer =
    map2 PluginInfoError string string


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


fuzzPluginExpectAction : String -> (PluginsViewModel -> PluginsAction) -> InstallStatus -> PluginType -> LicenseStatus -> (PluginId -> Select) -> (Plugin -> (PluginsAction -> Expectation)) -> Test
fuzzPluginExpectAction msg toAction status pluginType license sel toExpectation =
    fuzz pluginFuzz msg <|
        \plugin ->
            let
                plugins =
                    Dict.singleton plugin.id (plugin |> setPluginType pluginType |> setInstallStatus status |> setLicenseStatus license)

                viewModel =
                    initPluginsViewModel
                        |> setViewModelPlugins plugins
                        |> select (sel plugin.id) plugins
            in
            viewModel |> toAction |> toExpectation plugin


fuzzPlugin : String -> (PluginsViewModel -> PluginsAction) -> InstallStatus -> PluginType -> LicenseStatus -> (PluginId -> Select) -> ActionModel -> Test
fuzzPlugin msg toAction status pluginType license sel singleActionResult =
    fuzzPluginExpectAction msg toAction status pluginType license sel (\p -> Expect.equal <| pluginAction ( p.id, singleActionResult ))


fuzzSelectValidLicensePlugin : String -> (PluginsViewModel -> PluginsAction) -> InstallStatus -> LicenseStatus -> Action -> Test
fuzzSelectValidLicensePlugin msg toAction status license action =
    fuzzPlugin msg toAction status Webapp license SelectOne (AllowedAction action)


fuzzSelectValidPlugin : String -> (PluginsViewModel -> PluginsAction) -> InstallStatus -> Action -> Test
fuzzSelectValidPlugin msg toAction status action =
    fuzzSelectValidLicensePlugin msg toAction status NoLicense action


suite =
    describe "Plugins.module"
        [ describe "select valid plugins"
            [ -- install
              fuzz (list pluginFuzz) "initialize install action" <|
                \plugins -> initPluginsViewModel |> setViewModelPlugins (pluginsFromList plugins) |> .installAction |> Expect.equal initPluginsAction
            , fuzzSelectValidPlugin "allow installing uninstalled plugin" .installAction Uninstalled ActionInstall
            , fuzzSelectValidPlugin "allow upgrading already installed (enabled)" .installAction (Installed Enabled) ActionUpgrade
            , fuzzSelectValidPlugin "allow upgrading already installed (disabled)" .installAction (Installed Disabled) ActionUpgrade

            -- enable
            , fuzz (list pluginFuzz) "initialize enable action" <|
                \plugins -> initPluginsViewModel |> setViewModelPlugins (pluginsFromList plugins) |> .enableAction |> Expect.equal initPluginsAction
            , fuzzSelectValidPlugin "allow enabling disabled plugin" .enableAction (Installed Disabled) ActionEnable

            -- disable
            , fuzz (list pluginFuzz) "initialize disable action" <|
                \plugins -> initPluginsViewModel |> setViewModelPlugins (pluginsFromList plugins) |> .disableAction |> Expect.equal initPluginsAction
            , fuzzSelectValidPlugin "allow disabling enabled webapp plugin" .disableAction (Installed Enabled) ActionDisable

            -- uninstall
            , fuzz (list pluginFuzz) "initialize uninstall action" <|
                \plugins -> initPluginsViewModel |> setViewModelPlugins (pluginsFromList plugins) |> .disableAction |> Expect.equal initPluginsAction
            , fuzzSelectValidPlugin "allow uninstalling enabled plugin" .uninstallAction (Installed Enabled) ActionUninstall
            , fuzzSelectValidPlugin "allow uninstalling disabled plugin" .uninstallAction (Installed Disabled) ActionUninstall

            -- license
            , fuzzSelectValidLicensePlugin "allow disabling plugin with expired license" .disableAction (Installed Enabled) (ExpiredLicense "") ActionDisable
            , fuzzSelectValidLicensePlugin "allow uninstalling plugin with expired license" .uninstallAction (Installed Disabled) (ExpiredLicense "") ActionUninstall
            , fuzzSelectValidLicensePlugin "allow disabling plugin with no license" .disableAction (Installed Enabled) (MissingLicense "") ActionDisable
            , fuzzSelectValidLicensePlugin "allow uninstalling plugin with no license" .uninstallAction (Installed Disabled) (MissingLicense "") ActionUninstall
            ]
        , describe "disallow selection" <| List.map testDisallowedResult allSortedActionDisallowedResult
        ]


{-| To ensure every error is mapped to a test
-}
testDisallowedResult : ActionDisallowedResult -> Test
testDisallowedResult result =
    case result of
        PluginAlreadyUninstalled ->
            fuzzPlugin "disallow uninstalling already uninstalled plugin" .uninstallAction Uninstalled Webapp NoLicense SelectOne (DisallowedAction PluginAlreadyUninstalled)

        PluginAlreadyEnabled ->
            fuzzPlugin "disallow enabling already enabled plugin" .enableAction (Installed Enabled) Webapp NoLicense SelectOne (DisallowedAction PluginAlreadyEnabled)

        PluginAlreadyDisabled ->
            fuzzPlugin "disallow disabling already disabled plugin" .disableAction (Installed Disabled) Webapp NoLicense SelectOne (DisallowedAction PluginAlreadyDisabled)

        UninstalledPluginCannotBeEnabled ->
            fuzzPlugin "disallow enabling uninstalled plugin" .enableAction Uninstalled Webapp NoLicense SelectOne (DisallowedAction UninstalledPluginCannotBeEnabled)

        UninstalledPluginCannotBeDisabled ->
            fuzzPlugin "disallow disabling uninstalled plugin" .disableAction Uninstalled Webapp NoLicense SelectOne (DisallowedAction UninstalledPluginCannotBeDisabled)

        IntegrationPluginCannotBeDisabled ->
            fuzzPlugin "disallow disabling integration plugin" .disableAction (Installed Enabled) Integration NoLicense SelectOne (DisallowedAction IntegrationPluginCannotBeDisabled)

        ExpiredLicensePreventPluginActivation ->
            fuzzPlugin "disallow enabling plugin with expired license" .enableAction (Installed Disabled) Webapp (ExpiredLicense "") SelectOne (DisallowedAction ExpiredLicensePreventPluginActivation)

        MissingLicensePreventPluginActivation ->
            fuzzPlugin "disallow enabling plugin with no license" .enableAction (Installed Disabled) Webapp (MissingLicense "") SelectOne (DisallowedAction MissingLicensePreventPluginActivation)

        ExpiredLicensePreventPluginInstallation ->
            -- plugin is non-selectable, selection prevents from selecting it
            fuzzPluginExpectAction "disallow installing plugin with expired license" .installAction Uninstalled Webapp (ExpiredLicense "") SelectOne (\_ -> Expect.equal initPluginsAction)

        MissingLicensePreventPluginInstallation ->
            -- plugin is non-selectable, selection prevents from selecting it
            fuzzPluginExpectAction "disallow installing plugin with no license" .installAction Uninstalled Webapp (MissingLicense "") SelectOne (\_ -> Expect.equal initPluginsAction)
