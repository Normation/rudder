module Healthcheck.Init exposing (..)

import Healthcheck.ApiCalls exposing (getHealthCheck)
import Healthcheck.DataTypes exposing (Model, Msg, SeverityLevel(..), TabMenu(..))


subscriptions : Model -> Sub Msg
subscriptions model =
    Sub.none

init : { contextPath : String } -> ( Model, Cmd Msg )
init flags =
    let
        initModel = Model flags.contextPath [] General
    in
    ( initModel
    , getHealthCheck initModel
    )