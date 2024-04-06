module NodeProperties.Init exposing (..)

import Dict exposing (Dict)

import NodeProperties.DataTypes exposing (..)
import NodeProperties.ApiCalls exposing (getNodeProperties)

import Ui.Datatable exposing (defaultTableFilters)


init : { contextPath : String, hasNodeWrite : Bool, hasNodeRead : Bool, nodeId : String, objectType : String} -> ( Model, Cmd Msg )
init flags =
  let
    pagination = TablePagination 1 1 10 0
    initUi = UI flags.hasNodeWrite flags.hasNodeRead True NoModal Dict.empty [] (TableFiltersOnProperty Name Asc "") (TableFiltersOnUsage Name Asc "" Directives pagination)
    initModel = Model flags.contextPath flags.nodeId flags.objectType [] (EditProperty "" "" StringFormat True True False) initUi
  in
    ( initModel
    , getNodeProperties initModel
    )