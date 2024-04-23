module NodeProperties.Init exposing (..)

import Dict exposing (Dict)

import NodeProperties.DataTypes exposing (..)
import NodeProperties.ApiCalls exposing (getNodeProperties)

import Ui.Datatable exposing (defaultTableFilters)


init : { contextPath : String, hasNodeWrite : Bool, hasNodeRead : Bool, nodeId : String, objectType : String} -> ( Model, Cmd Msg )
init flags =
  let
    initUi = UI flags.hasNodeWrite flags.hasNodeRead True NoModal Dict.empty [] (defaultTableFilters Name)
    initModel = Model flags.contextPath flags.nodeId flags.objectType [] (EditProperty "" "" StringFormat True True False) initUi
  in
    ( initModel
    , getNodeProperties initModel
    )