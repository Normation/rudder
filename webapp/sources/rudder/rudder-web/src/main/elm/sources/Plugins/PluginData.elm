module Plugins.PluginData exposing (..)

import List.Extra
import Maybe.Extra
import Ordering exposing (Ordering)
import Time.ZonedDateTime exposing (ZonedDateTime)


type alias PluginId =
    String


type alias PluginMetadata =
    { license : Maybe LicenseGlobal
    , plugins : List Plugin
    }


type PluginCalloutError
    = CalloutWarning String
    | CalloutError String


type alias Plugin =
    { id : PluginId
    , name : String
    , pluginType : PluginType
    , installStatus : InstallStatus
    , docLink : String
    , description : String
    , version : String
    , licenseStatus : LicenseStatus
    , noLicense : Bool
    , errors : List PluginCalloutError
    }


type PluginType
    = Webapp
    | Integration


type InstallStatus
    = Installed ActivationStatus
    | Uninstalled


type ActivationStatus
    = Enabled
    | Disabled


type alias PluginLicense =
    { startDate : ZonedDateTime
    , endDate : ZonedDateTime
    }


{-| The license determines the view of a plugin, and the logic of action and selection.
Apart from a valid license, there are many other statuses each displayed differently.
The case of InvalidLicense leads to plugins not being actionable, when it is expired/invalidated/missing...
-}
type LicenseStatus
    = ValidLicense PluginLicense
    | InvalidLicense String
    | NearExpirationLicense String
    | WithoutLicense


{-| The API representation of the Plugin
-}
type alias PluginInfo =
    { id : PluginId
    , name : String
    , description : String
    , abiVersion : String
    , pluginVersion : String
    , version : String
    , pluginType : PluginType
    , errors : List PluginInfoError
    , status : PluginStatus
    , statusMessage : Maybe String
    , license : Maybe LicenseInfo
    }


type alias PluginsInfo =
    { license : Maybe LicenseGlobal
    , plugins : List PluginInfo
    }


type alias LicenseGlobal =
    { licensees : Maybe (List String)
    , startDate : Maybe ZonedDateTime
    , endDates : Maybe (List DateCount)
    , maxNodes : Maybe Int
    }


type alias DateCount =
    { date : ZonedDateTime
    , count : Int
    }


type PluginStatus
    = StatusEnabled
    | StatusDisabled
    | StatusUninstalled


type alias LicenseInfo =
    { licensee : String
    , allowedNodesNumber : Int
    , supportedVersions : String
    , startDate : ZonedDateTime
    , endDate : ZonedDateTime
    }


type alias PluginInfoError =
    { error : String
    , message : String
    }


toPlugin : PluginInfo -> Plugin
toPlugin { id, name, abiVersion, pluginType, description, status, statusMessage, pluginVersion, errors, license } =
    let
        installStatus =
            toInstallStatus status

        -- statusMessage indicates the reason of disabling when the status is disabled
        statusDisabledReason =
            statusMessage |> Maybe.Extra.filter (\_ -> installStatus == Installed Disabled)

        ( noLicenseError, licenseErrorStatus ) =
            findLicenseStatus (Maybe.map toPluginLicense license) errors

        -- a plugin disabled in the webapp cannot be used, whatever its license errors say
        ( noLicense, licenseStatus ) =
            case statusDisabledReason of
                Just reason ->
                    ( False, InvalidLicense reason )

                Nothing ->
                    ( noLicenseError, licenseErrorStatus )
    in
    { id = id
    , name = name
    , pluginType = pluginType
    , installStatus = installStatus
    , docLink = docLink { id = id, abiVersion = abiVersion }
    , description = description
    , version = pluginVersion
    , licenseStatus = licenseStatus
    , noLicense = noLicense
    , errors =
        ([ toLicenseStatusCallout licenseErrorStatus
         , findAbiVersionError errors
         ]
            |> List.filterMap identity
            |> List.sortWith pluginCalloutErrorOrdering
        )
            -- the reason for disabling the plugin does not replace the license errors, it is displayed after them
            ++ (statusDisabledReason |> Maybe.map CalloutError |> Maybe.Extra.toList)
    }


findAbiVersionError : List PluginInfoError -> Maybe PluginCalloutError
findAbiVersionError =
    List.Extra.findMap
        (\{ error, message } ->
            if error == "abi.version.error" then
                Just (CalloutWarning message)

            else
                Nothing
        )


{-| The license status the plugin errors tell about, and whether the license is a missing one.
The webapp can disable a plugin for a reason of its own (see `toPlugin`), which is not a license error.
-}
findLicenseStatus : Maybe PluginLicense -> List PluginInfoError -> ( Bool, LicenseStatus )
findLicenseStatus license errors =
    let
        findErr err =
            errors |> List.Extra.find (\{ error } -> error == err)
    in
    case ( license, ( findErr "license.needed.error", findErr "license.expired.error", findErr "license.near.expiration.error" ) ) of
        -- missing license : invalid
        ( _, ( Just { message }, _, _ ) ) ->
            ( True, InvalidLicense message )

        -- expired license : invalid
        ( _, ( _, Just { message }, _ ) ) ->
            ( False, InvalidLicense message )

        ( _, ( Nothing, _, Just { message } ) ) ->
            ( False, NearExpirationLicense message )

        ( Just l, ( Nothing, Nothing, Nothing ) ) ->
            ( False, ValidLicense l )

        ( Nothing, ( Nothing, Nothing, Nothing ) ) ->
            ( False, WithoutLicense )


toLicenseStatusCallout : LicenseStatus -> Maybe PluginCalloutError
toLicenseStatusCallout licenseStatus =
    case licenseStatus of
        InvalidLicense message ->
            Just (CalloutError message)

        NearExpirationLicense message ->
            Just (CalloutWarning message)

        _ ->
            Nothing


toInstallStatus : PluginStatus -> InstallStatus
toInstallStatus p =
    case p of
        StatusEnabled ->
            Installed Enabled

        StatusDisabled ->
            Installed Disabled

        StatusUninstalled ->
            Uninstalled


toPluginLicense : LicenseInfo -> PluginLicense
toPluginLicense { startDate, endDate } =
    { startDate = startDate, endDate = endDate }


{-| We need to infer the plugin ABI version with respects to main version
i.e. minor version without the patch version and without the ~rc1, ~beta2, etc.
-}
docLink : { id : PluginId, abiVersion : String } -> String
docLink { id, abiVersion } =
    let
        rudderVersion =
            abiVersion
                |> String.split "."
                |> List.take 2
                |> String.join "."
    in
    String.join "/" [ "/rudder-doc/reference", rudderVersion, "plugins", id ++ ".html" ]


setInstallStatus : InstallStatus -> Plugin -> Plugin
setInstallStatus status plugin =
    { plugin | installStatus = status }


setPluginType : PluginType -> Plugin -> Plugin
setPluginType pluginType plugin =
    { plugin | pluginType = pluginType }


setLicenseStatus : LicenseStatus -> Plugin -> Plugin
setLicenseStatus license plugin =
    { plugin | licenseStatus = license }


pluginTypeText : PluginType -> String
pluginTypeText arg =
    case arg of
        Webapp ->
            "Webapp"

        Integration ->
            "Integration"


pluginCalloutErrorOrdering : Ordering PluginCalloutError
pluginCalloutErrorOrdering =
    Ordering.byRank
        (\err ->
            case err of
                CalloutError _ ->
                    1

                CalloutWarning _ ->
                    2
        )
        (\_ _ -> Ordering.noConflicts)
