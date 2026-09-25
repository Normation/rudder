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
        |> andMap bool
        |> andMap (list pluginCalloutFuzzer)


pluginCalloutFuzzer : Fuzzer PluginCalloutError
pluginCalloutFuzzer =
    oneOf [ map CalloutError string, map CalloutWarning string ]


licenseStatusFuzzer : Fuzzer LicenseStatus
licenseStatusFuzzer =
    oneOf
        [ map ValidLicense pluginLicenseFuzzer
        , map NearExpirationLicense string
        , map InvalidLicense string
        , constant WithoutLicense
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
    fuzzSelectValidLicensePlugin msg toAction status WithoutLicense action


{-| A webapp plugin without license, the status message and the errors are the ones under test
-}
pluginInfo : PluginStatus -> Maybe String -> List PluginInfoError -> PluginInfo
pluginInfo status statusMessage errors =
    { id = "rudder-plugin-test"
    , name = "rudder-plugin-test"
    , description = "Test plugin"
    , abiVersion = "9.1.5"
    , pluginVersion = "1.2.0"
    , version = "9.1.5-1.2.0"
    , pluginType = Webapp
    , errors = errors
    , status = status
    , statusMessage = statusMessage
    , license = Nothing
    }


suite =
    describe "Plugins.module"
        [ describe "plugin errors"
            [ test "display the reason for disabling the plugin after all other errors" <|
                \_ ->
                    pluginInfo StatusDisabled
                        (Just "version is out of the license range")
                        [ PluginInfoError "abi.version.error" "plugin was not built for this Rudder version"
                        , PluginInfoError "license.expired.error" "license has expired on 2025-03-08"
                        ]
                        |> toPlugin
                        |> .errors
                        |> Expect.equal
                            [ CalloutError "license has expired on 2025-03-08"
                            , CalloutWarning "plugin was not built for this Rudder version"
                            , CalloutError "version is out of the license range"
                            ]
            , test "keep the plugin unusable when the webapp disabled it without license error" <|
                \_ ->
                    pluginInfo StatusDisabled (Just "version is out of the license range") []
                        |> toPlugin
                        |> .licenseStatus
                        |> Expect.equal (InvalidLicense "version is out of the license range")
            , test "ignore the status message of a plugin that is not disabled" <|
                \_ ->
                    pluginInfo StatusEnabled (Just "some information") []
                        |> toPlugin
                        |> .errors
                        |> Expect.equal []
            ]
        , describe "select valid plugins"
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
            , fuzzSelectValidLicensePlugin "allow disabling plugin with invalid license" .disableAction (Installed Enabled) (InvalidLicense "") ActionDisable
            , fuzzSelectValidLicensePlugin "allow uninstalling plugin with invalid license" .uninstallAction (Installed Disabled) (InvalidLicense "") ActionUninstall
            ]
        , describe "disallow selection" <| List.map testDisallowedResult allSortedActionDisallowedResult
        ]


{-| To ensure every error is mapped to a test
-}
testDisallowedResult : ActionDisallowedResult -> Test
testDisallowedResult result =
    case result of
        PluginAlreadyUninstalled ->
            fuzzPlugin "disallow uninstalling already uninstalled plugin" .uninstallAction Uninstalled Webapp WithoutLicense SelectOne (DisallowedAction PluginAlreadyUninstalled)

        PluginAlreadyEnabled ->
            fuzzPlugin "disallow enabling already enabled plugin" .enableAction (Installed Enabled) Webapp WithoutLicense SelectOne (DisallowedAction PluginAlreadyEnabled)

        PluginAlreadyDisabled ->
            fuzzPlugin "disallow disabling already disabled plugin" .disableAction (Installed Disabled) Webapp WithoutLicense SelectOne (DisallowedAction PluginAlreadyDisabled)

        UninstalledPluginCannotBeEnabled ->
            fuzzPlugin "disallow enabling uninstalled plugin" .enableAction Uninstalled Webapp WithoutLicense SelectOne (DisallowedAction UninstalledPluginCannotBeEnabled)

        UninstalledPluginCannotBeDisabled ->
            fuzzPlugin "disallow disabling uninstalled plugin" .disableAction Uninstalled Webapp WithoutLicense SelectOne (DisallowedAction UninstalledPluginCannotBeDisabled)

        IntegrationPluginCannotBeDisabled ->
            fuzzPlugin "disallow disabling integration plugin" .disableAction (Installed Enabled) Integration WithoutLicense SelectOne (DisallowedAction IntegrationPluginCannotBeDisabled)

        InvalidLicensePreventPluginActivation ->
            fuzzPlugin "disallow enabling plugin with invalid license" .enableAction (Installed Disabled) Webapp (InvalidLicense "") SelectOne (DisallowedAction InvalidLicensePreventPluginActivation)

        InvalidLicensePreventPluginInstallation ->
            -- plugin is non-selectable, selection prevents from selecting it
            fuzzPluginExpectAction "disallow installing plugin with expired license" .installAction Uninstalled Webapp (InvalidLicense "") SelectOne (\_ -> Expect.equal initPluginsAction)
