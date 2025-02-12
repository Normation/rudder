module Plugins.DataTypes exposing (..)

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
    = Enabled
    | Disabled
    | Uninstalled


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


type alias InstallActionModel =
    { disabled : Bool }


type alias PluginsViewModel =
    { plugins : List PluginInfo
    , selected : Set PluginId
    , modalState : ModalState
    , installAction : InstallActionModel
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
    model |> updatePluginsViewModel (processSelect_ select)


processSelect_ : Select -> PluginsViewModel -> PluginsViewModel
processSelect_ select model =
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


updatePluginsViewModel : (PluginsViewModel -> PluginsViewModel) -> Model -> Model
updatePluginsViewModel f ({ ui } as model) =
    model
        |> setUI
            { ui
                | view =
                    case model.ui.view of
                        ViewPluginsList plugins ->
                            ViewPluginsList <| f plugins

                        ViewSettingsError _ ->
                            model.ui.view
            }


setSelected : Set PluginId -> PluginsViewModel -> PluginsViewModel
setSelected plugins model =
    { model
        | selected = plugins
        , installAction = { disabled = Set.isEmpty plugins }
    }


setPlugins : List PluginInfo -> Model -> Model
setPlugins plugins =
    updatePluginsViewModel (\viewModel -> { viewModel | plugins = plugins })


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
    Ordering.explicit [ Enabled, Disabled, Uninstalled ]


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
