module Nodes.Init exposing (..)

import Nodes.DataTypes exposing (..)
import Nodes.ApiCalls exposing (getNodeDetails)


init : { contextPath : String, hasReadRights : Bool, policyMode : String} -> ( Model, Cmd Msg )
init flags =
  let
    initUi = UI flags.hasReadRights True (TableFilters Hostname Asc "") False [] -- TODO : Get columns list from browser cache
    initModel = Model flags.contextPath flags.policyMode [] initUi
  in
    ( initModel
    , getNodeDetails initModel
    )