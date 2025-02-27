module Plugins.DataTypes exposing (..)

import Dict exposing (Dict)
import Http
import Http.Detailed
import Json.Encode exposing (Value)
import List.Extra
import Maybe.Extra
import Ordering exposing (Ordering)
import Plugins.Action exposing (..)
import Plugins.PluginData exposing (..)
import Plugins.Select exposing (..)
import Set exposing (Set)
import Time exposing (Posix)


type alias UI =
    { loading : Bool
    , view : PluginsView
    }


type alias PluginsViewModel =
    { plugins : Dict PluginId Plugin
    , modalState : ModalState
    , filters : Filters
    , selected : Selected
    , installAction : PluginsAction
    , enableAction : PluginsAction
    , disableAction : PluginsAction
    , uninstallAction : PluginsAction
    }


type PluginsView
    = ViewPluginsList PluginsViewModel
    | ViewActionError ( String, String ) PluginsViewModel -- (message, details)
    | ViewSettingError ( String, String )


type ModalState
    = OpenModal ModalAction PluginsActionExplanation
    | ErrorModal ModalAction
    | NoModal


type ModalAction
    = ModalInstallUpgrade
    | ModalActionEnable
    | ModalActionDisable
    | ModalActionUninstall


type alias Filters =
    { search : String
    , pluginType : FilterByPluginType
    , installStatus : FilterByInstallStatus
    }


type FilterByPluginType
    = FilterByPluginType PluginType
    | FilterByAllPluginType


type FilterByInstallStatus
    = FilterByInstallStatus InstallStatus
    | FilterByAllInstallStatus


type alias Model =
    { contextPath : String
    , license : Maybe LicenseGlobal
    , plugins : Dict PluginId Plugin
    , now : Posix
    , ui : UI
    }


type RequestType
    = Install
    | Uninstall
    | Enable
    | Disable
    | UpdateIndex


type Msg
    = CallApi (Model -> Cmd Msg)
    | RequestApi RequestType (Set PluginId)
    | ApiGetPlugins (Result (Http.Detailed.Error String) ( Http.Metadata, PluginMetadata ))
    | ApiPostPlugins RequestType (Result (Http.Detailed.Error String) ())
    | SetModalState ModalState
    | ResetPluginListFromModal PluginsViewModel
    | ReloadPage
    | Copy String
    | CopyJson Value
    | CheckSelection Select
    | UpdateFilters Filters
    | Now Posix


initFilters : Filters
initFilters =
    { search = ""
    , pluginType = FilterByAllPluginType
    , installStatus = FilterByAllInstallStatus
    }


processSelect : Select -> Model -> Model
processSelect s model =
    model |> updatePluginsViewModel (select s model.plugins)


setView : PluginsView -> Model -> Model
setView view =
    updateView (\_ -> view)


updateView : (PluginsView -> PluginsView) -> Model -> Model
updateView f ({ ui } as model) =
    model |> setUI { ui | view = f ui.view }


applyFilters : Filters -> Dict PluginId Plugin -> PluginsViewModel -> PluginsViewModel
applyFilters ({ search, installStatus, pluginType } as filters) initialPlugins model =
    let
        onSearch { id, name, description } =
            [ id, name, description ]
                |> List.map String.toLower
                |> List.Extra.find (String.contains (String.toLower search))
                |> Maybe.Extra.isJust

        onInstallStatus p =
            case installStatus of
                FilterByAllInstallStatus ->
                    True

                FilterByInstallStatus s ->
                    p.installStatus == s

        onPluginType p =
            case pluginType of
                FilterByAllPluginType ->
                    True

                FilterByPluginType t ->
                    p.pluginType == t

        plugins =
            initialPlugins
                |> Dict.filter
                    (\_ ->
                        \p ->
                            onInstallStatus p && onPluginType p && onSearch p
                    )
    in
    { model
        | filters = filters
        , plugins = plugins
    }


