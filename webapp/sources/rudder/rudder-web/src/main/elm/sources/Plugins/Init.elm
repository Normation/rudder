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
            { loading = True, view = ViewPluginsList { selected = Set.empty, modalState = NoModal, installAction = { disabled = True } } }

        initModel =
            { contextPath = flags.contextPath
            , license = Nothing
            , plugins = []
            , ui = initUI
            }
    in
    ( initModel
    , getPluginInfos initModel
    )
