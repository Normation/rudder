module NodeDescription.Init exposing (init)

import NodeCompliance.DataTypes exposing (NodeId)
import NodeDescription.ApiCalls exposing (..)
import NodeDescription.DataTypes exposing (Model, Msg)


init : { nodeId : String, contextPath : String, nodeWrite : Bool } -> ( Model, Cmd Msg )
init flags =
    let
        initModel =
            Model (NodeId flags.nodeId) flags.contextPath "" "" flags.nodeWrite
    in
    ( initModel, Cmd.batch [ getNodeDescription initModel ] )
