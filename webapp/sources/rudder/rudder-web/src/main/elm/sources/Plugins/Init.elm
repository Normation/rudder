module Plugins.Init exposing (..)

import Plugins.ApiCalls exposing (getPluginInfos)
import Plugins.DataTypes exposing (..)


subscriptions : Model -> Sub Msg
subscriptions model =
    Sub.none


init : { contextPath : String } -> ( Model, Cmd Msg )
init flags =
    let
        initUI =
            UI [] NoModal Nothing

        initModel =
            Model flags.contextPath noGlobalLicense [] initUI
    in
    ( initModel
    , getPluginInfos initModel
    )
