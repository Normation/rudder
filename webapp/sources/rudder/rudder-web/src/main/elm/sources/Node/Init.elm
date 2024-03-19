port module Node.Init exposing (..)

import Dict
import Node.DataTypes exposing (..)
import Node.ApiCalls exposing (getScoreDetails, getScoreInfo)

import NodeCompliance.DataTypes exposing (NodeId)

-- PORTS / SUBSCRIPTIONS
port errorNotification   : String -> Cmd msg

init : { id : String, contextPath : String } -> ( Model, Cmd Msg )
init flags =
  let
    initModel = Model (NodeId flags.id) [] Dict.empty flags.contextPath []
  in
    ( initModel
    , Cmd.batch [ getScoreDetails initModel, getScoreInfo initModel ]
    )