module Nodes.JsonEncoder exposing (..)

import Json.Encode exposing (Value, list, object, string)
import Json.Encode.Extra exposing (maybe)
import Nodes.DataTypes exposing (..)


encodeDetails : Model -> Value
encodeDetails model =
    let
        data =
            object
                [ ( "properties", list string [] )
                , ( "software", list string [] )
                ]
    in
    data
