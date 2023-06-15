module NodeProperties.JsonEncoder exposing (..)

import Json.Encode exposing (encode, string, object, list)
import Json.Decode exposing (decodeValue)

import NodeProperties.DataTypes exposing (..)
import NodeProperties.JsonDecoder exposing (..)

encodeProperty : Model -> EditProperty -> String -> Json.Encode.Value
encodeProperty model property action =
  let
    encodeProp : EditProperty -> Json.Encode.Value
    encodeProp p =
      object (
        [ ( "name"  , string p.name  )
        , ( "value" , string (if action == "Delete" then "" else p.value) )
        ] )
    reason  = action ++ " property '" ++ property.name ++ "' to " ++ model.objectType ++ " '" ++ model.nodeId ++ "'"
    newProperty = [ property ]
  in
    object (
      [ ( "properties" , list encodeProp newProperty )
      , ( "reason"     , string reason )
      ] )