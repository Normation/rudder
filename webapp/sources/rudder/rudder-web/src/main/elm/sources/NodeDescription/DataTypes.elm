module NodeDescription.DataTypes exposing (..)

import Http exposing (Error)
import NodeCompliance.DataTypes exposing (NodeId)


type alias Model =
    { nodeId : NodeId
    , contextPath : String
    , currentDescription : String
    , newDescription : String
    , nodeWrite : Bool
    }


type Msg
    = GetNodeDescription (Result Error String)
    | SetNodeDescription (Result Error String)
    | EditNodeDescription String
    | SubmitDescription
