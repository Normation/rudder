module Tags.JsonEncoder exposing (..)

import Json.Encode exposing (..)

import Tags.DataTypes exposing (..)

encodeTags : List Tag -> String
encodeTags tags =
  encode 0 (list encodeTag tags)

encodeTag : Tag -> Value
encodeTag tag =
  object (
    [ ( "key"   , string tag.key   )
    , ( "value" , string tag.value )
    ]
  )