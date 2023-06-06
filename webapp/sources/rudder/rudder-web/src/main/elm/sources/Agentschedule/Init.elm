module Agentschedule.Init exposing (..)

import Agentschedule.DataTypes exposing (..)


init : { contextPath : String, hasWriteRights : Bool, schedule : Maybe Schedule, globalRun : Maybe Schedule } -> ( Model, Cmd Msg )
init flags =
  let
    initUi = UI flags.hasWriteRights
    initModel = Model flags.contextPath flags.globalRun flags.schedule flags.schedule initUi
  in
    ( initModel
    , Cmd.none
    )