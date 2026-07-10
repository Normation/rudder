module NodeDescription.Init exposing (init)

import NodeCompliance.DataTypes exposing (NodeId)
import NodeDescription.ApiCalls exposing (..)
import NodeDescription.DataTypes exposing (EditMode(..), Mode(..), Model, Msg)


init : { nodeId : String, contextPath : String, nodeWrite : Bool } -> ( Model, Cmd Msg )
init flags =
    let
        mode =
            if flags.nodeWrite then
                HasNodeWriteAuth Read

            else
                NoNodeWriteAuth

        initModel =
            Model (NodeId flags.nodeId) flags.contextPath "" "" mode
    in
    ( initModel, Cmd.batch [ getNodeDescription initModel ] )
