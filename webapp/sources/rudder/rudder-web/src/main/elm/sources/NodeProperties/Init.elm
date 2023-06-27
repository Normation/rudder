module NodeProperties.Init exposing (..)

import Dict exposing (Dict)

import NodeProperties.DataTypes exposing (..)
import NodeProperties.ApiCalls exposing (getNodeProperties)


init : { contextPath : String, hasWriteRights : Bool, hasReadRights : Bool, nodeId : String, objectType : String} -> ( Model, Cmd Msg )
init flags =
  let
    initUi = UI flags.hasWriteRights flags.hasReadRights True NoModal Dict.empty [] (TableFilters Name Asc "")
    initModel = Model flags.contextPath flags.nodeId flags.objectType [] (EditProperty "" "" StringFormat True True) initUi
  in
    ( initModel
    , getNodeProperties initModel
    )