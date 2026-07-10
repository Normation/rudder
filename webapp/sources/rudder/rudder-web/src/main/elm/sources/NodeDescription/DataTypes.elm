module NodeDescription.DataTypes exposing (..)

import Http exposing (Error)
import NodeCompliance.DataTypes exposing (NodeId)


type alias Model =
    { nodeId : NodeId
    , contextPath : String
    , currentDescription : String -- in this app, "description" and "documentation" denote the same field
    , newDescription : String
    , editMode : EditMode
    }


type EditMode
    = NoNodeWriteAuth
    | HasNodeWriteAuth Mode


type Mode
    = Read
    | Write


type Msg
    = GetNodeDescription (Result Error String)
    | SetNodeDescription (Result Error String)
    | EditNodeDescription String
    | ToggleMode
    | SubmitDescription
