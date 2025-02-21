module Plugins.DataTypes exposing (..)

import Dict exposing (Dict)
import Http
import Http.Detailed
import Json.Encode exposing (Value)
import Ordering exposing (Ordering)
import Set exposing (Set)
import String.Extra
import Time.ZonedDateTime exposing (ZonedDateTime)


type alias PluginsInfo =
    { license : Maybe LicenseGlobalInfo
    , plugins : List PluginInfo
    }


type alias LicenseGlobalInfo =
    { licensees : Maybe (List String)
    , startDate : Maybe ZonedDateTime
    , endDates : Maybe (List DateCount)
    , maxNodes : Maybe Int
    }


type alias DateCount =
    { date : ZonedDateTime
    , count : Int
    }


type alias PluginInfo =
    { id : PluginId
    , name : String
    , description : String
    , abiVersion : String
    , pluginVersion : String
    , version : String
    , pluginType : PluginType
    , errors : List PluginError
    , status : PluginStatus
    , license : Maybe LicenseInfo
    }


type alias PluginId =
    String


type PluginType
    = Webapp
    | Integration


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


type alias PluginError =
    { error : String
    , message : String
    }


type alias UI =
    { loading : Bool
    , view : PluginsView
    }


type InstallStatus
    = Installed ActivationStatus
    | Uninstalled


type ActivationStatus
    = Enabled
    | Disabled


type LicenseStatus
    = ValidLicense LicenseInfo
    | ExpiredLicense LicenseInfo
    | MissingLicense
    | NoLicense


{-| Result computation on install status, activation status, and license status of a plugin.
The license status cannot be changed, so it is just a limiting factor when associated with the others.
The name is long and specific enough but also descriptive (it is a sentence), so that we can generate
the message using the type.
-}
type ActionDisallowedResult
    = -- see ActionAllowedResult for (Installed,Installed): it is an upgrade
      UninstalledPluginCannotBeUninstalled -- --    (InstallStatus ⊗ InstallStatus)
    | EnabledPluginCannotBeEnabled -- --            (ActivationStatus ⊗ ActivationStatus)
    | DisabledPluginCannotBeDisabled
    | UninstalledPluginCannotBeDisabled -- --       (InstallStatus ⊗ (ActivationStatus \ InstallStatus))
    | ExpiredLicensePreventPluginInstallation -- -- (LicenseStatus ⊗ InstallStatus)
    | MissingLicensePreventPluginInstallation
    | ExpiredLicensePreventPluginActivation -- --   (LicenseStatus ⊗ ActivationStatus)
    | MissingLicensePreventPluginActivation



-- We could add logic for when a plugin will restart, by defining results :
-- e.g. uninstalling a disabled plugin
-- We should just add such a result as a parameter to the associated result type below


type ActionAllowedResult
    = AllowedAction
    | UpgradeAction


{-| Summary of what an action was allowed to do, with optional disallowed with warning.
It can be a useful description at any level
-}
type alias ActionExplanation =
    { success : Dict PluginId ActionAllowedResult
    , warning : Dict PluginId ActionDisallowedResult
    }


type ActionDisabledExplanation
    = NoPluginSelected
    | NoPluginForAction RequestType


type ActionModel
    = ActionDisabled ActionDisabledExplanation
    | ActionEnabled ActionExplanation


type alias PluginsViewModel =
    { plugins : List PluginInfo
    , selected : Set PluginId
    , modalState : ModalState
    , installAction : ActionModel
    }


type PluginsView
    = ViewPluginsList PluginsViewModel
    | ViewSettingsError ( String, String ) -- message, details


type ModalState
    = OpenModal RequestType
    | NoModal


type alias Model =
    { contextPath : String
    , license : Maybe LicenseGlobalInfo
    , ui : UI
    }


type Select
    = SelectOne PluginId
    | UnselectOne PluginId
    | SelectAll
    | UnselectAll


type RequestType
    = Install
    | Uninstall
    | Enable
    | Disable
    | UpdateIndex


type Msg
    = CallApi (Model -> Cmd Msg)
    | RequestApi RequestType (Set PluginId)
    | ApiGetPlugins (Result (Http.Detailed.Error String) ( Http.Metadata, PluginsInfo ))
    | ApiPostPlugins RequestType (Result (Http.Detailed.Error String) ())
    | SetModalState ModalState
    | ReloadPage
    | Copy String
    | CopyJson Value
    | CheckSelection Select


