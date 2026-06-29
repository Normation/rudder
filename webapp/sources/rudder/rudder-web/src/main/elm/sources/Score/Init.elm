port module Score.Init exposing (..)

import Dict
import NodeCompliance.DataTypes exposing (NodeId)
import Rules.DataTypes exposing (RuleId)
import Score.ApiCalls exposing (getScore, getScoreInfo)
import Score.DataTypes exposing (..)



-- PORTS / SUBSCRIPTIONS


port errorNotification : String -> Cmd msg


subscriptions : Model -> Sub Msg
subscriptions _ =
    Sub.none


init : { id : String, contextPath : String } -> ( Model, Cmd Msg )
init flags =
    let
        initModel =
            Model (NodeId flags.id) Nothing flags.contextPath [] Nothing

        action =
            Cmd.batch [ getScore initModel, getScoreInfo initModel ]
    in
    ( initModel
    , action
    )
