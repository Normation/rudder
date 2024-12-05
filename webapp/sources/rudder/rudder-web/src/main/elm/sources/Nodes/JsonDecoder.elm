module Nodes.JsonDecoder exposing (..)

import Json.Decode exposing (..)
import Json.Decode.Pipeline exposing (..)

import Nodes.DataTypes exposing (..)


-- GENERAL
decodeGetNodes =
  at [ "data", "nodes" ] (list decodeNode)

decodeGetNodeDetails =
  list decodeNode

decodeNode : Decoder Node
decodeNode =
  succeed Node
    |> required "id" (map NodeId string)
    |> required "name" string

