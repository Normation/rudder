module Agentpolicymode.Init exposing (..)

import Agentpolicymode.ApiCalls exposing (..)
import Agentpolicymode.DataTypes exposing (..)

init : { contextPath : String, hasWriteRights : Bool, nodeId : String } -> ( Model, Cmd Msg )
init flags =
  let
    form   = if String.isEmpty flags.nodeId then GlobalForm else (NodeForm flags.nodeId)
    initUi = UI flags.hasWriteRights form False
    initSettings    = Settings None False
    initModel       = Model flags.contextPath None initSettings initSettings initUi
    getPolicyMode   = if initModel.ui.form == GlobalForm then Cmd.none else (getNodePolicyMode initModel flags.nodeId)
    listInitActions =
      [ getGlobalPolicyMode initModel
      , getPolicyModeOverridable initModel
      , getPolicyMode
      ]
  in
    ( initModel
    , Cmd.batch listInitActions
    )