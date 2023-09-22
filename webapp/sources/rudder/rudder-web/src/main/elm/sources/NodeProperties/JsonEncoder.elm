module NodeProperties.JsonEncoder exposing (..)

import Json.Encode exposing (encode, string, object, list)
import Json.Decode exposing (decodeValue, decodeString)

import NodeProperties.DataTypes exposing (..)
import NodeProperties.JsonDecoder exposing (..)

encodeProperty : Model -> List EditProperty -> String -> Json.Encode.Value
encodeProperty model properties action =
  let
    encodeProp : EditProperty -> Json.Encode.Value
    encodeProp p =
      let
        trimValue = (String.trim p.value)
        value = if action == "Delete" then string "" else
          case p.format of
            JsonFormat ->
              decodeString Json.Decode.value trimValue
                |> Result.withDefault (Json.Encode.string trimValue)
            StringFormat -> string trimValue
      in
      object (
        [ ( "name"  , string (String.trim p.name)  )
        , ( "value" , value )
        ] )

    propertyTxt = if List.length properties > 1 then " properties" else
      case List.head properties of
        Just p  -> " property '" ++ (String.trim p.name) ++ "'"
        Nothing -> " property"

    reason  = action ++ propertyTxt ++ " to " ++ model.objectType ++ " '" ++ model.nodeId ++ "'"
  in
    object (
      [ ( "properties" , list encodeProp properties )
      , ( "reason"     , string reason )
      ] )