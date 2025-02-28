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


type alias ContextPath =
    String


type alias UI =
    { loading : Bool
    , view : PluginsView
    }


type alias PluginsViewModel =
    { plugins : Dict PluginId Plugin
    , filters : Filters
    , selected : Selected
    , installAction : PluginsAction
    , enableAction : PluginsAction
    , disableAction : PluginsAction
    , uninstallAction : PluginsAction
    }


type alias PluginsView =
    { viewModel : PluginsViewModel
    , modalState : ModalState
    , error : Maybe ViewError
    }


type ViewError
    = ViewActionError ( String, String )
    | ViewSettingsError PluginSettingsError


type PluginSettingsError
    = CredentialsError
    | ConfigurationError


type ModalState
    = OpenModal ModalAction PluginsActionExplanation
    | ErrorModal ModalAction ( String, String )
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
    { contextPath : ContextPath
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


updatePluginsView : (PluginsView -> PluginsView) -> Model -> Model
updatePluginsView f ({ ui } as model) =
    model
        |> setUI
            { ui
                | view = f ui.view
            }


updatePluginsViewModel : (PluginsViewModel -> PluginsViewModel) -> Model -> Model
updatePluginsViewModel f =
    updatePluginsView (\v -> { v | viewModel = f v.viewModel })


{-| Initialize with non selectable plugins and then set plugins
-}
setViewModelPlugins : Dict PluginId Plugin -> PluginsViewModel -> PluginsViewModel
setViewModelPlugins plugins model =
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
    setPlugins d >> updatePluginsViewModel (setViewModelPlugins d)


pluginsFromList : List Plugin -> Dict PluginId Plugin
pluginsFromList plugins =
    plugins |> List.map (\p -> ( p.id, p )) |> Dict.fromList


setPlugins : Dict PluginId Plugin -> Model -> Model
setPlugins plugins model =
    { model | plugins = plugins }


setModalState : ModalState -> PluginsView -> PluginsView
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


setSettingsError : PluginSettingsError -> Model -> Model
setSettingsError error ({ ui } as model) =
    let
        updateError v =
            { v | error = Just <| ViewSettingsError error }
    in
    { model | ui = { ui | view = updateError ui.view, loading = False } }


pluginSettingsError : PluginsView -> Maybe PluginSettingsError
pluginSettingsError v =
    case v.error of
        Just (ViewSettingsError err) ->
            Just err

        _ ->
            Nothing


{-| Process an error : sets the modal to an error state and set the error
-}
processActionError : ( String, String ) -> Model -> Model
processActionError error model =
    model
        |> updatePluginsView (setModalStateError error)
        |> updatePluginsView (setActionError error)
        |> setLoading False


setActionError : ( String, String ) -> PluginsView -> PluginsView
setActionError error model =
    { model | error = Just <| ViewActionError error }


setModalStateError : ( String, String ) -> PluginsView -> PluginsView
setModalStateError error ({ modalState } as model) =
    case modalState of
        OpenModal action _ ->
            { model | modalState = ErrorModal action error }

        _ ->
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
