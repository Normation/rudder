module Dashboard.JsonEncoder exposing (..)

import Json.Encode exposing (Value, object, int, list, string)

encodeRestEventLogFilter : Value
encodeRestEventLogFilter  =
  let
    data = object
      [ ("draw" , int 1 )
      , ("start" , int 0 )
      , ("length" , int 20 )
      , ("order" , list string [] )
      ]
  in
    data