requestTypeText : RequestType -> String
requestTypeText t =
    case t of
        Install ->
            "install"

        Uninstall ->
            "uninstall"

        Enable ->
            "enable"

        Disable ->
            "disable"

        UpdateIndex ->
            "update index"


processSelect : Select -> Model -> Model
processSelect select model =
    model |> updatePluginsViewModel (processViewModelSelect select)


processViewModelSelect : Select -> PluginsViewModel -> PluginsViewModel
processViewModelSelect select model =
    let
        previouslySelected =
            model.selected

        getAllPlugins =
            \_ -> model.plugins |> List.map .id |> Set.fromList
    in
    model
        |> setSelected
            (case select of
                SelectOne id ->
                    Set.insert id previouslySelected

                UnselectOne id ->
                    Set.remove id previouslySelected

                SelectAll ->
                    getAllPlugins ()

                UnselectAll ->
                    Set.empty
            )
            model.plugins


updatePluginsViewModel : (PluginsViewModel -> PluginsViewModel) -> Model -> Model
updatePluginsViewModel f ({ ui } as model) =
    model
        |> setUI
            { ui
                | view =
                    case ui.view of
                        ViewPluginsList plugins ->
                            ViewPluginsList <| f plugins

                        ViewSettingsError _ ->
                            model.ui.view
            }


setSelected : Set PluginId -> List PluginInfo -> PluginsViewModel -> PluginsViewModel
setSelected plugins pluginInfos model =
    { model
        | selected = plugins
        , installAction = findInstallablePlugins plugins pluginInfos
    }


setPluginsView : List PluginInfo -> PluginsViewModel -> PluginsViewModel
setPluginsView plugins model =
    { model
        | plugins = plugins
    }


setPluginInfoStatus : PluginStatus -> PluginInfo -> PluginInfo
setPluginInfoStatus status pluginInfo =
    { pluginInfo | status = status }


setPlugins : List PluginInfo -> Model -> Model
setPlugins plugins =
    updatePluginsViewModel (setPluginsView plugins)


findInstallablePlugins : Set PluginId -> List PluginInfo -> ActionModel
findInstallablePlugins selected plugins =
    ActionEnabled { success = selected |> Set.toList |> List.map (\p -> ( p, AllowedAction )) |> Dict.fromList, warning = Dict.empty }


setModalState : ModalState -> Model -> Model
setModalState modalState =
    updatePluginsViewModel (\model -> { model | modalState = modalState })


setLicense : Maybe LicenseGlobalInfo -> Model -> Model
setLicense license model =
    { model | license = license }


setUI : UI -> Model -> Model
setUI ui model =
    { model | ui = ui }


withSettingsError : ( String, String ) -> Model -> Model
withSettingsError error model =
    let
        updateError ui =
            { ui | view = ViewSettingsError error }
    in
    { model | ui = updateError model.ui }


{-| We need to infer the plugin ABI version with respects to main version
i.e. minor version without the patch version and without the ~rc1, ~beta2, etc.
-}
pluginMinorAbiVersion : PluginInfo -> String
pluginMinorAbiVersion { abiVersion } =
    abiVersion
        |> String.split "."
        |> List.take 2
        |> String.join "."
        |> String.Extra.leftOf "~"


noGlobalLicense : LicenseGlobalInfo
noGlobalLicense =
    { licensees = Nothing
    , startDate = Nothing
    , endDates = Nothing
    , maxNodes = Nothing
    }


pluginStatusOrdering : Ordering PluginStatus
pluginStatusOrdering =
    Ordering.explicit [ StatusEnabled, StatusDisabled, StatusUninstalled ]


pluginDefaultOrdering : Ordering PluginInfo
pluginDefaultOrdering =
    Ordering.byFieldWith pluginStatusOrdering .status
        |> Ordering.breakTiesWith (Ordering.byField .name)


updateUI : (UI -> UI) -> Model -> Model
updateUI f =
    \model -> { model | ui = f model.ui }


setLoading : Bool -> Model -> Model
setLoading value =
    updateUI (\ui -> { ui | loading = value })