updatePluginsViewModel : (PluginsViewModel -> PluginsViewModel) -> Model -> Model
updatePluginsViewModel f ({ ui } as model) =
    model
        |> setUI
            { ui
                | view =
                    case ui.view of
                        ViewPluginsList plugins ->
                            ViewPluginsList <| f plugins

                        _ ->
                            ui.view
            }


setPluginsView : Dict PluginId Plugin -> PluginsViewModel -> PluginsViewModel
setPluginsView plugins model =
    setNotSelectablePlugins plugins
        { model
            | plugins = plugins
        }


processPlugins : List Plugin -> Model -> Model
processPlugins plugins =
    let
        d =
            pluginsFromList plugins
    in
    setPlugins d >> updatePluginsViewModel (setPluginsView d)


pluginsFromList : List Plugin -> Dict PluginId Plugin
pluginsFromList plugins =
    plugins |> List.map (\p -> ( p.id, p )) |> Dict.fromList


setPlugins : Dict PluginId Plugin -> Model -> Model
setPlugins plugins model =
    { model | plugins = plugins }


setModalState : ModalState -> PluginsViewModel -> PluginsViewModel
setModalState modalState model =
    { model | modalState = modalState }


processFilters : Filters -> Model -> Model
processFilters filters model =
    updatePluginsViewModel (applyFilters filters model.plugins) model


setLicense : Maybe LicenseGlobal -> Model -> Model
setLicense license model =
    { model | license = license }


setUI : UI -> Model -> Model
setUI ui model =
    { model | ui = ui }


setSettingsError : ( String, String ) -> Model -> Model
setSettingsError error model =
    let
        updateError ui =
            { ui | view = ViewSettingError error, loading = False }
    in
    { model | ui = updateError model.ui }


setActionError : ( String, String ) -> Model -> Model
setActionError error ({ ui } as model) =
    case ui.view of
        ViewPluginsList ({ modalState } as pluginViewModel) ->
            case modalState of
                OpenModal action _ ->
                    model |> setUI { ui | view = ViewActionError error { pluginViewModel | modalState = ErrorModal action }, loading = False }

                _ ->
                    model

        ViewActionError _ ({ modalState } as pluginViewModel) ->
            case modalState of
                ErrorModal _ ->
                    -- modal should be left open
                    model |> setUI { ui | view = ViewActionError error pluginViewModel, loading = False }

                _ ->
                    model

        ViewSettingError _ ->
            model


noGlobalLicense : LicenseGlobal
noGlobalLicense =
    { licensees = Nothing
    , startDate = Nothing
    , endDates = Nothing
    , maxNodes = Nothing
    }


statusOrdering : Ordering InstallStatus
statusOrdering =
    Ordering.explicit [ Installed Enabled, Installed Disabled, Uninstalled ]


pluginDefaultOrdering : Ordering Plugin
pluginDefaultOrdering =
    Ordering.byFieldWith statusOrdering .installStatus
        |> Ordering.breakTiesWith (Ordering.byField .name)


updateUI : (UI -> UI) -> Model -> Model
updateUI f =
    \model -> { model | ui = f model.ui }


setLoading : Bool -> Model -> Model
setLoading value =
    updateUI (\ui -> { ui | loading = value })


actionRequestType : ModalAction -> RequestType
actionRequestType action =
    case action of
        ModalInstallUpgrade ->
            Install

        ModalActionEnable ->
            Enable

        ModalActionDisable ->
            Disable

        ModalActionUninstall ->
            Uninstall


actionToModal : Action -> ModalAction
actionToModal action =
    case action of
        ActionInstall ->
            ModalInstallUpgrade

        ActionUpgrade ->
            ModalInstallUpgrade

        ActionEnable ->
            ModalActionEnable

        ActionDisable ->
            ModalActionDisable

        ActionUninstall ->
            ModalActionUninstall


{-| Used for modal name / title, so it is a human name
-}
modalActionText : ModalAction -> String
modalActionText action =
    case action of
        ModalInstallUpgrade ->
            "Install/Upgrade"

        ModalActionEnable ->
            "Enable"

        ModalActionDisable ->
            "Disable"

        ModalActionUninstall ->
            "Uninstall"


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
