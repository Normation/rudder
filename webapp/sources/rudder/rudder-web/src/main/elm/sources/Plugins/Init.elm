module Plugins.Init exposing (..)

import Dict
import Plugins.Action exposing (initPluginsAction)
import Plugins.ApiCalls exposing (getPluginInfos)
import Plugins.DataTypes exposing (..)
import Plugins.Select exposing (noSelected)
import Task
import Time exposing (millisToPosix)


subscriptions : Model -> Sub Msg
subscriptions model =
    Sub.none


init : { contextPath : String } -> ( Model, Cmd Msg )
init flags =
    let
        initUI : UI
        initUI =
            { loading = True, view = ViewPluginsList initPluginsViewModel }

        initModel : Model
        initModel =
            { contextPath = flags.contextPath
            , license = Nothing
            , now = millisToPosix 0
            , plugins = Dict.empty
            , ui = initUI
            }
    in
    ( initModel
    , Cmd.batch [ getInitTime, getPluginInfos initModel ]
    )


getInitTime : Cmd Msg
getInitTime =
    Time.now |> Task.perform Now


initPluginsViewModel : PluginsViewModel
initPluginsViewModel =
    { plugins = Dict.empty
    , selected = noSelected
    , modalState = NoModal
    , filters = initFilters
    , installAction = initPluginsAction
    , enableAction = initPluginsAction
    , disableAction = initPluginsAction
    , uninstallAction = initPluginsAction
    }
