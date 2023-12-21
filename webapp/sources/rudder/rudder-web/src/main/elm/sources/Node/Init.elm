port module Node.Init exposing (..)

import Node.DataTypes exposing (..)
import Node.ApiCalls exposing (getScoreDetails)

import NodeCompliance.DataTypes exposing (NodeId)
import ComplianceScore.DataTypes exposing (ItemType(..))

-- PORTS / SUBSCRIPTIONS
port errorNotification   : String -> Cmd msg


subscriptions : Model -> Sub Msg
subscriptions _ = Sub.none

init : { id : String, contextPath : String } -> ( Model, Cmd Msg )
init flags =
  let
    initModel = Model (NodeId flags.id) Nothing flags.contextPath
  in
    ( initModel
    , getScoreDetails initModel
    )