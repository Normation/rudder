module NodeProperties.JsonEncoder exposing (..)

import Json.Encode exposing (encode, string, object, list)
import Json.Decode exposing (decodeValue)

import NodeProperties.DataTypes exposing (..)
import NodeProperties.JsonDecoder exposing (..)

encodeProperty : Model -> List EditProperty -> String -> Json.Encode.Value
encodeProperty model properties action =
  let
    encodeProp : EditProperty -> Json.Encode.Value
    encodeProp p =
      object (
        [ ( "name"  , string p.name  )
        , ( "value" , string (if action == "Delete" then "" else p.value) )
        ] )
    -- reason  = action ++ " property '" ++ property.name ++ "' to " ++ model.objectType ++ " '" ++ model.nodeId ++ "'"
    reason = "test"
  in
    object (
      [ ( "properties" , list encodeProp properties )
      , ( "reason"     , string reason )
      ] )