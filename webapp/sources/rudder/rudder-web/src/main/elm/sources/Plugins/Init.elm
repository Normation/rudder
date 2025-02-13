module Plugins.Init exposing (..)

import Plugins.ApiCalls exposing (getPluginInfos)
import Plugins.DataTypes exposing (..)
import Set


subscriptions : Model -> Sub Msg
subscriptions model =
    Sub.none


init : { contextPath : String } -> ( Model, Cmd Msg )
init flags =
    let
        initUI =
            { loading = True, view = ViewPluginsList initPluginsViewModel }

        initModel =
            { contextPath = flags.contextPath
            , license = Nothing
            , ui = initUI
            }
    in
    ( initModel
    , getPluginInfos initModel
    )


initPluginsViewModel : PluginsViewModel
initPluginsViewModel =
    { plugins = [], selected = Set.empty, modalState = NoModal, installAction = InstallActionDisabled }
