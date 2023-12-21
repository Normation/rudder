port module ComplianceScore.Init exposing (..)

import ComplianceScore.DataTypes exposing (..)
import ComplianceScore.ApiCalls exposing (getComplianceScore)

import NodeCompliance.DataTypes exposing (NodeId)
import Rules.DataTypes exposing (RuleId)

-- PORTS / SUBSCRIPTIONS
port errorNotification   : String -> Cmd msg


subscriptions : Model -> Sub Msg
subscriptions _ = Sub.none

init : { item : String, id : String, contextPath : String } -> ( Model, Cmd Msg )
init flags =
  let
    itemType = case flags.item of
      "node" -> Just ( Node (NodeId flags.id) )
      "rule" -> Just ( Rule (RuleId flags.id) )
      _ -> Nothing

    initModel = Model itemType Nothing flags.contextPath

    action = case itemType of
      Just iT -> getComplianceScore initModel iT
      Nothing -> errorNotification ("Cannot get compliance score. Reason : unknown object '" ++ flags.item ++ "'")

  in
    ( initModel
    , action
    )