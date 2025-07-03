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


type LicenseStatus
    = ValidLicense PluginLicense
    | NearExpirationLicense String
    | ExpiredLicense String
    | MissingLicense String
    | NoLicense


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
        licenseStatus =
            findLicenseStatus (Maybe.map toPluginLicense license) errors
    in
    { id = id
    , name = name
    , pluginType = pluginType
    , installStatus = toInstallStatus status
    , docLink = docLink { id = id, abiVersion = abiVersion }
    , description = description
    , version = pluginVersion
    , licenseStatus = licenseStatus
    , errors =
        [ toLicenseStatusCallout licenseStatus
        , findAbiVersionError errors
        , statusMessage |> Maybe.map CalloutError
        ]
            |> List.filterMap identity
            |> List.sortWith pluginCalloutErrorOrdering
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


findLicenseStatus : Maybe PluginLicense -> List PluginInfoError -> LicenseStatus
findLicenseStatus license errors =
    let
        findErr err =
            errors |> List.Extra.find (\{ error } -> error == err)
    in
    case ( license, ( findErr "license.needed.error", findErr "license.expired.error", findErr "license.near.expiration.error" ) ) of
        -- ignore current license as API already handles the message according to license data
        ( _, ( Just { message }, _, _ ) ) ->
            MissingLicense message

        ( _, ( _, Just { message }, _ ) ) ->
            ExpiredLicense message

        ( _, ( Nothing, _, Just { message } ) ) ->
            NearExpirationLicense message

        ( Just l, ( Nothing, Nothing, Nothing ) ) ->
            ValidLicense l

        ( Nothing, ( Nothing, Nothing, Nothing ) ) ->
            NoLicense


toLicenseStatusCallout : LicenseStatus -> Maybe PluginCalloutError
toLicenseStatusCallout licenseStatus =
    case licenseStatus of
        ExpiredLicense message ->
            Just (CalloutError message)

        MissingLicense message ->
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
