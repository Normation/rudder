port module Score.Init exposing (..)

import Dict
import Score.DataTypes exposing (..)
import Score.ApiCalls exposing (getScore)

import NodeCompliance.DataTypes exposing (NodeId)
import Rules.DataTypes exposing (RuleId)

-- PORTS / SUBSCRIPTIONS
port errorNotification   : String -> Cmd msg


subscriptions : Model -> Sub Msg
subscriptions _ = Sub.none

init : { id : String, contextPath : String } -> ( Model, Cmd Msg )
init flags =
  let
    initModel = Model (NodeId flags.id) Nothing flags.contextPath

    action = getScore initModel
  in
    ( initModel
    , action
    )