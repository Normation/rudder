module NodeDescription.Init exposing (init)

import NodeCompliance.DataTypes exposing (NodeId)
import NodeDescription.ApiCalls exposing (..)
import NodeDescription.DataTypes exposing (Model, Msg)


init : { nodeId : String, contextPath : String, username : String } -> ( Model, Cmd Msg )
init flags =
    let
        initModel =
            Model (NodeId flags.nodeId) flags.contextPath "" "" False
    in
    ( initModel, Cmd.batch [ getNodeDescription initModel, getNodeWriteAuth initModel flags.username ] )
